package com.mango.backend.knowledge.evidence;

import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.knowledge.KnowledgeStore;
import com.mango.backend.contract.knowledge.KnowledgeTypes.ChunkKey;
import com.mango.backend.contract.knowledge.KnowledgeTypes.CorpusSnapshot;
import com.mango.backend.contract.knowledge.KnowledgeTypes.IndexedChunk;
import com.mango.backend.contract.knowledge.KnowledgeTypes.KnowledgeDocument;
import com.mango.backend.contract.knowledge.KnowledgeTypes.ReadEvidence;
import com.mango.backend.contract.knowledge.KnowledgeTypes.SnapshotManifest;
import com.mango.backend.contract.model.DenseVector;
import com.mango.backend.contract.runtime.BudgetSnapshot;
import com.mango.backend.contract.runtime.EvidenceRegistry;
import com.mango.backend.contract.runtime.ExecutionBudget;
import com.mango.backend.contract.runtime.RetrievalLedger;
import com.mango.backend.contract.runtime.RunContext;
import com.mango.backend.knowledge.T05TestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.mango.backend.contract.search.SearchTypes.*;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeEvidenceReaderTest {
    @Test
    void reservesTextBudgetBeforeRegisteringReadEvidence() {
        var key = key("A");
        var evidence = T05TestFixtures.evidence(key, "报销期限", "完整条款");
        var events = new ArrayList<String>();
        var budget = new TestBudget(events, true);
        var evidenceRegistry = new TestEvidence(events);
        var ledger = new TestLedger(events);
        var reader = new KnowledgeEvidenceReader(new TestStore(Optional.of(evidence)),
                T05TestFixtures.properties());

        var result = reader.read(new ReadRequest("D1", "v1", "A"),
                context(budget, evidenceRegistry, ledger));

        assertThat(result.status()).isEqualTo(ReadStatus.OK);
        assertThat(result.evidence()).isEqualTo(evidence);
        assertThat(result.error()).isNull();
        assertThat(events).containsExactly("budget", "evidence", "resolve");
        assertThat(budget.readCharacters).isEqualTo(evidence.chunk().content().length());
        assertThat(budget.toolTextCharacters).isEqualTo(evidence.chunk().content().length());
        assertThat(ledger.problems).isEmpty();
        assertThat(ledger.resolveCalls).hasValue(1);
    }

    @Test
    void budgetRefusalRecordsAReadProblemWithoutRegisteringEvidence() {
        var key = key("A");
        var evidence = T05TestFixtures.evidence(key, "报销期限", "完整条款");
        var budget = new TestBudget(new ArrayList<>(), false);
        var evidenceRegistry = new TestEvidence(new ArrayList<>());
        var ledger = new TestLedger(new ArrayList<>());
        var reader = new KnowledgeEvidenceReader(new TestStore(Optional.of(evidence)),
                T05TestFixtures.properties());

        var result = reader.read(new ReadRequest("D1", "v1", "A"),
                context(budget, evidenceRegistry, ledger));

        assertThat(result.status()).isEqualTo(ReadStatus.ERROR);
        assertThat(result.error().code()).isEqualTo(Failure.Code.BUDGET_EXHAUSTED);
        assertThat(evidenceRegistry.entries()).isEmpty();
        assertThat(ledger.problems).hasSize(1);
        assertThat(ledger.problems.get(0).key()).isEqualTo(key);
        assertThat(ledger.resolveCalls).hasValue(0);
    }

    @Test
    void missingChunkReturnsStructuredErrorWithoutRegisteringEvidence() {
        var evidenceRegistry = new TestEvidence(new ArrayList<>());
        var ledger = new TestLedger(new ArrayList<>());
        var reader = new KnowledgeEvidenceReader(new TestStore(Optional.empty()),
                T05TestFixtures.properties());

        var result = reader.read(new ReadRequest("D1", "v1", "missing"),
                context(new TestBudget(new ArrayList<>(), true), evidenceRegistry, ledger));

        assertThat(result.status()).isEqualTo(ReadStatus.ERROR);
        assertThat(result.error().code()).isEqualTo(Failure.Code.NOT_FOUND);
        assertThat(evidenceRegistry.entries()).isEmpty();
        assertThat(ledger.problems)
                .extracting(ReadProblem::key)
                .containsExactly(key("missing"));
        assertThat(ledger.resolveCalls).hasValue(0);
    }

    @Test
    void dependencyFailureIsReturnedAndRecordedWithoutRegistrations() {
        var failure = new DependencyException(
                new Failure(Failure.Code.DATABASE_UNAVAILABLE, "database unavailable", true));
        var evidenceRegistry = new TestEvidence(new ArrayList<>());
        var ledger = new TestLedger(new ArrayList<>());
        var reader = new KnowledgeEvidenceReader(new TestStore(() -> {
            throw failure;
        }), T05TestFixtures.properties());

        var result = reader.read(new ReadRequest("D1", "v1", "A"),
                context(new TestBudget(new ArrayList<>(), true), evidenceRegistry, ledger));

        assertThat(result.status()).isEqualTo(ReadStatus.ERROR);
        assertThat(result.error()).isEqualTo(failure.failure());
        assertThat(evidenceRegistry.entries()).isEmpty();
        assertThat(ledger.problems).hasSize(1);
        assertThat(ledger.resolveCalls).hasValue(0);
    }

    private static ChunkKey key(String chunkId) {
        return T05TestFixtures.preview("D1", "v1", chunkId, "报销期限", "预览").key();
    }

    private static RunContext context(TestBudget budget, TestEvidence evidence, TestLedger ledger) {
        return new RunContext("run-1", T05TestFixtures.readySnapshot(T05TestFixtures.SNAPSHOT_ID),
                budget, evidence, ledger, null);
    }

    private static final class TestStore implements KnowledgeStore {
        private final Supplier<Optional<ReadEvidence>> readResult;

        private TestStore(Optional<ReadEvidence> readResult) {
            this(() -> readResult);
        }

        private TestStore(Supplier<Optional<ReadEvidence>> readResult) {
            this.readResult = readResult;
        }

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

        public List<com.mango.backend.contract.knowledge.KnowledgeTypes.RecallHit>
        searchVector(String snapshotId, DenseVector vector, int limit, Duration timeout) {
            return List.of();
        }

        public List<com.mango.backend.contract.knowledge.KnowledgeTypes.RecallHit>
        searchKeyword(String snapshotId, List<String> tokens, int limit, Duration timeout) {
            return List.of();
        }

        public com.mango.backend.contract.knowledge.KnowledgeTypes.TopicExpansion expandTopics(
                String snapshotId, List<String> topicKeys, List<ChunkKey> excluded,
                int limit, Duration timeout) {
            return new com.mango.backend.contract.knowledge.KnowledgeTypes.TopicExpansion(
                    List.of(), false);
        }

        public List<com.mango.backend.contract.knowledge.KnowledgeTypes.KnowledgeChunk> loadChunks(
                String snapshotId, List<ChunkKey> keys, Duration timeout) {
            return List.of();
        }

        public Optional<ReadEvidence> read(ChunkKey key, Duration timeout) {
            return readResult.get();
        }
    }

    private static final class TestBudget implements ExecutionBudget {
        private final List<String> events;
        private final boolean allowText;
        int readCharacters;
        int toolTextCharacters;

        private TestBudget(List<String> events, boolean allowText) {
            this.events = events;
            this.allowText = allowText;
        }

        public Duration remaining() {
            return Duration.ofSeconds(120);
        }

        public void recordModelIteration() {}

        public void markIterationLimitExceeded() {}

        public boolean tryAcquireToolCall() {
            return true;
        }

        public boolean tryAcquireText(int readCharacters, int toolTextCharacters) {
            events.add("budget");
            this.readCharacters = readCharacters;
            this.toolTextCharacters = toolTextCharacters;
            return allowText;
        }

        public BudgetSnapshot snapshot() {
            return new BudgetSnapshot(0, 0, 6, 12, 0, 120000, 0, 0,
                    false, false, false, false);
        }
    }

    private static final class TestEvidence implements EvidenceRegistry {
        private final List<String> events;
        private final List<ReadEvidence> entries = new ArrayList<>();

        private TestEvidence(List<String> events) {
            this.events = events;
        }

        public void register(ReadEvidence evidence) {
            events.add("evidence");
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
        private final List<String> events;
        private final List<ReadProblem> problems = new ArrayList<>();
        final AtomicInteger resolveCalls = new AtomicInteger();

        private TestLedger(List<String> events) {
            this.events = events;
        }

        public void recordSearch(SearchResult result) {}

        public void recordRead(ReadProblem problem) {
            problems.add(problem);
        }

        public void resolveRead(ChunkKey key) {
            events.add("resolve");
            resolveCalls.incrementAndGet();
        }

        public RetrievalSummary summary() {
            return new RetrievalSummary(false, false,
                    new Routes(RouteStatus.NOT_RUN, RouteStatus.NOT_RUN, RouteStatus.NOT_RUN),
                    RankingMode.NONE, List.of(), List.of(), problems, List.of());
        }
    }
}
