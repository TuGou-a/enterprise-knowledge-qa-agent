package com.mango.backend.qa.agent;

import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.qa.AnswerReason;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.util.JsonUtils;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Mono;

public final class QaAgentMiddleware implements MiddlewareBase {
    private final AgentRunState state;
    private final AgentProperties properties;

    public QaAgentMiddleware(AgentRunState state, AgentProperties properties) {
        this.state = state;
        this.properties = properties;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input).doOnNext(event -> {
            if (event instanceof ExceedMaxItersEvent) {
                state.budget().markIterationLimitExceeded();
            } else if (event instanceof ToolResultEndEvent result) {
                state.toolCompleted(result.getToolCallId(), result.getState());
            }
        });
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            state.prepareTools(input.toolCalls());
            return next.apply(input);
        });
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            if (state.stopped()) {
                return Flux.error(new AgentExecutionStopped(state.fatalSearch()
                        ? AnswerReason.ROUTE_FAILED : AnswerReason.BUDGET_EXHAUSTED));
            }
            var options = input.options();
            // 包含系统提示词、历史、工具观察、工具定义和结构化输出 schema。
            String serialized = JsonUtils.getJsonCodec().toJson(List.of(input.messages(), input.tools(),
                    options != null && options.getResponseFormat() != null
                            ? options.getResponseFormat() : ""));
            long conservativeTokens = serialized.getBytes(StandardCharsets.UTF_8).length;
            if (serialized.length() > properties.limits().modelInputCharacters()
                    || conservativeTokens + properties.deepseek().maxOutputTokens()
                    > properties.deepseek().contextWindow()) {
                state.budget().markInputLimitExceeded();
                return Flux.error(new AgentExecutionStopped(AnswerReason.INPUT_LIMIT_EXCEEDED));
            }
            var timeout = Duration.ofMillis(properties.timeouts().modelMs());
            var remaining = state.budget().remaining();
            if (remaining.isZero()) {
                return Flux.error(new AgentExecutionStopped(AnswerReason.DEADLINE_EXCEEDED));
            }
            if (remaining.compareTo(timeout) < 0) { timeout = remaining; }
            var execution = ExecutionConfig.builder().timeout(timeout).maxAttempts(1).build();
            var boundedOptions = GenerateOptions.mergeOptions(
                    GenerateOptions.builder().executionConfig(execution).build(), options);
            return Flux.defer(() -> next.apply(new ModelCallInput(
                            input.messages(), input.tools(), boundedOptions, input.model())))
                    .takeUntilOther(Mono.delay(timeout).then(Mono.error(
                            new java.util.concurrent.TimeoutException("模型单次总时限已到。"))))
                    .doOnNext(event -> {
                        if (event instanceof ModelCallStartEvent) {
                            state.modelStarted(input.messages());
                        } else if (event instanceof ModelCallEndEvent) {
                            state.modelCompleted();
                        }
                    })
                    .onErrorMap(error -> {
                        boolean timedOut = false;
                        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                            if (cause instanceof java.util.concurrent.TimeoutException) {
                                timedOut = true;
                                break;
                            }
                        }
                        state.modelFailed(new Failure(timedOut
                                ? Failure.Code.TIMEOUT : Failure.Code.UPSTREAM_UNAVAILABLE,
                                "模型请求失败，未取得可靠业务草稿。", true));
                        return new AgentExecutionStopped(AnswerReason.MODEL_FAILED);
                    });
        });
    }
}
