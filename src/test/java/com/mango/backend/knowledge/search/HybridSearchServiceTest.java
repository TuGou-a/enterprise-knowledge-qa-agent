package com.mango.backend.knowledge.search;

import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.knowledge.KnowledgeStore;
import com.mango.backend.contract.knowledge.KnowledgeTokenizer;
import com.mango.backend.contract.model.DenseVector;
import com.mango.backend.contract.model.EmbeddingClient;
import com.mango.backend.contract.model.ModelTypes.EmbeddingBatch;
import com.mango.backend.contract.model.ModelTypes.RerankBatch;
import com.mango.backend.contract.model.ModelTypes.RerankScore;
import com.mango.backend.contract.model.RerankClient;
import com.mango.backend.contract.runtime.BudgetSnapshot;
import com.mango.backend.contract.runtime.EvidenceRegistry;
import com.mango.backend.contract.runtime.ExecutionBudget;
import com.mango.backend.contract.runtime.RetrievalLedger;
import com.mango.backend.contract.runtime.RunContext;
import com.mango.backend.knowledge.T05TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;
import static com.mango.backend.contract.search.SearchTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class HybridSearchServiceTest {
    private HybridSearchService service;

    @AfterEach
    void disposeScheduler() {
        if (service != null) {
            service.dispose();
        }
    }

    @Test
    void fusesRoutesAndReranksTheWholeCandidatePoolBeforeSelectingTopK() {
        var a = T05TestFixtures.preview("D1", "v1", "A", "报销期限", "A 预览");
        var b = T05TestFixtures.preview("D2", "v1", "B", "报销期限", "B 预览");
        var c = T05TestFixtures.preview("D3", "v1", "C", "差旅凭证", "C 预览");
        var store = new TestStore();
        store.vectorHits = () -> List.of(hit(a, 1, 0.1), hit(b, 2, 0.2));
        store.keywordHits = () -> List.of(hit(b, 1, 0.9), hit(c, 2, 0.8));
        store.chunks = keys -> keys.stream()
                .map(key -> T05TestFixtures.chunk(key, "topic", key.chunkId() + " 完整原文"))
                .toList();
        var rerank = new RecordingRerankClient(List.of(
                new RerankScore(2, 0.9), new RerankScore(0, 0.2), new RerankScore(1, 0.7)));
        service = service(store, rerank);

        var result = service.search(new SearchRequest("报销期限", 2),
                context(store.ledger, new TestBudget(Duration.ofSeconds(120))));

        assertThat(result.primaryCandidates())
                .extracting(candidate -> candidate.source().key().chunkId())
                .containsExactly("C", "A");
        assertThat(result.routes()).isEqualTo(
                new Routes(RouteStatus.OK, RouteStatus.OK, RouteStatus.OK));
        assertThat(result.rankingMode()).isEqualTo(RankingMode.RERANK);
        assertThat(result.metrics().uniqueCount()).isEqualTo(3);
        assertThat(result.metrics().rerankInputCount()).isEqualTo(3);
        assertThat(result.topicGroups()).hasSize(2);
        assertThat(store.ledger.searchRecords).hasValue(1);
        assertThat(rerank.documents).hasSize(3);
        assertThat(rerank.topN).isEqualTo(3);
    }

    @Test
    void keepsKeywordCandidatesWhenTheVectorRouteFails() {
        var a = T05TestFixtures.preview("D1", "v1", "A", "报销期限", "A 预览");
        var store = new TestStore();
        store.vectorHits = () -> {
            throw dependency(Failure.Code.TIMEOUT, "vector timeout");
        };
        store.keywordHits = () -> List.of(hit(a, 1, 0.9));
        store.chunks = keys -> keys.stream()
                .map(key -> T05TestFixtures.chunk(key, "topic", "完整条款"))
                .toList();
        var rerank = new RecordingRerankClient(List.of(new RerankScore(0, 0.8)));
        service = service(store, rerank);

        var result = service.search(new SearchRequest("报销期限", 1),
                context(store.ledger, new TestBudget(Duration.ofSeconds(120))));

        assertThat(result.routes()).isEqualTo(
                new Routes(RouteStatus.ERROR, RouteStatus.OK, RouteStatus.OK));
        assertThat(result.primaryCandidates()).hasSize(1);
        assertThat(result.unresolvedErrors())
                .extracting(RecallProblem::route)
                .containsExactly(RecallRoute.VECTOR);
        assertThat(result.degraded()).isTrue();
    }

    @Test
    void retainsBothErrorsWhenNoRecallRouteSucceeds() {
        var store = new TestStore();
        store.vectorHits = () -> {
            throw dependency(Failure.Code.TIMEOUT, "vector timeout");
        };
        store.keywordHits = () -> {
            throw dependency(Failure.Code.DATABASE_UNAVAILABLE, "keyword unavailable");
        };
        var rerank = new RecordingRerankClient(List.of());
        service = service(store, rerank);

        var result = service.search(new SearchRequest("报销期限", 1),
                context(store.ledger, new TestBudget(Duration.ofSeconds(120))));

        assertThat(result.unresolvedErrors())
                .extracting(RecallProblem::route)
                .containsExactly(RecallRoute.VECTOR, RecallRoute.KEYWORD);
        assertThat(result.routes()).isEqualTo(
                new Routes(RouteStatus.ERROR, RouteStatus.ERROR, RouteStatus.SKIPPED_EMPTY));
        assertThat(result.rankingMode()).isEqualTo(RankingMode.NONE);
        assertThat(result.primaryCandidates()).isEmpty();
        assertThat(rerank.calls).hasValue(0);
        assertThat(store.expandCalls).hasValue(0);
        assertThat(store.ledger.searchRecords).hasValue(1);
    }

    @Test
    void fallsBackToRrfWhenRerankFailsAndStillExpandsThemes() {
        var a = T05TestFixtures.preview("D1", "v1", "A", "报销期限", "A 预览");
        var store = new TestStore();
        store.vectorHits = () -> List.of(hit(a, 1, 0.1));
        store.chunks = keys -> List.of(T05TestFixtures.chunk(keys.get(0), "报销期限", "完整条款"));
        store.expansion = new TopicExpansion(List.of(), false);
        var rerank = new RecordingRerankClient(List.of());
        rerank.failure = dependency(Failure.Code.UPSTREAM_UNAVAILABLE, "rerank unavailable");
        service = service(store, rerank);

        var result = service.search(new SearchRequest("报销期限", 1),
                context(store.ledger, new TestBudget(Duration.ofSeconds(120))));

        assertThat(result.primaryCandidates())
                .extracting(candidate -> candidate.source().key().chunkId())
                .containsExactly("A");
        assertThat(result.rankingMode()).isEqualTo(RankingMode.RRF_FALLBACK);
        assertThat(result.routes().rerank()).isEqualTo(RouteStatus.ERROR);
        assertThat(result.rankingWarnings())
                .extracting(Failure::code)
                .containsExactly(Failure.Code.UPSTREAM_UNAVAILABLE);
        assertThat(store.expandCalls).hasValue(1);
    }

    @Test
    void propagatesFatalThemeExpansionWithoutRecordingASearchResult() {
        var a = T05TestFixtures.preview("D1", "v1", "A", "报销期限", "A 预览");
        var store = new TestStore();
        store.vectorHits = () -> List.of(hit(a, 1, 0.1));
        store.chunks = keys -> List.of(T05TestFixtures.chunk(keys.get(0), "报销期限", "完整条款"));
        store.expansionFailure = dependency(Failure.Code.DATABASE_UNAVAILABLE, "topic expansion failed");
        service = service(store, new RecordingRerankClient(List.of(new RerankScore(0, 0.8))));

        var failure = catchThrowableOfType(() -> service.search(
                        new SearchRequest("报销期限", 1),
                        context(store.ledger, new TestBudget(Duration.ofSeconds(120)))),
                DependencyException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.failure().code()).isEqualTo(Failure.Code.DATABASE_UNAVAILABLE);
        assertThat(store.ledger.searchRecords).hasValue(0);
    }

    @Test
    void marksEverySeedTopicAsIncompleteWhenGlobalExpansionIsTruncated() {
        var a = T05TestFixtures.preview("D1", "v1", "A", "报销期限", "A 预览");
        var b = T05TestFixtures.preview("D3", "v1", "B", "差旅凭证", "B 预览");
        var store = new TestStore();
        store.vectorHits = () -> List.of(hit(a, 1, 0.1), hit(b, 2, 0.2));
        store.chunks = keys -> keys.stream()
                .map(key -> T05TestFixtures.chunk(key, "topic", "完整条款"))
                .toList();
        store.expansion = new TopicExpansion(List.of(), true);
        var rerank = new RecordingRerankClient(List.of(
                new RerankScore(0, 0.9), new RerankScore(1, 0.8)));
        service = service(store, rerank);

        var result = service.search(new SearchRequest("制度", 2),
                context(store.ledger, new TestBudget(Duration.ofSeconds(120))));

        assertThat(result.truncated()).isTrue();
        assertThat(result.topicGroups()).hasSize(2);
        assertThat(result.topicGroups()).allMatch(TopicGroup::truncated);
    }

    private HybridSearchService service(TestStore store, RecordingRerankClient rerank) {
        var embedding = new EmbeddingClient() {
            @Override
            public EmbeddingBatch embed(List<String> texts, Duration timeout) {
                var values = new float[DenseVector.DIMENSION];
                Arrays.fill(values, 0.01f);
                var vectors = texts.stream().map(ignored -> new DenseVector(values)).toList();
                return new EmbeddingBatch(vectors, null, null);
            }
        };
        KnowledgeTokenizer tokenizer = query -> List.of(query);
        return new HybridSearchService(store, tokenizer, embedding, rerank,
                T05TestFixtures.properties());
    }

    private RunContext context(TestLedger ledger, TestBudget budget) {
        return new RunContext("run-1", T05TestFixtures.readySnapshot(T05TestFixtures.SNAPSHOT_ID),
                budget, new TestEvidence(), ledger, null);
    }

    private static RecallHit hit(ChunkPreview preview, int rank, double score) {
        return new RecallHit(preview, rank, score);
    }

    private static DependencyException dependency(Failure.Code code, String message) {
        return new DependencyException(new Failure(code, message, true));
    }

    private static final class TestStore implements KnowledgeStore {
        Supplier<List<RecallHit>> vectorHits = List::of;
        Supplier<List<RecallHit>> keywordHits = List::of;
        Function<List<ChunkKey>, List<KnowledgeChunk>> chunks = keys -> List.of();
        TopicExpansion expansion = new TopicExpansion(List.of(), false);
        DependencyException expansionFailure;
        final AtomicInteger expandCalls = new AtomicInteger();
        final TestLedger ledger = new TestLedger();

        public Optional<CorpusSnapshot> latestReadySnapshot(Duration timeout) {
            return Optional.empty();
        }

        public Optional<CorpusSnapshot> findSnapshot(String snapshotId, Duration timeout) {
            return Optional.empty();
        }

        public void createSnapshot(CorpusSnapshot snapshot, Duration timeout) {}

        public void insertDocuments(List<KnowledgeDocument> documents, Duration timeout) {}

        public void insertChunks(List<IndexedChunk> chunks, Duration timeout) {}

        public CorpusSnapshot publishSnapshot(String snapshotId, SnapshotManifest manifest,
                                                Duration timeout) {
            throw new UnsupportedOperationException();
        }

        public List<RecallHit> searchVector(String snapshotId, DenseVector vector,
                                             int limit, Duration timeout) {
            return vectorHits.get();
        }

        public List<RecallHit> searchKeyword(String snapshotId, List<String> tokens,
                                              int limit, Duration timeout) {
            return keywordHits.get();
        }

        public TopicExpansion expandTopics(String snapshotId, List<String> topicKeys,
                                           List<ChunkKey> excluded, int limit, Duration timeout) {
            expandCalls.incrementAndGet();
            if (expansionFailure != null) {
                throw expansionFailure;
            }
            return expansion;
        }

        public List<KnowledgeChunk> loadChunks(String snapshotId, List<ChunkKey> keys,
                                                Duration timeout) {
            return chunks.apply(keys);
        }

        public Optional<ReadEvidence> read(ChunkKey key, Duration timeout) {
            return Optional.empty();
        }
    }

    private static final class RecordingRerankClient implements RerankClient {
        private final List<RerankScore> scores;
        DependencyException failure;
        final AtomicInteger calls = new AtomicInteger();
        int topN;
        List<String> documents = List.of();

        private RecordingRerankClient(List<RerankScore> scores) {
            this.scores = scores;
        }

        public RerankBatch rerank(String query, List<String> documents,
                                  int topN, Duration timeout) {
            calls.incrementAndGet();
            this.topN = topN;
            this.documents = List.copyOf(documents);
            if (failure != null) {
                throw failure;
            }
            return new RerankBatch(scores, "response-id", null);
        }
    }

    private static final class TestEvidence implements EvidenceRegistry {
        private final List<ReadEvidence> entries = new ArrayList<>();

        public void register(ReadEvidence evidence) {
            entries.add(evidence);
        }

        public Optional<ReadEvidence> find(ChunkKey key) {
            return entries.stream()
                    .filter(evidence -> evidence.chunk().key().equals(key))
                    .findFirst();
        }

        public List<ReadEvidence> entries() {
            return List.copyOf(entries);
        }
    }

    private static final class TestLedger implements RetrievalLedger {
        final AtomicInteger searchRecords = new AtomicInteger();

        public void recordSearch(SearchResult result) {
            searchRecords.incrementAndGet();
        }

        public void recordRead(ReadProblem problem) {}

        public void resolveRead(ChunkKey key) {}

        public RetrievalSummary summary() {
            return new RetrievalSummary(false, false,
                    new Routes(RouteStatus.NOT_RUN, RouteStatus.NOT_RUN, RouteStatus.NOT_RUN),
                    RankingMode.NONE, List.of(), List.of(), List.of(), List.of());
        }
    }

    private static final class TestBudget implements ExecutionBudget {
        private final Duration remaining;

        private TestBudget(Duration remaining) {
            this.remaining = remaining;
        }

        public Duration remaining() {
            return remaining;
        }

        public void recordModelIteration() {}

        public void markIterationLimitExceeded() {}

        public boolean tryAcquireToolCall() {
            return true;
        }

        public boolean tryAcquireText(int readCharacters, int toolTextCharacters) {
            return true;
        }

        public BudgetSnapshot snapshot() {
            return new BudgetSnapshot(0, 0, 6, 12, 0, remaining.toMillis(), 0, 0,
                    false, false, false, false);
        }
    }
}
