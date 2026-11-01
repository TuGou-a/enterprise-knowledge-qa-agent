package com.mango.backend.knowledge.search;

import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.knowledge.KnowledgeStore;
import com.mango.backend.contract.knowledge.KnowledgeTokenizer;
import com.mango.backend.contract.model.EmbeddingClient;
import com.mango.backend.contract.model.ModelTypes.RerankBatch;
import com.mango.backend.contract.model.ModelTypes.Usage;
import com.mango.backend.contract.model.RerankClient;
import com.mango.backend.contract.runtime.RunContext;
import com.mango.backend.contract.search.KnowledgeSearch;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;
import static com.mango.backend.contract.search.SearchTypes.*;

/** 只返回资料线索；内部加载的重排序全文不登记为已读证据。 */
@Component
public final class HybridSearchService implements KnowledgeSearch {
    private final KnowledgeStore store;
    private final KnowledgeTokenizer tokenizer;
    private final EmbeddingClient embedding;
    private final RerankClient rerank;
    private final AgentProperties properties;
    // 外层同步工具也运行于共享 boundedElastic，召回分支需避免排在等待它们的父调用之后。
    private final Scheduler recallScheduler = Schedulers.newBoundedElastic(
            Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE, Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
            "knowledge-recall");

    public HybridSearchService(KnowledgeStore store, KnowledgeTokenizer tokenizer,
                               EmbeddingClient embedding, RerankClient rerank,
                               AgentProperties properties) {
        this.store = store;
        this.tokenizer = tokenizer;
        this.embedding = embedding;
        this.rerank = rerank;
        this.properties = properties;
    }

    @Override
    public SearchResult search(SearchRequest request, RunContext context) {
        var budget = new SearchBudget(context, properties.timeouts().toolMs(),
                properties.timeouts().databaseMs());
        var config = properties.retrieval();
        var timeouts = properties.timeouts();
        String snapshotId = context.snapshot().snapshotId();
        var scope = new SearchScope(snapshotId, request.query(), request.topK());

        // 两个分支只计算自己的结果；账本和证据集合不在工作线程中修改。
        var vectorTask = recall(() -> {
            var batch = embedding.embed(List.of(request.query()),
                    budget.timeout(timeouts.embeddingMs(), false));
            return store.searchVector(snapshotId, batch.vectors().get(0), config.routeLimit(),
                    budget.timeout(timeouts.databaseMs(), false));
        }, budget);
        var keywordTask = recall(() -> {
            var tokens = tokenizer.tokenize(request.query());
            return store.searchKeyword(snapshotId, tokens, config.routeLimit(),
                    budget.timeout(timeouts.databaseMs(), false));
        }, budget);
        var recalled = Mono.zip(vectorTask, keywordTask).block();
        var vector = recalled.getT1();
        var keyword = recalled.getT2();
        var errors = new ArrayList<RecallProblem>();
        if (vector.error() != null) {
            errors.add(new RecallProblem(scope, RecallRoute.VECTOR, vector.error()));
        }
        if (keyword.error() != null) {
            errors.add(new RecallProblem(scope, RecallRoute.KEYWORD, keyword.error()));
        }

        var fused = new HashMap<ChunkKey, Candidate>();
        for (var hit : vector.hits()) {
            fused.put(hit.candidate().key(), new Candidate(hit.candidate(), hit.rank(), null,
                    1.0 / (config.rrfK() + (double) hit.rank()), null, CandidateOrigin.PRIMARY));
        }
        for (var hit : keyword.hits()) {
            var previous = fused.get(hit.candidate().key());
            double contribution = 1.0 / (config.rrfK() + (double) hit.rank());
            fused.put(hit.candidate().key(), new Candidate(hit.candidate(),
                    previous == null ? null : previous.vectorRank(), hit.rank(),
                    contribution + (previous == null ? 0 : previous.rrfScore()), null,
                    CandidateOrigin.PRIMARY));
        }
        var pool = fused.values().stream()
                .sorted(Comparator.comparingDouble(Candidate::rrfScore).reversed()
                        .thenComparing(candidate -> candidate.source().key(), CHUNK_ORDER))
                .limit(config.candidatePoolLimit()).toList();
        var inputKeys = pool.stream().map(candidate -> candidate.source().key()).toList();
        var poolRanks = new HashMap<ChunkKey, Integer>();
        var scoredPool = new HashMap<ChunkKey, Candidate>();
        for (int index = 0; index < pool.size(); index++) {
            poolRanks.put(inputKeys.get(index), index + 1);
            scoredPool.put(inputKeys.get(index), pool.get(index));
        }

        var warnings = new ArrayList<Failure>();
        var rankingMode = RankingMode.NONE;
        var rerankStatus = RouteStatus.SKIPPED_EMPTY;
        List<ChunkKey> sentKeys = List.of();
        long rerankMillis = 0;
        String responseId = null;
        Usage usage = null;
        List<Candidate> primary = pool.stream().limit(request.topK()).toList();
        if (!pool.isEmpty()) {
            rankingMode = RankingMode.RRF_FALLBACK;
            rerankStatus = RouteStatus.ERROR;
            try {
                var chunks = store.loadChunks(snapshotId, inputKeys,
                        budget.timeout(timeouts.databaseMs(), true));
                var documents = chunks.stream().map(KnowledgeChunk::content).toList();
                var timeout = budget.timeout(timeouts.rerankMs(), true);
                sentKeys = inputKeys;
                long started = System.nanoTime();
                RerankBatch batch;
                try {
                    batch = rerank.rerank(request.query(), documents, documents.size(), timeout);
                } finally {
                    rerankMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
                }
                var ranked = new ArrayList<Candidate>();
                for (var score : batch.scores()) {
                    var original = pool.get(score.inputIndex());
                    var candidate = new Candidate(original.source(), original.vectorRank(),
                            original.keywordRank(), original.rrfScore(), score.relevanceScore(),
                            CandidateOrigin.PRIMARY);
                    ranked.add(candidate);
                    scoredPool.put(candidate.source().key(), candidate);
                }
                ranked.sort(Comparator.comparingDouble((Candidate candidate) -> candidate.rerankScore())
                        .reversed().thenComparingInt(candidate -> poolRanks.get(candidate.source().key()))
                        .thenComparing(candidate -> candidate.source().key(), CHUNK_ORDER));
                primary = ranked.stream().limit(request.topK()).toList();
                rankingMode = RankingMode.RERANK;
                rerankStatus = RouteStatus.OK;
                responseId = batch.responseId();
                usage = batch.usage();
            } catch (DependencyException exception) {
                // 排序增强失败不转成必要召回失败；主候选整批沿用 RRF。
                warnings.add(exception.failure());
            }
        }

        var topics = pool.stream().map(candidate -> candidate.source().topicKey())
                .distinct().sorted(TEXT_ORDER).toList();
        var related = new ArrayList<Candidate>();
        boolean truncated = false;
        if (!topics.isEmpty()) {
            var excluded = primary.stream().map(candidate -> candidate.source().key()).toList();
            // 扩展失败不能返回看似完整的结果，交由工具边界处理致命错误。
            var expansion = store.expandTopics(snapshotId, topics, excluded, config.relatedLimit(),
                    budget.timeout(timeouts.databaseMs(), false));
            truncated = expansion.truncated();
            for (var preview : expansion.candidates()) {
                var original = scoredPool.get(preview.key());
                related.add(new Candidate(preview, original == null ? null : original.vectorRank(),
                        original == null ? null : original.keywordRank(),
                        original == null ? 0 : original.rrfScore(),
                        original == null ? null : original.rerankScore(), CandidateOrigin.RELATED_TOPIC));
            }
        }
        var members = new HashMap<String, List<ChunkKey>>();
        for (var candidate : primary) {
            members.computeIfAbsent(candidate.source().topicKey(), ignored -> new ArrayList<>())
                    .add(candidate.source().key());
        }
        for (var candidate : related) {
            members.computeIfAbsent(candidate.source().topicKey(), ignored -> new ArrayList<>())
                    .add(candidate.source().key());
        }
        var groups = new ArrayList<TopicGroup>();
        for (var topic : topics) {
            var keys = members.getOrDefault(topic, List.of()).stream().sorted(CHUNK_ORDER).toList();
            groups.add(new TopicGroup(topic, keys, truncated));
        }
        var routes = new Routes(vector.error() == null ? RouteStatus.OK : RouteStatus.ERROR,
                keyword.error() == null ? RouteStatus.OK : RouteStatus.ERROR, rerankStatus);
        var metrics = new SearchMetrics(vector.hits().size(), keyword.hits().size(), fused.size(),
                sentKeys.size(), primary.size(), related.size(), rerankMillis, responseId, usage, sentKeys);
        var result = new SearchResult(scope, primary, related, groups, routes, rankingMode, warnings,
                errors, !errors.isEmpty() || !warnings.isEmpty(), truncated, metrics);
        context.retrieval().recordSearch(result);
        return result;
    }

