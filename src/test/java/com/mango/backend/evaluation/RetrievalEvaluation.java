package com.mango.backend.evaluation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.knowledge.KnowledgeStore;
import com.mango.backend.contract.model.ModelTypes.Usage;
import com.mango.backend.contract.model.RerankClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;

/** 测试侧评估工具；参考标注、确定性指标与真实运行分别记录，不产生问答或验收通过结论。 */
public final class RetrievalEvaluation {
    private RetrievalEvaluation() {}

    public enum Scheme { KEYWORD, VECTOR, HYBRID_RRF, HYBRID_RERANK }
    public enum Mode { KEYWORD, VECTOR, RRF, RERANK, RRF_FALLBACK, NONE }

    public record ClauseRef(String documentId, String version, String chunkId) {
        public ChunkKey bind(String snapshotId) {
            return new ChunkKey(new DocumentKey(snapshotId, documentId, version), chunkId);
        }
    }
    public record ConflictPair(ClauseRef left, ClauseRef right) {}
    public record Corpus(String corpusId, String path, String sourceSha256, String contentHash) {}
    public record Query(String queryId, String corpusId, String query, List<ClauseRef> relevantClauses,
                        List<ConflictPair> conflictPairs, String expectedAnswer) {}
    public record Labels(String materialType, String annotationStatus, String annotationSource,
                         String relevanceScale, List<Corpus> corpora, List<Query> queries) {}
    public record Metrics(Double recallAt6, Double mrrAt6, Double expandedRecall, int returnedCount,
                          Double conflictPairRecall, Double expandedConflictPairRecall) {}
    public record Ranking(Scheme scheme, List<ChunkKey> primary, List<ChunkKey> expanded,
                          boolean truncated, Mode rankingMode, Failure rankingWarning,
                          Long rerankMillis, String rerankResponseId, Usage rerankUsage,
                          List<ChunkKey> fusionPool) {}

