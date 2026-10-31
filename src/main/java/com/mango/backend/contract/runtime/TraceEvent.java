package com.mango.backend.contract.runtime;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.qa.AnswerReason;
import com.mango.backend.contract.qa.TaskStatus;

import java.time.Instant;
import java.util.List;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;
import static com.mango.backend.contract.search.SearchTypes.*;

/** T06 分配单调递增序号和时间；不记录模型原始推理、鉴权信息或外部原始错误体。 */
public record TraceEvent(String runId, long sequence, Instant timestamp, Detail detail) {
    public enum BusinessTool { SEARCH_KNOWLEDGE, READ_KNOWLEDGE }
    public enum ModelPhase { STARTED, COMPLETED, FAILED }
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Started.class, name = "RUN_STARTED"),
            @JsonSubTypes.Type(value = ModelRound.class, name = "MODEL_ROUND"),
            @JsonSubTypes.Type(value = ToolStarted.class, name = "TOOL_STARTED"),
            @JsonSubTypes.Type(value = ToolCompleted.class, name = "TOOL_COMPLETED"),
            @JsonSubTypes.Type(value = Finished.class, name = "RUN_FINISHED")
    })
    public sealed interface Detail permits Started, ModelRound, ToolStarted, ToolCompleted, Finished {}
    public record Started(String snapshotId, String frameworkVersion,
                          String model, IndexProfile indexProfile, String rerankModel,
                          int routeLimit, int candidatePoolLimit, int primaryLimit,
                          int relatedLimit, int rrfK) implements Detail {}
    public record ModelRound(int iteration, ModelPhase phase, String publicSummary,
                             Failure error) implements Detail {}
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = SearchInput.class, name = "SEARCH_INPUT"),
            @JsonSubTypes.Type(value = ReadInput.class, name = "READ_INPUT")
    })
    public sealed interface ToolInput permits SearchInput, ReadInput {}
    public record SearchInput(String query, Integer topK) implements ToolInput {}
    public record ReadInput(String documentId, String version, String chunkId) implements ToolInput {}
    public record ToolStarted(String callId, int iteration, BusinessTool tool, ToolInput requested,
                              ToolInput effective, String publicPurpose) implements Detail {}
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = SearchOutcome.class, name = "SEARCH_RESULT"),
            @JsonSubTypes.Type(value = ReadOutcome.class, name = "READ_RESULT"),
            @JsonSubTypes.Type(value = ToolError.class, name = "TOOL_ERROR")
    })
    public sealed interface ToolOutcome permits SearchOutcome, ReadOutcome, ToolError {}
    public record SearchOutcome(SearchScope scope, List<ChunkKey> primary, List<ChunkKey> related,
                                Routes routes, RankingMode rankingMode, List<Failure> rankingWarnings,
                                List<RecallProblem> unresolvedErrors, boolean truncated,
                                SearchMetrics metrics) implements ToolOutcome {
        public SearchOutcome {
            primary = List.copyOf(primary);
            related = List.copyOf(related);
            rankingWarnings = List.copyOf(rankingWarnings);
            unresolvedErrors = List.copyOf(unresolvedErrors);
        }
    }
    public record ReadOutcome(ChunkKey key, int characters, String contentHash) implements ToolOutcome {}
    public record ToolError(Failure failure) implements ToolOutcome {}
    public record ToolCompleted(String callId, int iteration, BusinessTool tool,
                                ToolOutcome outcome, Failure deliveryError) implements Detail {}
    public record Finished(TaskStatus status, AnswerReason reason, BudgetSnapshot budget,
                           List<ChunkKey> citations) implements Detail {
        public Finished {
            citations = List.copyOf(citations);
        }
    }
}
