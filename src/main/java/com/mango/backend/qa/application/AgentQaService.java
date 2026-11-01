package com.mango.backend.qa.application;

import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.knowledge.KnowledgeStore;
import com.mango.backend.contract.qa.AnswerDraft;
import com.mango.backend.contract.qa.AnswerEnvelope;
import com.mango.backend.contract.qa.AnswerReason;
import com.mango.backend.contract.qa.AnswerValidator;
import com.mango.backend.contract.qa.QaRequest;
import com.mango.backend.contract.qa.QaService;
import com.mango.backend.contract.qa.TaskStatus;
import com.mango.backend.contract.runtime.RunContext;
import com.mango.backend.contract.runtime.TraceRecorder;
import com.mango.backend.qa.agent.AgentExecutionStopped;
import com.mango.backend.qa.agent.AgentRunState;
import com.mango.backend.qa.agent.QaAgentFactory;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;
import static com.mango.backend.contract.runtime.TraceEvent.*;

@Service
public final class AgentQaService implements QaService {
    private final KnowledgeStore store;
    private final AgentProperties properties;
    private final QaAgentFactory agents;
    private final AnswerValidator validator;
    private final TraceRecorder.Factory traces;

    public AgentQaService(KnowledgeStore store, AgentProperties properties, QaAgentFactory agents,
                          AnswerValidator validator, TraceRecorder.Factory traces) {
        this.store = store;
        this.properties = properties;
        this.agents = agents;
        this.validator = validator;
        this.traces = traces;
    }

    @Override
    public Mono<AnswerEnvelope> answer(QaRequest request) {
        return Mono.defer(() -> {
            String runId = UUID.randomUUID().toString();
            var budget = new RequestExecutionBudget(properties);
            return Mono.fromCallable(() -> {
                var timeout = Duration.ofMillis(properties.timeouts().databaseMs());
                var remaining = budget.remaining();
                if (remaining.isZero()) {
                    throw new DependencyException(new Failure(Failure.Code.TIMEOUT,
                            "问答建立运行前已超时。", true));
                }
                if (remaining.compareTo(timeout) < 0) { timeout = remaining; }
                var snapshot = store.latestReadySnapshot(timeout).orElseThrow(() ->
                        new DependencyException(new Failure(Failure.Code.SNAPSHOT_UNAVAILABLE,
                                "当前没有可用的已发布资料快照。", false)));
                var index = properties.index();
                var expected = new IndexProfile(properties.siliconflow().embeddingModel(), 1024,
                        index.preprocessingVersion(), index.tokenizerVersion(),
                        index.dictionaryVersion(), index.stopWordsVersion());
                if (!snapshot.indexProfile().equals(expected)) {
                    throw new DependencyException(new Failure(Failure.Code.INDEX_PROFILE_MISMATCH,
                            "资料快照与当前索引配置不匹配。", false));
                }
                return snapshot;
            }).subscribeOn(Schedulers.boundedElastic())
                    .timeout(budget.remaining(), Mono.error(new DependencyException(
                            new Failure(Failure.Code.TIMEOUT, "选择资料快照超时。", true))))
                    .flatMap(snapshot -> execute(request, runId, snapshot, budget));
        });
    }

    private Mono<AnswerEnvelope> execute(QaRequest request, String runId, CorpusSnapshot snapshot,
                                          RequestExecutionBudget budget) {
        var context = new RunContext(runId, snapshot, budget,
                new RequestEvidenceRegistry(snapshot.snapshotId()), new RequestRetrievalLedger(),
                traces.create(runId));
        var state = new AgentRunState(context, budget, properties);
        var retrieval = properties.retrieval();
        context.trace().record(new Started(snapshot.snapshotId(), "2.0.4", properties.deepseek().model(),
                snapshot.indexProfile(), properties.siliconflow().rerankModel(), retrieval.routeLimit(),
                retrieval.candidatePoolLimit(), retrieval.primaryLimit(), retrieval.relatedLimit(), retrieval.rrfK()));
        var messages = new ArrayList<Msg>();
        for (var history : request.history()) {
            var role = history.role() == QaRequest.Role.USER ? MsgRole.USER : MsgRole.ASSISTANT;
            messages.add(Msg.builder().name(history.role() == QaRequest.Role.USER ? "user" : "assistant")
                    .role(role).textContent(history.content()).build());
        }
        messages.add(Msg.builder().name("user").role(MsgRole.USER).textContent(request.question()).build());
        var runtime = RuntimeContext.builder().put(RunContext.class, context).build();
        return Mono.defer(() -> agents.create(state).call(messages, AnswerDraft.class, runtime))
                .timeout(budget.remaining())
                .map(message -> finish(state, message, null))
                .switchIfEmpty(Mono.fromSupplier(() -> finish(state, null, AnswerReason.OUTPUT_INVALID)))
                .onErrorResume(AgentExecutionStopped.class,
                        error -> Mono.fromSupplier(() -> finish(state, null, error.reason())))
                .onErrorResume(TimeoutException.class, error -> {
                    budget.markDeadlineExceeded();
                    return Mono.fromSupplier(() -> finish(state, null, AnswerReason.DEADLINE_EXCEEDED));
                })
                .doOnCancel(state::cancel);
    }

