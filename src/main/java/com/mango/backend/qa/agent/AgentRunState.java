package com.mango.backend.qa.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.qa.AnswerReason;
import com.mango.backend.contract.runtime.RunContext;
import com.mango.backend.qa.application.RequestExecutionBudget;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;
import static com.mango.backend.contract.runtime.TraceEvent.*;

/** 仅本次 Agent 使用；将框架调用标识、已交付证据和业务 Trace 关联。 */
public final class AgentRunState {
    private final RunContext context;
    private final RequestExecutionBudget budget;
    private final AgentProperties properties;
    private final LinkedHashMap<String, PendingTool> tools = new LinkedHashMap<>();
    private Set<ChunkKey> observed = Set.of();
    private int iteration;
    private boolean modelActive;
    private boolean sealed;
    private boolean modelFailed;
    private boolean fatalSearch;
    private RuntimeException internalError;

    public AgentRunState(RunContext context, RequestExecutionBudget budget,
                         AgentProperties properties) {
        this.context = context;
        this.budget = budget;
        this.properties = properties;
    }

    public RunContext context() { return context; }
    public RequestExecutionBudget budget() { return budget; }

    public synchronized void modelStarted(List<Msg> messages) {
        if (sealed) { return; }
        var visible = new HashSet<ChunkKey>();
        for (var message : messages) {
            for (var block : message.getContent()) {
                if (block instanceof ToolResultBlock result) {
                    var tool = tools.get(result.getId());
                    if (tool != null && tool.delivered && tool.outcome instanceof ReadOutcome read) {
                        visible.add(read.key());
                    }
                }
            }
        }
        observed = Set.copyOf(visible);
        budget.recordModelIteration();
        iteration = budget.snapshot().reactIterations();
        modelActive = true;
        String summary = budget.snapshot().iterationLimitExceeded()
                ? "框架在轮次上限后请求汇总。" : "模型根据本次对话及已交付的工具结果选择后续动作。";
        context.trace().record(new ModelRound(iteration, ModelPhase.STARTED, summary, null));
    }

    public synchronized void modelCompleted() {
        if (sealed || !modelActive) { return; }
        modelActive = false;
        context.trace().record(new ModelRound(iteration, ModelPhase.COMPLETED,
                "模型调用完成；具体动作由后续工具事件或最终任务结果记录。", null));
    }

    public synchronized void modelFailed(Failure failure) {
        if (sealed) { return; }
        modelFailed = true;
        if (modelActive) {
            modelActive = false;
            context.trace().record(new ModelRound(iteration, ModelPhase.FAILED,
                    "模型调用未能完成。", failure));
        }
    }

    public synchronized void prepareTools(List<ToolUseBlock> calls) {
        for (var call : calls) {
            BusinessTool kind = switch (call.getName()) {
                case "searchKnowledge" -> BusinessTool.SEARCH_KNOWLEDGE;
                case "readKnowledge" -> BusinessTool.READ_KNOWLEDGE;
                default -> null;
            };
            if (kind == null) { continue; }
            var input = JsonUtils.getJsonCodec().fromJson(
                    JsonUtils.resolveToolCallArgsJson(call), JsonNode.class);
            String purpose = text(input, "purpose");
            ToolInput requested;
            ToolInput effective;
            if (kind == BusinessTool.SEARCH_KNOWLEDGE) {
                String query = text(input, "query");
                var top = input.path("topK");
                Integer requestedTop = top.isIntegralNumber() && top.canConvertToInt()
                        ? top.intValue() : null;
                int limit = properties.retrieval().primaryLimit();
                requested = new SearchInput(query, requestedTop);
                effective = new SearchInput(query == null ? null : query.strip(),
                        requestedTop == null ? limit : Math.max(1, Math.min(limit, requestedTop)));
            } else {
                requested = new ReadInput(text(input, "documentId"), text(input, "version"),
                        text(input, "chunkId"));
                effective = requested;
            }
            tools.put(call.getId(), new PendingTool(kind, requested, effective,
                    purpose == null ? null : purpose.strip(), iteration));
        }
    }

    private static String text(JsonNode input, String field) {
        var value = input.path(field);
        return value.isTextual() ? value.textValue() : null;
    }

