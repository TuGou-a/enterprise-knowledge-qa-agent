package com.mango.backend.knowledge.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.knowledge.KnowledgeStore;
import com.mango.backend.evaluation.RetrievalEvaluation.Corpus;
import jakarta.validation.Validation;

import java.nio.file.Path;
import java.time.Duration;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;

/** 两种真实评估共用的测试侧绑定，集合哈希不能替代元数据和切分结果核对。 */
public final class CorpusEvaluationBinding {
    private CorpusEvaluationBinding() {}

    public static void verify(Corpus corpus, CorpusSnapshot snapshot, KnowledgeStore store,
                              AgentProperties properties) {
        var configured = new IndexProfile(properties.siliconflow().embeddingModel(), 1024,
                properties.index().preprocessingVersion(), properties.index().tokenizerVersion(),
                properties.index().dictionaryVersion(), properties.index().stopWordsVersion());
        if (snapshot.state() != SnapshotState.READY || !snapshot.contentHash().equals(corpus.contentHash())
                || !snapshot.indexProfile().equals(configured)) {
            throw new IllegalArgumentException("快照状态、资料摘要或索引配置与评估不一致：" + corpus.corpusId());
        }
        var timeout = Duration.ofMillis(properties.timeouts().databaseMs());
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var reader = new CorpusSourceReader(new ObjectMapper(), validation.getValidator(), new ClauseSplitter(), properties);
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
        }
    }
}
