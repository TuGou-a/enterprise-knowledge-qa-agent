package com.mango.backend.qa.agent;

import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.runtime.RunContext;
import com.mango.backend.contract.search.KnowledgeReader;
import com.mango.backend.contract.search.KnowledgeSearch;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.core.util.JsonUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;
import static com.mango.backend.contract.runtime.TraceEvent.*;
import static com.mango.backend.contract.search.SearchTypes.*;

public final class KnowledgeTools {
    private final KnowledgeSearch search;
    private final KnowledgeReader reader;
    private final AgentProperties properties;
    private final AgentRunState state;

    public KnowledgeTools(KnowledgeSearch search, KnowledgeReader reader,
                          AgentProperties properties, AgentRunState state) {
        this.search = search;
        this.reader = reader;
        this.properties = properties;
        this.state = state;
    }

    public Toolkit toolkit() {
        var toolkit = new Toolkit(ToolkitConfig.builder().parallel(false).build());
        toolkit.registerTool(this);
        // @Tool 负责 schema 和类型注入；装饰器另注入框架原始调用标识，避免 ThreadLocal。
        for (String name : new String[]{"searchKnowledge", "readKnowledge"}) {
            var delegate = toolkit.getTool(name);
            toolkit.registerAgentTool(new AgentTool() {
                @Override public String getName() { return delegate.getName(); }
                @Override public String getDescription() { return delegate.getDescription(); }
                @Override public Map<String, Object> getParameters() { return delegate.getParameters(); }
                @Override public Boolean getStrict() { return delegate.getStrict(); }
                @Override public boolean isReadOnly() { return true; }

                @Override
                public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                    return Mono.defer(() -> {
                        var remaining = state.budget().remaining();
                        String id = param.getToolUseBlock().getId();
                        state.startTool(id);
                        if (remaining.isZero() || state.stopped()) {
                            return Mono.just(reject(id, new Failure(Failure.Code.BUDGET_EXHAUSTED,
                                    "本次问答已停止，未启动新的工具调用。", false)));
                        }
                        var timeout = Duration.ofMillis(properties.timeouts().toolMs());
                        if (remaining.compareTo(timeout) < 0) { timeout = remaining; }
                        var runtime = RuntimeContext.builder(param.getRuntimeContext())
                                .put(ToolCallParam.class, param).build();
                        return delegate.callAsync(ToolCallParam.builder(param).runtimeContext(runtime).build())
                                .timeout(timeout)
                                .onErrorResume(TimeoutException.class, error -> {
                                    var failure = new Failure(Failure.Code.TIMEOUT,
                                            "工具未在剩余时限内完成。", true);
                                    if (name.equals("searchKnowledge")) {
                                        state.failSearch();
                                    } else {
                                        state.readTimedOut(id, failure);
                                    }
                                    return Mono.just(reject(id, failure));
                                });
                    });
                }
            });
        }
        return toolkit;
    }

    @Tool(name = "searchKnowledge", readOnly = true,
            description = "在本次固定资料快照检索制度候选及同主题版本。预览不是证据；回答前须读取原文。")
    public ToolResultBlock searchKnowledge(
            @ToolParam(name = "query", description = "非空查询，最多配置的查询字符数") String query,
            @ToolParam(name = "topK", required = false, description = "主候选数量；省略取服务端上限，越界夹取") Integer topK,
            @ToolParam(name = "purpose", description = "本次查询的公开用途，非空且最多200字符，不提供内部思维过程") String purpose,
            RunContext context, ToolCallParam call) {
        String id = call.getToolUseBlock().getId();
        if (query == null || query.isBlank() || query.length() > properties.limits().queryCharacters()
                || !validPurpose(purpose)) {
            return reject(id, new Failure(Failure.Code.INVALID_ARGUMENT,
                    "query 必须非空且不超过查询上限，purpose 必须为1至200字符的公开用途。", true));
        }
        if (!context.budget().tryAcquireToolCall()) {
            return reject(id, new Failure(Failure.Code.BUDGET_EXHAUSTED, "业务工具调用预算不足。", false));
        }
        int limit = properties.retrieval().primaryLimit();
        int effectiveTop = topK == null ? limit : Math.max(1, Math.min(limit, topK));
        try {
            var result = search.search(new SearchRequest(query.strip(), effectiveTop), context);
            var outcome = new SearchOutcome(result.scope(), result.primaryCandidates().stream()
                    .map(c -> c.source().key()).toList(), result.relatedCandidates().stream()
                    .map(c -> c.source().key()).toList(), result.routes(), result.rankingMode(),
                    result.rankingWarnings(), result.unresolvedErrors(), result.truncated(), result.metrics());
            int previewCharacters = result.primaryCandidates().stream()
                    .mapToInt(c -> c.source().preview().length()).sum()
                    + result.relatedCandidates().stream().mapToInt(c -> c.source().preview().length()).sum();
            if (!context.budget().tryAcquireText(0, previewCharacters)) {
                var failure = new Failure(Failure.Code.BUDGET_EXHAUSTED,
                        "候选预览超过累计工具文本预算，未向模型交付候选。", false);
                state.outcome(id, outcome, failure);
                return ToolResultBlock.error(JsonUtils.getJsonCodec().toJson(failure));
            }
            state.outcome(id, outcome, null);
            return ToolResultBlock.text(JsonUtils.getJsonCodec().toJson(result));
        } catch (DependencyException error) {
            state.failSearch();
            return reject(id, error.failure());
        } catch (RuntimeException error) {
            state.internalError(error);
            return reject(id, new Failure(Failure.Code.INTERNAL_ERROR, "检索工具内部异常。", false));
        }
    }

    @Tool(name = "readKnowledge", readOnly = true,
            description = "读取本次快照中指定文档版本的完整原文片段。须观察读取结果后，才能在后续模型调用中引用。")
    public ToolResultBlock readKnowledge(
            @ToolParam(name = "documentId", description = "候选中的文档ID") String documentId,
            @ToolParam(name = "version", description = "候选中的版本") String version,
            @ToolParam(name = "chunkId", description = "候选中的片段ID") String chunkId,
            @ToolParam(name = "purpose", description = "本次读取的公开用途，非空且最多200字符，不提供内部思维过程") String purpose,
            RunContext context, ToolCallParam call) {
        String id = call.getToolUseBlock().getId();
        if (documentId == null || documentId.isBlank() || version == null || version.isBlank()
                || chunkId == null || chunkId.isBlank() || !validPurpose(purpose)) {
            return reject(id, new Failure(Failure.Code.INVALID_ARGUMENT,
                    "文档ID、版本、片段ID必须非空，purpose 必须为1至200字符的公开用途。", true));
        }
        if (!context.budget().tryAcquireToolCall()) {
            return reject(id, new Failure(Failure.Code.BUDGET_EXHAUSTED, "业务工具调用预算不足。", false));
        }
        try {
            var result = reader.read(new ReadRequest(documentId, version, chunkId), context);
            if (result.status() == ReadStatus.ERROR) { return reject(id, result.error()); }
            var chunk = result.evidence().chunk();
            state.outcome(id, new ReadOutcome(chunk.key(), chunk.content().length(), chunk.contentHash()), null);
            return ToolResultBlock.text(JsonUtils.getJsonCodec().toJson(result));
        } catch (DependencyException error) {
            var key = new ChunkKey(new DocumentKey(context.snapshot().snapshotId(), documentId, version), chunkId);
            context.retrieval().recordRead(new ReadProblem(key, error.failure()));
            return reject(id, error.failure());
        } catch (RuntimeException error) {
            state.internalError(error);
            return reject(id, new Failure(Failure.Code.INTERNAL_ERROR, "读取工具内部异常。", false));
        }
    }

    private static boolean validPurpose(String purpose) {
        return purpose != null && !purpose.isBlank() && purpose.strip().length() <= 200;
    }

    private ToolResultBlock reject(String id, Failure failure) {
        state.outcome(id, new ToolError(failure), null);
        return ToolResultBlock.error(JsonUtils.getJsonCodec().toJson(failure));
    }
}