    private Mono<RecallResult> recall(Supplier<List<RecallHit>> operation, SearchBudget budget) {
        return Mono.fromCallable(() -> new RecallResult(operation.get(), null))
                .subscribeOn(recallScheduler)
                .onErrorResume(DependencyException.class,
                        exception -> Mono.just(new RecallResult(List.of(), exception.failure())))
                .timeout(budget.remaining(), Mono.fromSupplier(() -> new RecallResult(List.of(),
                        new Failure(Failure.Code.TIMEOUT, "检索路由未在工具剩余时限内完成", true))));
    }

    @PreDestroy
    public void dispose() {
        recallScheduler.dispose();
    }

    private record RecallResult(List<RecallHit> hits, Failure error) {}

    /** 两个召回分支共享同一截止时间；每次调用即时收紧预算。 */
    private static final class SearchBudget {
        private final RunContext context;
        private final long started = System.nanoTime();
        private final Duration toolTimeout;
        private final Duration expansionReserve;

        private SearchBudget(RunContext context, long toolMs, long databaseMs) {
            this.context = context;
            toolTimeout = Duration.ofMillis(toolMs);
            expansionReserve = Duration.ofMillis(databaseMs);
        }

        private Duration remaining() {
            var toolRemaining = toolTimeout.minusNanos(System.nanoTime() - started);
            var requestRemaining = context.budget().remaining();
            var remaining = toolRemaining.compareTo(requestRemaining) < 0 ? toolRemaining : requestRemaining;
            return remaining.isNegative() ? Duration.ZERO : remaining;
        }

        private Duration timeout(long configuredMs, boolean reserveExpansion) {
            var available = remaining();
            if (reserveExpansion) {
                available = available.minus(expansionReserve);
            }
            if (available.isZero() || available.isNegative()) {
                throw new DependencyException(new Failure(Failure.Code.BUDGET_EXHAUSTED,
                        reserveExpansion ? "为主题扩展保留预算后，重排序可用时间不足" : "检索工具剩余时间不足",
                        false));
            }
            var configured = Duration.ofMillis(configuredMs);
            return configured.compareTo(available) < 0 ? configured : available;
        }
    }
}
