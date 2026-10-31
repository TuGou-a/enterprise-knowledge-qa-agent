package com.mango.backend.contract.search;

import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.model.ModelTypes.Usage;

import java.util.List;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;

public final class SearchTypes {
    private SearchTypes() {}

    public enum RouteStatus { OK, ERROR, SKIPPED_EMPTY, NOT_RUN }
    public enum RecallRoute { VECTOR, KEYWORD }
    public enum RankingMode { RERANK, RRF_FALLBACK, NONE, MIXED }
    public enum CandidateOrigin { PRIMARY, RELATED_TOPIC }
    public record Routes(RouteStatus vector, RouteStatus keyword, RouteStatus rerank) {}
    /** query 为边界校验后 strip() 的原始查询；不做改写、大小写或语义等价合并。 */
    public record SearchRequest(String query, int topK) {}
    public record SearchScope(String snapshotId, String query, int topK) {}
    public record RecallProblem(SearchScope scope, RecallRoute route, Failure failure) {}
    public record ReadProblem(ChunkKey key, Failure failure) {}

    public record Candidate(ChunkPreview source, Integer vectorRank, Integer keywordRank,
                            double rrfScore, Double rerankScore, CandidateOrigin origin) {}
    public record TopicGroup(String topicKey, List<ChunkKey> members, boolean truncated) {
        public TopicGroup {
            members = List.copyOf(members);
        }
    }
    public record SearchMetrics(int vectorCount, int keywordCount, int uniqueCount,
                                int rerankInputCount, int primaryCount, int relatedCount,
                                long rerankMillis, String rerankResponseId, Usage rerankUsage,
                                List<ChunkKey> rerankInputKeys) {
        public SearchMetrics {
            rerankInputKeys = List.copyOf(rerankInputKeys);
        }
    }

    public record SearchResult(SearchScope scope, List<Candidate> primaryCandidates,
                               List<Candidate> relatedCandidates, List<TopicGroup> topicGroups,
                               Routes routes, RankingMode rankingMode, List<Failure> rankingWarnings,
                               List<RecallProblem> unresolvedErrors, boolean degraded,
                               boolean truncated, SearchMetrics metrics) {
        public SearchResult {
            primaryCandidates = List.copyOf(primaryCandidates);
            relatedCandidates = List.copyOf(relatedCandidates);
            topicGroups = List.copyOf(topicGroups);
            rankingWarnings = List.copyOf(rankingWarnings);
            unresolvedErrors = List.copyOf(unresolvedErrors);
        }
    }

    public record ReadRequest(String documentId, String version, String chunkId) {}
    public enum ReadStatus { OK, ERROR }
    /** OK 时 evidence 非空且 error 为空；ERROR 时反之。 */
    public record ReadResult(ReadStatus status, ReadEvidence evidence, Failure error) {
        public ReadResult {
            if (status == null || (status == ReadStatus.OK
                    ? evidence == null || error != null : evidence != null || error == null)) {
                throw new IllegalArgumentException("读取结果必须明确区分证据与错误");
            }
        }
    }

    /** 按运行聚合；排序回退警告与必要召回错误分开保存。 */
    public record RetrievalSummary(boolean degraded, boolean truncated, Routes routes,
                                   RankingMode rankingMode, List<Failure> rankingWarnings,
                                   List<RecallProblem> unresolvedErrors,
                                   List<ReadProblem> unresolvedReadErrors,
                                   List<SearchScope> truncatedSearches) {
        public RetrievalSummary {
            rankingWarnings = List.copyOf(rankingWarnings);
            unresolvedErrors = List.copyOf(unresolvedErrors);
            unresolvedReadErrors = List.copyOf(unresolvedReadErrors);
            truncatedSearches = List.copyOf(truncatedSearches);
        }
    }
}