    public synchronized void startTool(String id) {
        var tool = tools.get(id);
        if (!sealed && !tool.started) {
            tool.started = true;
            context.trace().record(new ToolStarted(id, tool.iteration, tool.kind,
                    tool.requested, tool.effective, tool.purpose));
        }
    }

    public synchronized void outcome(String id, ToolOutcome outcome, Failure deliveryError) {
        if (sealed) { return; }
        var tool = tools.get(id);
        tool.outcome = outcome;
        tool.deliveryError = deliveryError;
    }

    public synchronized void toolCompleted(String id, ToolResultState resultState) {
        var tool = tools.get(id);
        if (sealed || tool == null || tool.completed) { return; }
        startTool(id);
        tool.completed = true;
        if (tool.outcome == null) {
            tool.outcome = new ToolError(new Failure(Failure.Code.INVALID_ARGUMENT,
                    "工具参数不符合声明，请按工具定义修正后再选择动作。", true));
        }
        tool.delivered = resultState == ToolResultState.SUCCESS
                && tool.deliveryError == null && tool.outcome instanceof ReadOutcome;
        context.trace().record(new ToolCompleted(id, tool.iteration, tool.kind,
                tool.outcome, tool.deliveryError));
    }

    public synchronized boolean wasObserved(ChunkKey key) { return observed.contains(key); }
    public synchronized void failSearch() { fatalSearch = true; }
    public synchronized boolean fatalSearch() { return fatalSearch; }
    public synchronized boolean modelFailed() { return modelFailed; }
    public synchronized void internalError(RuntimeException error) { internalError = error; }
    public synchronized RuntimeException internalError() { return internalError; }

    public synchronized void readTimedOut(String id, Failure failure) {
        var tool = tools.get(id);
        if (!sealed && tool.effective instanceof ReadInput read) {
            var key = new ChunkKey(new DocumentKey(context.snapshot().snapshotId(),
                    read.documentId(), read.version()), read.chunkId());
            context.retrieval().recordRead(new com.mango.backend.contract.search.SearchTypes.ReadProblem(key, failure));
        }
    }

    /** 结束时封存 Trace；取消后迟到的阻塞工具结果不能补写成功事件。 */
    public synchronized void seal(Finished finished) {
        if (sealed) { return; }
        var failure = new Failure(finished.reason() == AnswerReason.DEADLINE_EXCEEDED
                ? Failure.Code.TIMEOUT : Failure.Code.BUDGET_EXHAUSTED,
                "本次运行已结束，工具结果未完成交付。", false);
        closePending(failure);
        context.trace().record(finished);
        sealed = true;
    }

    /** 调用方取消时只封存实际事件，不伪造已返回的任务结果。 */
    public synchronized void cancel() {
        if (sealed) { return; }
        closePending(new Failure(Failure.Code.INTERNAL_ERROR, "调用方已取消本次问答。", false));
        sealed = true;
    }

    private void closePending(Failure failure) {
        if (modelActive) {
            modelActive = false;
            context.trace().record(new ModelRound(iteration, ModelPhase.FAILED,
                    "运行结束时模型调用尚未完成。", failure));
        }
        for (var entry : tools.entrySet()) {
            var tool = entry.getValue();
            if (tool.started && !tool.completed) {
                tool.deliveryError = tool.outcome == null ? null : failure;
                if (tool.outcome == null) { tool.outcome = new ToolError(failure); }
                toolCompleted(entry.getKey(), ToolResultState.INTERRUPTED);
            }
        }
    }

    public synchronized boolean stopped() {
        var snapshot = budget.snapshot();
        return sealed || snapshot.deadlineExceeded() || snapshot.toolLimitExceeded()
                || snapshot.textLimitExceeded() || fatalSearch || internalError != null;
    }

    private static final class PendingTool {
        final BusinessTool kind;
        final ToolInput requested;
        final ToolInput effective;
        final String purpose;
        final int iteration;
        ToolOutcome outcome;
        Failure deliveryError;
        boolean started;
        boolean completed;
        boolean delivered;

        PendingTool(BusinessTool kind, ToolInput requested, ToolInput effective,
                    String purpose, int iteration) {
            this.kind = kind;
            this.requested = requested;
            this.effective = effective;
            this.purpose = purpose;
            this.iteration = iteration;
        }
    }
}
