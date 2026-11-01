package com.mango.backend.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.knowledge.KnowledgeStore;
import com.mango.backend.contract.model.ModelTypes.EmbeddingBatch;
import com.mango.backend.contract.model.ModelTypes.RerankBatch;
import com.mango.backend.contract.model.ModelTypes.RerankScore;
import com.mango.backend.contract.model.RerankClient;
import com.mango.backend.contract.runtime.*;
import com.mango.backend.knowledge.T05TestFixtures;
import com.mango.backend.knowledge.search.HybridSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;
import static com.mango.backend.contract.search.SearchTypes.*;
import static com.mango.backend.evaluation.RetrievalEvaluation.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetrievalEvaluationTest {
    @TempDir
    Path temporary;

    @Test
    void measuresRanksDeduplicationAndConflictPairsSeparatelyFromTopicExpansion() {
        var left = new ClauseRef("D01", "v1", "clause-1");
        var right = new ClauseRef("D02", "v1", "clause-1");
        var other = new ClauseRef("D03", "v1", "clause-1");
        var query = new Query("conflict", "conflict", "期限", List.of(left, right),
                List.of(new ConflictPair(left, right)), "两项冲突");
        var ranking = new Ranking(Scheme.HYBRID_RRF, List.of(other.bind("s"), left.bind("s"), left.bind("s")),
                List.of(other.bind("s"), left.bind("s"), right.bind("s")), false, Mode.RRF, null, null, null, null, List.of());
        var result = metrics(query, "s", ranking);
        assertThat(result.recallAt6()).isEqualTo(0.5);
        assertThat(result.mrrAt6()).isEqualTo(0.5);
        assertThat(result.expandedRecall()).isEqualTo(1.0);
        assertThat(result.returnedCount()).isEqualTo(3);
        assertThat(result.conflictPairRecall()).isZero();
        assertThat(result.expandedConflictPairRecall()).isEqualTo(1.0);
    }

    @Test
    void outOfCorpusAndAbsentConflictSetsAreNotZeroScoringPositiveExamples() {
        var query = new Query("outside", "normal", "期权", List.of(), List.of(), "资料缺口");
        var result = metrics(query, "s", new Ranking(Scheme.VECTOR, List.of(), null,
                false, Mode.VECTOR, null, null, null, null, List.of()));
        assertThat(result.recallAt6()).isNull();
        assertThat(result.mrrAt6()).isNull();
        assertThat(result.expandedRecall()).isNull();
        assertThat(result.conflictPairRecall()).isNull();
    }

    @Test
    void hitAtRankSevenIsNotIncludedInRecallOrMrrAtSix() {
        var reference = new ClauseRef("D7", "v1", "clause-1");
        var ranked = java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(number -> new ClauseRef("D" + number, "v1", "clause-1").bind("s")).toList();
        var result = metrics(new Query("q", "normal", "问题", List.of(reference), List.of(), "答案"), "s",
                new Ranking(Scheme.VECTOR, ranked, null, false, Mode.VECTOR, null, null, null, null, List.of()));
        assertThat(result.recallAt6()).isZero();
        assertThat(result.mrrAt6()).isZero();
    }

    @Test
    void annotationLoaderRejectsUnfilledTemplatesAndChangedSourceIdentity() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> load(Path.of("examples/retrieval-labels.template.json")));
        var mapper = new ObjectMapper();
        var root = mapper.readTree(Path.of("examples/retrieval-labels.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("corpora").get(0)).put("sourceSha256", "0".repeat(64));
        var path = temporary.resolve("changed.json");
        Files.writeString(path, mapper.writeValueAsString(root));
        assertThrows(IllegalArgumentException.class, () -> load(path));
        assertThat(load(Path.of("examples/retrieval-labels.json")).annotationStatus()).isEqualTo("REFERENCE_REVIEW_PENDING");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "directory"})
    void annotationLoaderRejectsBlankAndDirectoryPaths(String sourcePath) throws Exception {
        var mapper = new ObjectMapper();
        var root = mapper.readTree(Path.of("examples/retrieval-labels.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("corpora").get(0))
                .put("path", sourcePath.isEmpty() ? "" : temporary.toString());
        var labels = temporary.resolve("invalid-path.json");
        Files.writeString(labels, mapper.writeValueAsString(root));
        assertThrows(IllegalArgumentException.class, () -> load(labels));
    }

    @Test
    void comparisonKeepsProductionFusionRankingAndWholePoolFallback() {
        var store = mock(KnowledgeStore.class);
        var rerank = mock(RerankClient.class);
        var properties = T05TestFixtures.properties();
        var first = T05TestFixtures.preview("D01", "v1", "clause-1", "期限", "甲");
        var second = T05TestFixtures.preview("D02", "v1", "clause-1", "期限", "乙");
        var third = T05TestFixtures.preview("D03", "v1", "clause-1", "凭证", "丙");
        var vector = List.of(new RecallHit(first, 1, 0.9), new RecallHit(second, 2, 0.8));
        var keyword = List.of(new RecallHit(third, 1, 100), new RecallHit(first, 2, 10));
        when(store.searchVector(anyString(), any(), anyInt(), any())).thenReturn(vector);
        when(store.searchKeyword(anyString(), anyList(), anyInt(), any())).thenReturn(keyword);
        when(store.loadChunks(anyString(), anyList(), any())).thenAnswer(call -> {
            List<ChunkKey> keys = call.getArgument(1);
            return keys.stream().map(key -> T05TestFixtures.chunk(key, "期限", key.document().documentId())).toList();
        });
        when(store.expandTopics(anyString(), anyList(), anyList(), anyInt(), any()))
                .thenReturn(new TopicExpansion(List.of(), false));
        when(rerank.rerank(anyString(), anyList(), anyInt(), any())).thenReturn(new RerankBatch(
                List.of(new RerankScore(2, 0.9), new RerankScore(0, 0.5), new RerankScore(1, 0.5)), "rank", null));
        var snapshot = T05TestFixtures.readySnapshot("snapshot-1");
        var compared = compare("期限", snapshot, vector, keyword, store, rerank, properties);
        var values = new float[1024];
        values[0] = 1;
        var ledger = mock(RetrievalLedger.class);
        var budget = mock(ExecutionBudget.class);
        when(budget.remaining()).thenReturn(Duration.ofSeconds(120));
        var service = new HybridSearchService(store, text -> List.of("期限"),
                (texts, timeout) -> new EmbeddingBatch(List.of(new com.mango.backend.contract.model.DenseVector(values)), null, null),
                rerank, properties);
        try {
            var actual = service.search(new SearchRequest("期限", 6), new RunContext("run", snapshot, budget,
                    mock(EvidenceRegistry.class), ledger, mock(TraceRecorder.class)));
            assertThat(compared.get(3).primary()).containsExactlyElementsOf(actual.primaryCandidates().stream()
                    .map(candidate -> candidate.source().key()).toList());
            assertThat(compared.get(2).fusionPool()).containsExactlyElementsOf(actual.metrics().rerankInputKeys());
            assertThat(compared.get(3).rerankResponseId()).isEqualTo("rank");
            assertThat(compared.get(2).primary()).containsExactly(first.key(), third.key(), second.key());
            when(rerank.rerank(anyString(), anyList(), anyInt(), any())).thenThrow(new DependencyException(
                    new Failure(Failure.Code.TIMEOUT, "受控超时", true)));
            var fallback = compare("期限", snapshot, vector, keyword, store, rerank, properties).get(3);
            assertThat(fallback.primary()).isEqualTo(compared.get(2).primary());
            assertThat(fallback.rankingMode()).isEqualTo(Mode.RRF_FALLBACK);
            assertThat(fallback.rankingWarning().code()).isEqualTo(Failure.Code.TIMEOUT);
            assertThat(fallback.rerankResponseId()).isNull();
        } finally {
            service.dispose();
        }
    }
}
