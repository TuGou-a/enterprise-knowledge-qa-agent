package com.mango.backend.knowledge.search;

import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.config.DatabaseProperties;
import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.knowledge.KnowledgeStore;
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
import com.mango.backend.infra.pg.KnowledgeMapper;
import com.mango.backend.knowledge.evidence.KnowledgeEvidenceReader;
import com.mango.backend.knowledge.ingest.CorpusImporter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;
import static com.mango.backend.contract.search.SearchTypes.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = T05PostgresIntegrationTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class T05PostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("knowledge_agent")
                    .withUsername("test")
                    .withPassword("test");
    private static final String ORIGINAL_SOCKS_PROXY_HOST = System.getProperty("socksProxyHost");
    private static final String ORIGINAL_SOCKS_PROXY_PORT = System.getProperty("socksProxyPort");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CorpusImporter importer;

    @Autowired
    private KnowledgeStore store;

    @Autowired
    private HybridSearchService search;

    @Autowired
    private KnowledgeEvidenceReader reader;

    @Autowired
    private RecordingRerankClient rerank;

    private String normalSnapshotId;
    private String conflictSnapshotId;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        // Spring may resolve dynamic properties before Testcontainers starts @Container.
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("knowledge-agent.deepseek.api-key", () -> "test-deepseek-key");
        registry.add("knowledge-agent.siliconflow.api-key", () -> "test-siliconflow-key");
    }

    @BeforeAll
    void createSchema() throws Exception {
        // Maven may inject a SOCKS proxy that cannot route Testcontainers loopback ports.
        System.clearProperty("socksProxyHost");
        System.clearProperty("socksProxyPort");
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(Files.readString(
                    Path.of("src/main/resources/db/V001__knowledge.sql")));
        }
    }

    @AfterAll
    void restoreSocksProxyProperties() {
        if (ORIGINAL_SOCKS_PROXY_HOST != null) {
            System.setProperty("socksProxyHost", ORIGINAL_SOCKS_PROXY_HOST);
        }
        if (ORIGINAL_SOCKS_PROXY_PORT != null) {
            System.setProperty("socksProxyPort", ORIGINAL_SOCKS_PROXY_PORT);
        }
    }

    @BeforeEach
    void importFixedCorpora() {
        jdbc.execute("TRUNCATE TABLE kb_chunk, kb_document, kb_corpus");
        rerank.reset();
        normalSnapshotId = importer.importCorpus(
                Path.of("data/corpus/normal.json"), Duration.ofMinutes(2)).snapshotId();
        conflictSnapshotId = importer.importCorpus(
                Path.of("data/corpus/conflict.json"), Duration.ofMinutes(2)).snapshotId();
    }

    @Test
    void searchesAndReadsWithinOneFixedPostgresSnapshot() {
        var latest = store.latestReadySnapshot(Duration.ofSeconds(5)).orElseThrow();
        var normal = store.findSnapshot(normalSnapshotId, Duration.ofSeconds(5)).orElseThrow();
        assertThat(latest.snapshotId()).isEqualTo(conflictSnapshotId);
        assertThat(normal.state()).isEqualTo(SnapshotState.READY);

        var evidence = new RecordingEvidence();
        var ledger = new RecordingLedger();
        var conflict = search.search(new SearchRequest("报销期限", 1),
                context("conflict-run", latest, evidence, ledger));

        assertThat(conflict.routes()).isEqualTo(
                new Routes(RouteStatus.OK, RouteStatus.OK, RouteStatus.OK));
        assertThat(conflict.rankingMode()).isEqualTo(RankingMode.RERANK);
        assertThat(conflict.metrics().vectorCount()).isEqualTo(8);
        assertThat(conflict.metrics().keywordCount()).isPositive();
        assertThat(conflict.metrics().uniqueCount()).isEqualTo(8);
        assertThat(conflict.metrics().rerankInputCount()).isEqualTo(8);
        assertThat(rerank.topN).isEqualTo(rerank.documentCount);
        assertThat(rerank.documentCount).isEqualTo(conflict.metrics().rerankInputCount());
        assertThat(conflict.metrics().rerankInputKeys())
                .hasSize(8)
                .doesNotHaveDuplicates()
                .allSatisfy(key -> assertThat(key.document().snapshotId())
                        .isEqualTo(conflictSnapshotId));
        assertThat(conflict.primaryCandidates())
                .extracting(candidate -> candidate.source().key().document().documentId())
                .containsExactly("D01");
        assertThat(conflict.relatedCandidates())
                .extracting(candidate -> candidate.source().key().document().documentId())
                .contains("D02");
        assertThat(evidence.entries()).isEmpty();
        assertThat(ledger.searchRecords).hasValue(1);

        var primaryKey = conflict.primaryCandidates().get(0).source().key();
        var read = reader.read(new ReadRequest(primaryKey.document().documentId(),
                        primaryKey.document().version(), primaryKey.chunkId()),
                context("read-run", latest, evidence, new RecordingLedger()));
        assertThat(read.status()).isEqualTo(ReadStatus.OK);
        assertThat(read.evidence().chunk().content()).contains("30 个自然日");
        assertThat(evidence.entries()).hasSize(1);

        var normalResult = search.search(new SearchRequest("报销期限", 6),
                context("normal-run", normal, new RecordingEvidence(), new RecordingLedger()));
        assertThat(java.util.stream.Stream.concat(
                normalResult.primaryCandidates().stream(),
                normalResult.relatedCandidates().stream()))
                .extracting(candidate -> candidate.source().key().document().documentId())
                .doesNotContain("D02");

        var missing = reader.read(new ReadRequest("D02", "v1", "clause-1"),
                context("cross-snapshot-run", normal, new RecordingEvidence(), new RecordingLedger()));
        assertThat(missing.status()).isEqualTo(ReadStatus.ERROR);
        assertThat(missing.error().code()).isEqualTo(Failure.Code.NOT_FOUND);
    }

    @Test
    void fallsBackToRrfWhenThePostgresBackedSearchCannotRerank() {
        rerank.failNext();
        var latest = store.latestReadySnapshot(Duration.ofSeconds(5)).orElseThrow();
        var ledger = new RecordingLedger();

        var result = search.search(new SearchRequest("报销期限", 3),
                context("fallback-run", latest, new RecordingEvidence(), ledger));

        assertThat(result.rankingMode()).isEqualTo(RankingMode.RRF_FALLBACK);
        assertThat(result.routes().rerank()).isEqualTo(RouteStatus.ERROR);
        assertThat(result.primaryCandidates()).isNotEmpty();
        assertThat(result.rankingWarnings())
                .extracting(Failure::code)
                .containsExactly(Failure.Code.UPSTREAM_UNAVAILABLE);
        assertThat(result.metrics().rerankInputCount()).isPositive();
        assertThat(ledger.searchRecords).hasValue(1);
    }

    private static RunContext context(String runId, CorpusSnapshot snapshot,
                                      EvidenceRegistry evidence, RetrievalLedger ledger) {
        return new RunContext(runId, snapshot, new TestBudget(), evidence, ledger, null);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties({AgentProperties.class, DatabaseProperties.class})
    @MapperScan(basePackageClasses = KnowledgeMapper.class)
    @ComponentScan(basePackages = {
            "com.mango.backend.infra.pg",
            "com.mango.backend.knowledge.ingest",
            "com.mango.backend.knowledge.search",
            "com.mango.backend.knowledge.evidence"
    })
    static class Config {
        @Bean
        EmbeddingClient testEmbeddingClient() {
            return (texts, timeout) -> {
                var values = new float[DenseVector.DIMENSION];
                Arrays.fill(values, 0.01f);
                var vectors = texts.stream().map(ignored -> new DenseVector(values)).toList();
                return new EmbeddingBatch(vectors, "test-embedding", null);
            };
        }

        @Bean
        RecordingRerankClient testRerankClient() {
            return new RecordingRerankClient();
        }
    }

    static final class RecordingRerankClient implements RerankClient {
        private boolean failNext;
        int topN;
        int documentCount;

        void reset() {
            failNext = false;
            topN = 0;
            documentCount = 0;
        }

        void failNext() {
            failNext = true;
        }

        public RerankBatch rerank(String query, List<String> documents,
                                  int topN, Duration timeout) {
            this.topN = topN;
            this.documentCount = documents.size();
            if (failNext) {
                failNext = false;
                throw new DependencyException(new Failure(Failure.Code.UPSTREAM_UNAVAILABLE,
                        "test rerank unavailable", true));
            }
            var scores = new ArrayList<RerankScore>(documents.size());
            for (int index = 0; index < documents.size(); index++) {
                String document = documents.get(index);
                double score;
                if (document.contains("30 个自然日")) {
                    score = 0.99;
                } else if (document.contains("15 个自然日")) {
                    score = 0.95;
                } else if (document.contains("报销期限")) {
                    score = 0.9;
                } else {
                    score = 0.8 - index * 0.001;
                }
                scores.add(new RerankScore(index, score));
            }
            return new RerankBatch(scores, "test-rerank", null);
        }
    }

    private static final class RecordingEvidence implements EvidenceRegistry {
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

    private static final class RecordingLedger implements RetrievalLedger {
        final AtomicInteger searchRecords = new AtomicInteger();
        final List<ReadProblem> readProblems = new ArrayList<>();

        public void recordSearch(SearchResult result) {
            searchRecords.incrementAndGet();
        }

        public void recordRead(ReadProblem problem) {
            readProblems.add(problem);
        }

        public void resolveRead(ChunkKey key) {}

        public RetrievalSummary summary() {
            return new RetrievalSummary(false, false,
                    new Routes(RouteStatus.NOT_RUN, RouteStatus.NOT_RUN, RouteStatus.NOT_RUN),
                    RankingMode.NONE, List.of(), List.of(), readProblems, List.of());
        }
    }

    private static final class TestBudget implements ExecutionBudget {
        public Duration remaining() {
            return Duration.ofSeconds(120);
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
            return new BudgetSnapshot(0, 0, 6, 12, 0, 120000, 0, 0,
                    false, false, false, false);
        }
    }
}
