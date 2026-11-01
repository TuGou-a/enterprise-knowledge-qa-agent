package com.mango.backend.knowledge.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.config.DatabaseProperties;
import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.knowledge.KnowledgeStore;
import com.mango.backend.contract.knowledge.KnowledgeTokenizer;
import com.mango.backend.contract.model.EmbeddingClient;
import com.mango.backend.contract.model.RerankClient;
import com.mango.backend.evaluation.RetrievalEvaluation;
import com.mango.backend.infra.pg.KnowledgeMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;
import static com.mango.backend.evaluation.RetrievalEvaluation.*;

/** 显式真实评估入口：不导入或发布资料，不调用 DeepSeek，不替代完整问答验收。 */
@Tag("real")
@ActiveProfiles("real-evaluation")
@SpringBootTest(classes = RealRetrievalEvaluationTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RealRetrievalEvaluationTest {
    @Autowired private AgentProperties properties;
    @Autowired private CorpusSourceReader reader;
    @Autowired private KnowledgeStore store;
    @Autowired private KnowledgeTokenizer tokenizer;
    @Autowired private EmbeddingClient embedding;
    @Autowired private RerankClient rerank;

    @Test
    void comparesFixedSnapshotsAndWritesActualResults() throws Exception {
        var labels = RetrievalEvaluation.load(Path.of("examples/retrieval-labels.json"));
        String supplied = System.getProperty("evaluation.snapshots");
        if (supplied == null || supplied.isBlank()) {
            throw new IllegalArgumentException("需要 -Devaluation.snapshots=normal=<id>,conflict=<id>,mutation=<id>");
        }
        var ids = new HashMap<String, String>();
        for (String assignment : supplied.split(",")) {
            var parts = assignment.split("=", 2);
            if (parts.length != 2 || parts[1].isBlank() || ids.putIfAbsent(parts[0], parts[1]) != null) {
                throw new IllegalArgumentException("资料快照映射无效或重复");
            }
        }
        if (!ids.keySet().equals(labels.corpora().stream().map(Corpus::corpusId)
                .collect(java.util.stream.Collectors.toSet()))) {
            throw new IllegalArgumentException("必须为标注中的每份资料显式指定实际 READY 快照");
        }
        var snapshots = new LinkedHashMap<String, CorpusSnapshot>();
        var timeout = Duration.ofMillis(properties.timeouts().databaseMs());
        var configuredProfile = new IndexProfile(properties.siliconflow().embeddingModel(), 1024,
                properties.index().preprocessingVersion(), properties.index().tokenizerVersion(),
                properties.index().dictionaryVersion(), properties.index().stopWordsVersion());
        for (var corpus : labels.corpora()) {
            var snapshot = store.findSnapshot(ids.get(corpus.corpusId()), timeout).orElseThrow();
            if (snapshot.state() != SnapshotState.READY || !snapshot.contentHash().equals(corpus.contentHash())
                    || !snapshot.indexProfile().equals(configuredProfile)) {
                throw new IllegalArgumentException("快照状态、资料摘要或索引配置与评估不一致：" + corpus.corpusId());
            }
            var prepared = reader.read(Path.of(corpus.path()), snapshot.snapshotId());
            if (!prepared.contentHash().equals(corpus.contentHash())) {
                throw new IllegalArgumentException("参考资料集合哈希与实际切分结果不一致");
            }
            for (var chunk : prepared.chunks()) {
                var actual = store.read(chunk.key(), timeout).orElseThrow();
                var document = prepared.documents().stream()
                        .filter(value -> value.metadata().key().equals(chunk.key().document())).findFirst().orElseThrow();
                if (!actual.chunk().equals(chunk) || !actual.document().equals(document.metadata())) {
                    throw new IllegalArgumentException("快照的条款或元数据与参考资料不一致");
                }
            }
            snapshots.put(corpus.corpusId(), snapshot);
        }
        String runId = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
                .format(Instant.now()) + "-" + UUID.randomUUID();
        var output = Path.of("examples/retrieval/real", runId);
        Files.createDirectories(output);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var rows = new ArrayList<QueryResult>();
        var report = mapper.createObjectNode();
        report.put("materialType", "ACTUAL_RETRIEVAL_EVALUATION");
        report.put("startedAt", Instant.now().toString());
        report.put("annotationStatus", labels.annotationStatus());
        report.put("annotationSource", labels.annotationSource());
        report.set("corpora", mapper.valueToTree(labels.corpora()));
        report.set("snapshots", mapper.valueToTree(snapshots));
        report.set("retrievalConfiguration", mapper.valueToTree(properties.retrieval()));
        report.set("timeouts", mapper.valueToTree(properties.timeouts()));
        report.put("embeddingModel", properties.siliconflow().embeddingModel());
        report.put("rerankModel", properties.siliconflow().rerankModel());
        report.put("javaVersion", System.getProperty("java.version"));
        var git = new ProcessBuilder("git", "rev-parse", "HEAD").start();
        String commit = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (git.waitFor() != 0) throw new IllegalStateException("无法记录实际 Git commit");
        report.put("commit", commit);
        report.set("queries", mapper.valueToTree(rows));
        report.put("status", "RUNNING");
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("results.json").toFile(), report);
        for (var query : labels.queries()) {
            var snapshot = snapshots.get(query.corpusId());
            long started = System.nanoTime();
            try {
                long keywordStarted = System.nanoTime();
                var keyword = store.searchKeyword(snapshot.snapshotId(), tokenizer.tokenize(query.query()),
                        properties.retrieval().routeLimit(), timeout);
                long keywordMillis = Duration.ofNanos(System.nanoTime() - keywordStarted).toMillis();
                long vectorStarted = System.nanoTime();
                var batch = embedding.embed(List.of(query.query()), Duration.ofMillis(properties.timeouts().embeddingMs()));
                var vector = store.searchVector(snapshot.snapshotId(), batch.vectors().get(0),
                        properties.retrieval().routeLimit(), timeout);
                long vectorMillis = Duration.ofNanos(System.nanoTime() - vectorStarted).toMillis();
                var rankings = compare(query.query(), snapshot, vector, keyword, store, rerank, properties);
                var measured = rankings.stream().map(ranking -> new SchemeResult(ranking,
                        metrics(query, snapshot.snapshotId(), ranking))).toList();
                rows.add(new QueryResult(query.queryId(), query.corpusId(), query.query(), snapshot.snapshotId(),
                        query.relevantClauses(), query.conflictPairs(), keyword, vector, batch.responseId(), batch.usage(),
                        keywordMillis, vectorMillis, Duration.ofNanos(System.nanoTime() - started).toMillis(), measured, null));
            } catch (DependencyException failure) {
                rows.add(new QueryResult(query.queryId(), query.corpusId(), query.query(), snapshot.snapshotId(),
                        query.relevantClauses(), query.conflictPairs(), List.of(), List.of(), null, null,
                        null, null, Duration.ofNanos(System.nanoTime() - started).toMillis(), List.of(), failure.failure()));
            }
            report.set("queries", mapper.valueToTree(rows));
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("results.json").toFile(), report);
        }
        var aggregates = new LinkedHashMap<String, Map<String, Double>>();
        for (String scheme : List.of("KEYWORD", "VECTOR", "HYBRID_RRF", "HYBRID_RERANK")) {
            var measured = rows.stream().flatMap(row -> row.schemes().stream())
                    .filter(result -> result.ranking().scheme().equals(scheme)).toList();
            var values = new LinkedHashMap<String, Double>();
            values.put("recallAt6", average(measured.stream().map(result -> result.metrics().recallAt6()).toList()));
            values.put("mrrAt6", average(measured.stream().map(result -> result.metrics().mrrAt6()).toList()));
            values.put("expandedRecall", average(measured.stream().map(result -> result.metrics().expandedRecall()).toList()));
            values.put("conflictPairRecall", average(measured.stream().map(result -> result.metrics().conflictPairRecall()).toList()));
            values.put("expandedConflictPairRecall", average(measured.stream()
                    .map(result -> result.metrics().expandedConflictPairRecall()).toList()));
            aggregates.put(scheme, values);
        }
        var reranked = rows.stream().flatMap(row -> row.schemes().stream())
                .map(SchemeResult::ranking).filter(rank -> rank.scheme().equals("HYBRID_RERANK")
                        && !rank.fusionPool().isEmpty()).toList();
        report.set("aggregates", mapper.valueToTree(aggregates));
        report.put("failedQueries", rows.stream().filter(row -> row.failure() != null).count());
        report.put("rerankEligibleCalls", reranked.size());
        report.put("rerankFallbackRate", reranked.isEmpty() ? null : reranked.stream()
                .filter(rank -> rank.rankingWarning() != null).count() / (double) reranked.size());
        boolean failed = rows.stream().anyMatch(row -> row.failure() != null);
        report.put("status", failed ? "COMPLETED_WITH_FAILURES" : "COMPLETED");
        report.put("finishedAt", Instant.now().toString());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("results.json").toFile(), report);
        Files.writeString(output.resolve("run.md"), "# 检索评估运行\n\n实际 commit：`" + commit
                + "`。输入、快照、原始召回、排名、计量、失败和汇总见 [results.json](results.json)。\n\n"
                + "入口：`mvn -Preal-evaluation -Dtest=RealRetrievalEvaluationTest -Devaluation.snapshots='" + supplied
                + "' test`；其他自定义 JVM／Maven 参数如有需另记。\n\n"
                + "标注状态：`" + labels.annotationStatus() + "`。本次仅比较检索，不运行 DeepSeek 或问答 Agent；"
                + "不证明动态动作调整或答案支持性。16 个问题来自 7／8 条款的小型合成资料，不能据此推断普遍收益。\n");
        if (failed) throw new AssertionError("存在必要检索失败，已保存结果；失败问题不进入成功排名均值，须结合失败计数阅读");
    }

    private static Double average(List<Double> values) {
        return values.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).average()
                .stream().boxed().findFirst().orElse(null);
    }

    record SchemeResult(Ranking ranking, Metrics metrics) {}
    record QueryResult(String queryId, String corpusId, String query, String snapshotId,
                       List<ClauseRef> relevantClauses, List<ConflictPair> conflictPairs,
                       List<RecallHit> keywordHits, List<RecallHit> vectorHits,
                       String embeddingResponseId, com.mango.backend.contract.model.ModelTypes.Usage embeddingUsage,
                       Long keywordMillis, Long vectorMillis, long elapsedMillis,
                       List<SchemeResult> schemes, Failure failure) {}

    @Configuration(proxyBeanMethods = false)
    @Profile("real-evaluation")
    @EnableAutoConfiguration
    @EnableConfigurationProperties({AgentProperties.class, DatabaseProperties.class})
    @MapperScan(basePackageClasses = KnowledgeMapper.class)
    @ComponentScan(basePackages = {"com.mango.backend.infra.pg", "com.mango.backend.infra.model",
            "com.mango.backend.knowledge.ingest"})
    static class Config {}
}
