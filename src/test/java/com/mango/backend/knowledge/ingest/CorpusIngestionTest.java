package com.mango.backend.knowledge.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.knowledge.KnowledgeStore;
import com.mango.backend.contract.knowledge.KnowledgeTokenizer;
import com.mango.backend.contract.model.EmbeddingClient;
import com.mango.backend.contract.model.ModelTypes.EmbeddingBatch;
import com.mango.backend.knowledge.T05TestFixtures;
import com.mango.backend.evaluation.RetrievalEvaluation;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CorpusIngestionTest {
    @TempDir
    Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void referenceAnnotationsPointToExistingClausesAndStableCorpusHashes() throws Exception {
        var labels = RetrievalEvaluation.load(Path.of("examples/retrieval-labels.json"));
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var reader = new CorpusSourceReader(mapper, validation.getValidator(), new ClauseSplitter(),
                    T05TestFixtures.properties());
            for (var corpus : labels.corpora()) {
                var prepared = reader.read(Path.of(corpus.path()), "annotation-check");
                assertThat(prepared.contentHash()).isEqualTo(corpus.contentHash());
                var keys = prepared.chunks().stream().map(KnowledgeChunk::key).toList();
                for (var query : labels.queries()) {
                    if (query.corpusId().equals(corpus.corpusId())) {
                        assertThat(query.relevantClauses()).allSatisfy(ref ->
                                assertThat(keys).contains(ref.bind("annotation-check")));
                    }
                }
            }
        }
        // 仅核对标注身份与条款存在性，不能据此宣称参考答案已通过人工语义复核。
    }

    @Test
    void readsAllCorporaWithStableHashesAndSnapshotSpecificKeys() throws Exception {
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var reader = new CorpusSourceReader(mapper, validation.getValidator(), new ClauseSplitter(),
                    T05TestFixtures.properties());
            var normal = reader.read(Path.of("data/corpus/normal.json"), "first");
            var reimported = reader.read(Path.of("data/corpus/normal.json"), "second");
            var conflict = reader.read(Path.of("data/corpus/conflict.json"), "conflict");
            var mutation = reader.read(Path.of("data/corpus/mutation.json"), "mutation");
            assertThat(normal.chunks()).hasSize(7);
            assertThat(conflict.chunks()).hasSize(8);
            assertThat(mutation.chunks()).hasSize(7);
            assertThat(normal.contentHash()).isEqualTo(reimported.contentHash())
                    .isNotEqualTo(conflict.contentHash()).isNotEqualTo(mutation.contentHash());
            assertThat(reimported.chunks()).allSatisfy(chunk ->
                    assertThat(chunk.key().document().snapshotId()).isEqualTo("second"));
            for (var chunk : normal.chunks()) {
                var document = normal.documents().stream()
                        .filter(value -> value.metadata().key().equals(chunk.key().document())).findFirst().orElseThrow();
                assertThat(document.content().substring(chunk.startOffset(), chunk.endOffset())).isEqualTo(chunk.content());
                assertThat(chunk.contentHash()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(chunk.content().getBytes(StandardCharsets.UTF_8))));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "number", "missing", "date", "duplicateDocument", "topic", "supersedes", "trailing", "duplicateField"})
    void rejectsUntrustedCorpusBeforeAnyDatabaseWrite(String defect) throws Exception {
        var root = (ObjectNode) mapper.readTree(Path.of("data/corpus/normal.json").toFile());
        var document = (ObjectNode) root.withArray("documents").get(0);
        switch (defect) {
            case "unknown" -> document.put("extra", "no");
            case "number" -> document.put("documentId", 42);
            case "missing" -> document.remove("supersedes");
            case "date" -> document.put("versionDate", "2026-02-30");
            case "duplicateDocument" -> root.withArray("documents").add(document.deepCopy());
            case "topic" -> document.withObject("clauseTopics").put("99", "不存在");
            case "supersedes" -> document.withArray("supersedes").addObject()
                    .put("documentId", "MISSING").put("version", "v1");
            case "trailing", "duplicateField" -> { }
            default -> throw new AssertionError(defect);
        }
        String content = mapper.writeValueAsString(root);
        if (defect.equals("trailing")) content += " {}";
        if (defect.equals("duplicateField")) content = "{\"documents\":[],\"documents\":[]}";
        var path = temporary.resolve("invalid.json");
        Files.writeString(path, content);
        var store = mock(KnowledgeStore.class);
        var embedding = mock(EmbeddingClient.class);
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var reader = new CorpusSourceReader(mapper, validation.getValidator(), new ClauseSplitter(),
                    T05TestFixtures.properties());
            var importer = new CorpusImporter(reader, mock(KnowledgeTokenizer.class), store,
                    embedding, T05TestFixtures.properties());
            var failure = assertThrows(CorpusImporter.ImportException.class,
                    () -> importer.importCorpus(path, Duration.ofSeconds(30)));
            assertThat(failure.failure().code()).isEqualTo(Failure.Code.INVALID_ARGUMENT);
            assertThat(failure.snapshotId()).isNull();
            assertThat(failure.publicationAttempted()).isFalse();
            verifyNoInteractions(store, embedding);
            if (defect.equals("duplicateField")) {
                assertThat(failure.failure().message()).contains("行", "列");
            }
        }
    }

    @Test
    void preservesUtf16OffsetsAndRejectsMalformedClauseBoundaries() {
        var key = new DocumentKey("snapshot", "D", "v1");
        String source = "标题😀\r\n\r\n第1条 甲😀。\r\n\r\n第2条 乙。\r\n";
        var splitter = new ClauseSplitter();
        var chunks = splitter.split(key, "标题😀", source, java.util.Map.of("1", "甲", "2", "乙"), 100);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).startOffset()).isEqualTo(source.indexOf("第1条"));
        assertThat(chunks.get(0).endOffset()).isEqualTo(source.indexOf("第2条"));
        assertThat(chunks.get(1).endOffset()).isEqualTo(source.length());
        assertThat(chunks).allSatisfy(chunk -> assertThat(source.substring(chunk.startOffset(), chunk.endOffset()))
                .isEqualTo(chunk.content()));
        for (String invalid : List.of("第2条 跳号", "第1条甲", "说明\n第1条 正文", "第1条 \n")) {
            assertThrows(IllegalArgumentException.class,
                    () -> splitter.split(key, "标题", invalid, java.util.Map.of("1", "甲"), 100));
        }
        assertThrows(IllegalArgumentException.class,
                () -> splitter.split(key, "标题😀", source, java.util.Map.of("1", "甲", "2", "乙"), 5));
    }

    @Test
    void embeddingFailureKeepsBuildingSnapshotAndNeverPublishesOrRetries() {
        var store = mock(KnowledgeStore.class);
        var embedding = mock(EmbeddingClient.class);
        when(embedding.embed(anyList(), any())).thenThrow(new DependencyException(
                new Failure(Failure.Code.TIMEOUT, "受控超时", true)));
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var properties = T05TestFixtures.properties();
            var reader = new CorpusSourceReader(mapper, validation.getValidator(), new ClauseSplitter(), properties);
            var importer = new CorpusImporter(reader, text -> List.of("报销"), store, embedding, properties);
            var failure = assertThrows(CorpusImporter.ImportException.class,
                    () -> importer.importCorpus(Path.of("data/corpus/normal.json"), Duration.ofSeconds(30)));
            assertThat(failure.failure().code()).isEqualTo(Failure.Code.TIMEOUT);
            assertThat(failure.snapshotId()).isNotBlank();
            assertThat(failure.publicationAttempted()).isFalse();
            verify(store).createSnapshot(argThat(snapshot -> snapshot.state() == SnapshotState.BUILDING
                    && snapshot.snapshotId().equals(failure.snapshotId())), any());
            verify(embedding, times(1)).embed(anyList(), any());
            verify(store, never()).insertChunks(anyList(), any());
            verify(store, never()).publishSnapshot(anyString(), any(), any());
        }
    }

    @Test
    void importsWholeBatchesBeforePublishingManifestAndUsesFreshIds() {
        var store = mock(KnowledgeStore.class);
        var embedding = mock(EmbeddingClient.class);
        float[] vector = new float[1024];
        vector[0] = 1;
        var dense = new com.mango.backend.contract.model.DenseVector(vector);
        when(embedding.embed(anyList(), any())).thenAnswer(call -> {
            List<String> texts = call.getArgument(0);
            return new EmbeddingBatch(texts.stream().map(text -> dense).toList(), null, null);
        });
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var properties = T05TestFixtures.properties();
            var reader = new CorpusSourceReader(mapper, validation.getValidator(), new ClauseSplitter(), properties);
            var importer = new CorpusImporter(reader, text -> List.of("词"), store, embedding, properties);
            var first = importer.importCorpus(Path.of("data/corpus/normal.json"), Duration.ofSeconds(30));
            var order = inOrder(store, embedding);
            order.verify(store).createSnapshot(any(), any());
            order.verify(store).insertDocuments(argThat(docs -> docs.size() == 2), any());
            order.verify(embedding).embed(argThat(texts -> texts.size() == 7), any());
            order.verify(store).insertChunks(argThat(chunks -> chunks.size() == 7
                    && chunks.stream().allMatch(chunk -> chunk.tokens().equals(List.of("词")))), any());
            order.verify(store).publishSnapshot(eq(first.snapshotId()),
                    eq(new SnapshotManifest(2, 7, first.contentHash())), any());
            var second = importer.importCorpus(Path.of("data/corpus/normal.json"), Duration.ofSeconds(30));
            assertThat(second.snapshotId()).isNotEqualTo(first.snapshotId());
            assertThat(second.contentHash()).isEqualTo(first.contentHash());
        }
    }

    @Test
    void tokenizerKeepsNumbersAndNegationsAndRejectsUnreadableResources() {
        var properties = T05TestFixtures.properties();
        var tokenizer = new JiebaKnowledgeTokenizer(properties);
        var tokens = tokenizer.tokenize("不得报销 30 个自然日，ABC");
        assertThat(tokens).contains("30", "abc");
        assertThat(String.join("", tokens)).contains("不得");
        var invalidIndex = new com.mango.backend.contract.config.AgentProperties.Index(
                properties.index().preprocessingVersion(), properties.index().tokenizerVersion(),
                "missing-resource-v1", "none-v1", temporary.resolve("missing.dict").toString(), null);
        var invalid = new com.mango.backend.contract.config.AgentProperties(properties.deepseek(),
                properties.siliconflow(), properties.retrieval(), properties.limits(), properties.timeouts(), invalidIndex);
        assertThrows(IngestionConfigurationException.class, () -> new JiebaKnowledgeTokenizer(invalid));
    }
}
