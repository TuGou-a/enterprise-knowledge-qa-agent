package com.mango.backend.qa.application;

import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.runtime.RetrievalLedger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;
import static com.mango.backend.contract.search.SearchTypes.*;

/** 当前限制可恢复，历史降级和排序警告保留到请求结束。 */
public final class RequestRetrievalLedger implements RetrievalLedger {
    private final LinkedHashSet<RecallProblem> recalls = new LinkedHashSet<>();
    private final LinkedHashSet<SearchScope> truncated = new LinkedHashSet<>();
    private final LinkedHashMap<ChunkKey, ReadProblem> reads = new LinkedHashMap<>();
    private final List<Failure> warnings = new ArrayList<>();
    private boolean degraded;
    private boolean vectorSucceeded;
    private boolean keywordSucceeded;
    private boolean rerankSucceeded;
    private boolean rerankFailed;
    private boolean rerankSkipped;
    private final LinkedHashSet<RankingMode> rankingModes = new LinkedHashSet<>();

    @Override
    public synchronized void recordSearch(SearchResult result) {
        var scope = result.scope();
        recalls.removeIf(problem -> covers(scope, problem.scope())
                && (problem.route() == RecallRoute.VECTOR
                ? result.routes().vector() : result.routes().keyword()) == RouteStatus.OK);
        recalls.addAll(result.unresolvedErrors());
        if (!result.truncated()) {
            truncated.removeIf(previous -> covers(scope, previous));
        } else {
            truncated.add(scope);
        }
        vectorSucceeded |= result.routes().vector() == RouteStatus.OK;
        keywordSucceeded |= result.routes().keyword() == RouteStatus.OK;
        rerankSucceeded |= result.routes().rerank() == RouteStatus.OK;
        rerankFailed |= result.routes().rerank() == RouteStatus.ERROR;
        rerankSkipped |= result.routes().rerank() == RouteStatus.SKIPPED_EMPTY;
        degraded |= result.degraded();
        warnings.addAll(result.rankingWarnings());
        if (result.rankingMode() != RankingMode.NONE) {
            rankingModes.add(result.rankingMode());
        }
    }

    private static boolean covers(SearchScope next, SearchScope previous) {
        return next.snapshotId().equals(previous.snapshotId())
                && next.query().equals(previous.query()) && next.topK() >= previous.topK();
    }

    @Override
    public synchronized void recordRead(ReadProblem problem) {
        reads.put(problem.key(), problem);
    }

    @Override
    public synchronized void resolveRead(ChunkKey key) {
        reads.remove(key);
    }

    @Override
    public synchronized RetrievalSummary summary() {
        boolean vectorFailed = recalls.stream().anyMatch(p -> p.route() == RecallRoute.VECTOR);
        boolean keywordFailed = recalls.stream().anyMatch(p -> p.route() == RecallRoute.KEYWORD);
        var vector = vectorFailed ? RouteStatus.ERROR
                : vectorSucceeded ? RouteStatus.OK : RouteStatus.NOT_RUN;
        var keyword = keywordFailed ? RouteStatus.ERROR
                : keywordSucceeded ? RouteStatus.OK : RouteStatus.NOT_RUN;
        var rerank = rerankFailed ? RouteStatus.ERROR : rerankSucceeded ? RouteStatus.OK
                : rerankSkipped ? RouteStatus.SKIPPED_EMPTY : RouteStatus.NOT_RUN;
        var ranking = rankingModes.isEmpty() ? RankingMode.NONE
                : rankingModes.size() > 1 ? RankingMode.MIXED : rankingModes.iterator().next();
        return new RetrievalSummary(degraded, !truncated.isEmpty(),
                new Routes(vector, keyword, rerank), ranking, warnings,
                List.copyOf(recalls), List.copyOf(reads.values()), List.copyOf(truncated));
    }
}
