package com.mango.backend.knowledge;

import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.knowledge.KnowledgeTypes.ChunkKey;
import com.mango.backend.contract.knowledge.KnowledgeTypes.ChunkPreview;
import com.mango.backend.contract.knowledge.KnowledgeTypes.CorpusSnapshot;
import com.mango.backend.contract.knowledge.KnowledgeTypes.DocumentKey;
import com.mango.backend.contract.knowledge.KnowledgeTypes.DocumentMetadata;
import com.mango.backend.contract.knowledge.KnowledgeTypes.IndexProfile;
import com.mango.backend.contract.knowledge.KnowledgeTypes.KnowledgeChunk;
import com.mango.backend.contract.knowledge.KnowledgeTypes.ReadEvidence;
import com.mango.backend.contract.knowledge.KnowledgeTypes.SnapshotState;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class T05TestFixtures {
    public static final String SNAPSHOT_ID = "snapshot-1";

    private T05TestFixtures() {}

    public static AgentProperties properties() {
        return new AgentProperties(
                new AgentProperties.DeepSeek("key", URI.create("https://api.deepseek.com"),
                        "deepseek", 32768, 2048),
                new AgentProperties.SiliconFlow("key", URI.create("https://api.siliconflow.cn/v1"),
                        "BAAI/bge-m3", "BAAI/bge-reranker-v2-m3"),
                new AgentProperties.Retrieval(20, 20, 6, 12, 60, 300),
                new AgentProperties.Limits(2000, 20, 4000, 12000, 512, 24000,
                        20000, 24000, 32, 4000, 64000, 6, 12),
                new AgentProperties.Timeouts(120000, 60000, 30000, 15000, 10000, 5000, 2000),
                new AgentProperties.Index("source-preserving-v1", "jieba-analysis-1.0.2",
                        "builtin-v1", "none-v1", null, null));
    }

    public static CorpusSnapshot readySnapshot(String snapshotId) {
        return new CorpusSnapshot(snapshotId, SnapshotState.READY,
                new IndexProfile("BAAI/bge-m3", 1024, "source-preserving-v1",
                        "jieba-analysis-1.0.2", "builtin-v1", "none-v1"),
                "content-hash", Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:01:00Z"));
    }

    public static ChunkPreview preview(String documentId, String version, String chunkId,
                                       String topicKey, String preview) {
        return new ChunkPreview(new ChunkKey(
                new DocumentKey(SNAPSHOT_ID, documentId, version), chunkId),
                "制度 " + documentId, LocalDate.of(2026, 1, 1), "全体", topicKey, preview);
    }

    public static KnowledgeChunk chunk(ChunkKey key, String topicKey, String content) {
        return new KnowledgeChunk(key, topicKey, content, 0, content.length(), "chunk-hash");
    }

    public static ReadEvidence evidence(ChunkKey key, String topicKey, String content) {
        var metadata = new DocumentMetadata(key.document(), "制度 " + key.document().documentId(),
                LocalDate.of(2026, 1, 1), "全体", List.of(), "document-hash");
        return new ReadEvidence(metadata, chunk(key, topicKey, content));
    }
}