    public static Labels load(Path path) throws Exception {
        var mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        var labels = mapper.readValue(path.toFile(), Labels.class);
        if (labels == null || !"REFERENCE_ANNOTATIONS".equals(labels.materialType())
                || labels.annotationStatus() == null
                || !Set.of("REFERENCE_REVIEW_PENDING", "HUMAN_REVIEWED").contains(labels.annotationStatus())
                || labels.annotationSource() == null || labels.annotationSource().isBlank()
                || !"BINARY".equals(labels.relevanceScale()) || labels.corpora() == null
                || labels.corpora().isEmpty() || labels.queries() == null || labels.queries().isEmpty()) {
            throw new IllegalArgumentException("需要完整参考标注及明确的来源和复核状态，不能评估空模板");
        }
        var corpora = new HashSet<String>();
        for (var corpus : labels.corpora()) {
            if (corpus == null || corpus.corpusId() == null || corpus.corpusId().isBlank()
                    || !corpora.add(corpus.corpusId()) || corpus.path() == null || corpus.path().isBlank()
                    || !Files.isRegularFile(Path.of(corpus.path()))
                    || corpus.sourceSha256() == null || !corpus.sourceSha256().matches("[0-9a-f]{64}")
                    || corpus.contentHash() == null || !corpus.contentHash().matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("资料清单无效或重复");
            }
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(Path.of(corpus.path()))));
            if (!hash.equals(corpus.sourceSha256())) {
                throw new IllegalArgumentException("标注对应的源文件已变化：" + corpus.corpusId());
            }
        }
        var queries = new HashSet<String>();
        for (var query : labels.queries()) {
            if (query == null || query.queryId() == null || query.queryId().isBlank()
                    || !queries.add(query.queryId()) || !corpora.contains(query.corpusId())
                    || query.query() == null || query.query().isBlank()
                    || query.relevantClauses() == null || query.conflictPairs() == null
                    || query.expectedAnswer() == null || query.expectedAnswer().isBlank()) {
                throw new IllegalArgumentException("问题缺少标注、参考答案或资料身份，null 不代表资料外问题");
            }
            var refs = new HashSet<ClauseRef>();
            for (var ref : query.relevantClauses()) {
                if (ref == null || ref.documentId() == null || ref.documentId().isBlank()
                        || ref.version() == null || ref.version().isBlank() || ref.chunkId() == null
                        || ref.chunkId().isBlank() || !refs.add(ref)) {
                    throw new IllegalArgumentException("相关条款键无效或重复：" + query.queryId());
                }
            }
            for (var pair : query.conflictPairs()) {
                if (pair == null || !refs.contains(pair.left()) || !refs.contains(pair.right())
                        || pair.left().equals(pair.right())) {
                    throw new IllegalArgumentException("冲突双方必须是两个不同的已标注相关条款");
                }
            }
        }
        return labels;
    }

    public static Metrics metrics(Query query, String snapshotId, Ranking ranking) {
        var relevant = new HashSet<ChunkKey>();
        query.relevantClauses().forEach(ref -> relevant.add(ref.bind(snapshotId)));
        var primary = ranking.primary().stream().distinct().limit(6).toList();
        var expanded = ranking.expanded() == null ? null : new HashSet<>(ranking.expanded());
        Double recall = relevant.isEmpty() ? null
                : primary.stream().filter(relevant::contains).count() / (double) relevant.size();
        Double reciprocal = null;
        if (!relevant.isEmpty()) {
            reciprocal = 0.0;
            for (int index = 0; index < primary.size(); index++) {
                if (relevant.contains(primary.get(index))) {
                    reciprocal = 1.0 / (index + 1);
                    break;
                }
            }
        }
        Double expandedRecall = relevant.isEmpty() || expanded == null ? null
                : expanded.stream().filter(relevant::contains).count() / (double) relevant.size();
        Double pairs = null;
        Double expandedPairs = null;
        if (!query.conflictPairs().isEmpty()) {
            pairs = query.conflictPairs().stream().filter(pair -> primary.contains(pair.left().bind(snapshotId))
                    && primary.contains(pair.right().bind(snapshotId))).count() / (double) query.conflictPairs().size();
            if (expanded != null) {
                expandedPairs = query.conflictPairs().stream().filter(pair -> expanded.contains(pair.left().bind(snapshotId))
                        && expanded.contains(pair.right().bind(snapshotId))).count() / (double) query.conflictPairs().size();
            }
        }
        return new Metrics(recall, reciprocal, expandedRecall, expanded == null ? primary.size() : expanded.size(),
                pairs, expandedPairs);
    }

    /** 两路召回在调用方各执行一次；排序方案共用原始召回和同一个融合池。 */
    public static List<Ranking> compare(String query, CorpusSnapshot snapshot, List<RecallHit> vector,
                                        List<RecallHit> keyword, KnowledgeStore store, RerankClient rerank,
                                        AgentProperties properties) {
        var config = properties.retrieval();
        var timeout = Duration.ofMillis(properties.timeouts().databaseMs());
        var scores = new HashMap<ChunkKey, Double>();
        var previews = new HashMap<ChunkKey, ChunkPreview>();
        for (var route : List.of(vector, keyword)) {
            for (var hit : route) {
                var key = hit.candidate().key();
                previews.put(key, hit.candidate());
                scores.merge(key, 1.0 / (config.rrfK() + (double) hit.rank()), Double::sum);
            }
        }
        var pool = scores.keySet().stream().sorted(Comparator
                .<ChunkKey>comparingDouble(scores::get).reversed().thenComparing(CHUNK_ORDER))
                .limit(config.candidatePoolLimit()).toList();
        var rankings = new ArrayList<Ranking>();
        rankings.add(new Ranking(Scheme.KEYWORD, keyword.stream().map(hit -> hit.candidate().key())
                .limit(config.primaryLimit()).toList(), null, false, Mode.KEYWORD, null, null, null, null, List.of()));
        rankings.add(new Ranking(Scheme.VECTOR, vector.stream().map(hit -> hit.candidate().key())
                .limit(config.primaryLimit()).toList(), null, false, Mode.VECTOR, null, null, null, null, List.of()));
        var rrf = pool.stream().limit(config.primaryLimit()).toList();
        var ranked = rrf;
        Failure warning = null;
        Long elapsed = null;
        Usage usage = null;
        String responseId = null;
        var mode = pool.isEmpty() ? Mode.NONE : Mode.RERANK;
        if (!pool.isEmpty()) {
            long started = System.nanoTime();
            try {
                var chunks = store.loadChunks(snapshot.snapshotId(), pool, timeout);
                var result = rerank.rerank(query, chunks.stream().map(KnowledgeChunk::content).toList(),
                        pool.size(), Duration.ofMillis(properties.timeouts().rerankMs()));
                usage = result.usage();
                responseId = result.responseId();
                ranked = result.scores().stream().sorted(Comparator
                        .comparingDouble(com.mango.backend.contract.model.ModelTypes.RerankScore::relevanceScore)
                        .reversed().thenComparingInt(score -> score.inputIndex()))
                        .map(score -> pool.get(score.inputIndex())).limit(config.primaryLimit()).toList();
            } catch (DependencyException exception) {
                warning = exception.failure();
                mode = Mode.RRF_FALLBACK;
            } finally {
                elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
            }
        }
        var topics = pool.stream().map(key -> previews.get(key).topicKey()).distinct().sorted(TEXT_ORDER).toList();
        // 仅混合方案沿用 T05 的融合池主题种子；单路不发明额外扩展行为。
        for (boolean useRerank : List.of(false, true)) {
            var primary = useRerank ? ranked : rrf;
            var expansion = topics.isEmpty() ? new TopicExpansion(List.of(), false)
                    : store.expandTopics(snapshot.snapshotId(), topics, primary, config.relatedLimit(), timeout);
            var expanded = new LinkedHashSet<>(primary);
            expansion.candidates().forEach(candidate -> expanded.add(candidate.key()));
            rankings.add(new Ranking(useRerank ? Scheme.HYBRID_RERANK : Scheme.HYBRID_RRF, primary,
                    List.copyOf(expanded), expansion.truncated(), useRerank ? mode : pool.isEmpty() ? Mode.NONE : Mode.RRF,
                    useRerank ? warning : null, useRerank ? elapsed : null, useRerank ? responseId : null,
                    useRerank ? usage : null, pool));
        }
        return List.copyOf(rankings);
    }
}