    private AnswerEnvelope finish(AgentRunState state, Msg message, AnswerReason forced) {
        if (state.internalError() != null) { throw state.internalError(); }
        var context = state.context();
        if (message != null && message.getGenerateReason() == GenerateReason.MAX_ITERATIONS) {
            state.budget().markIterationLimitExceeded();
        }
        if (state.modelFailed()) { forced = AnswerReason.MODEL_FAILED; }
        AnswerDraft draft = null;
        if (message != null && message.hasStructuredData()) {
            try {
                draft = message.getStructuredData(AnswerDraft.class);
            } catch (IllegalArgumentException error) {
                // 模型内容不能解析时交 T06 按无效草稿处理，不误报为模型请求失败。
                draft = null;
            }
        }
        if (state.hasUnobservedCitation(draft)) {
            draft = null;
            if (forced == null) { forced = AnswerReason.OUTPUT_INVALID; }
        }
        var checked = validator.validate(draft, context);
        if (forced == null && state.fatalSearch()
                && checked.reason() != AnswerReason.OUTPUT_INVALID
                && checked.reason() != AnswerReason.CITATION_INVALID) {
            forced = AnswerReason.ROUTE_FAILED;
        }
        var budget = state.budget().snapshot();
        // 校验期间也可能跨过截止点，最终状态以这份快照为准。
        if (budget.deadlineExceeded()) {
            forced = AnswerReason.DEADLINE_EXCEEDED;
        } else if (budget.toolLimitExceeded()) {
            forced = AnswerReason.BUDGET_EXHAUSTED;
        } else if (budget.iterationLimitExceeded()) {
            forced = AnswerReason.MAX_ITERATIONS;
        } else if (budget.textLimitExceeded()) {
            forced = AnswerReason.INPUT_LIMIT_EXCEEDED;
        }
        if (forced != null) {
            var reason = forced;
            String explanation = failureMessage(reason);
            var gaps = new LinkedHashSet<String>();
            if (draft != null) { gaps.addAll(checked.evidenceGaps()); }
            gaps.add(explanation);
            var summary = context.retrieval().summary();
            for (var problem : summary.unresolvedErrors()) {
                gaps.add("必要召回路由 " + problem.route() + " 尚未恢复（" + problem.failure().code() + "）。");
            }
            for (var problem : summary.unresolvedReadErrors()) {
                var key = problem.key();
                gaps.add("原文读取尚未完成：" + key.document().documentId() + "/"
                        + key.document().version() + "/" + key.chunkId()
                        + "（" + problem.failure().code() + "）。");
            }
            if (summary.truncated()) { gaps.add("同主题资料尚未完整查证。"); }
            checked = new AnswerValidator.ValidatedAnswer(TaskStatus.INCOMPLETE, reason, explanation,
                    checked.citations(), List.copyOf(gaps), null);
        }
        var envelope = new AnswerEnvelope(context.runId(), context.snapshot().snapshotId(),
                checked.status(), checked.reason(), checked.answer(), checked.citations(), checked.evidenceGaps(),
                checked.clarifyingQuestion(), context.retrieval().summary(), budget);
        state.seal(new Finished(envelope.status(), envelope.reason(), budget, envelope.citations().stream()
                .map(citation -> new ChunkKey(new DocumentKey(envelope.snapshotId(), citation.documentId(),
                        citation.version()), citation.chunkId())).toList()));
        return envelope;
    }

    private static String failureMessage(AnswerReason reason) {
        return switch (reason) {
            case DEADLINE_EXCEEDED -> "本次问答已达到总时限，尚未完成查证。";
            case BUDGET_EXHAUSTED -> "业务工具调用预算已耗尽，尚未完成查证。";
            case MAX_ITERATIONS -> "已达到模型推理轮次上限，尚未完成查证。";
            case INPUT_LIMIT_EXCEEDED -> "完整模型输入或查证文本超过执行预算，尚未完成查证。";
            case MODEL_FAILED -> "模型请求失败，未取得可靠回答。";
            case ROUTE_FAILED -> "必要检索未能完成，尚未完成查证。";
            case OUTPUT_INVALID -> "模型草稿无效或引用了生成前尚未观察的原文，无法提供可靠回答。";
            default -> throw new IllegalArgumentException("不属于问答服务处理的终止原因：" + reason);
        };
    }
}
