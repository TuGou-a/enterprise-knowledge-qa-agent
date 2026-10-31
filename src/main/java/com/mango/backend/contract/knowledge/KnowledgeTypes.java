package com.mango.backend.contract.knowledge;

import com.mango.backend.contract.model.DenseVector;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** 原文范围统一采用 Java UTF-16 的 [startOffset, endOffset)，哈希为 UTF-8 SHA-256 小写十六进制。 */
public final class KnowledgeTypes {
    private KnowledgeTypes() {}

    /** 与 PostgreSQL UTF-8 数据库的 COLLATE "C" 字节序一致。 */
    public static final Comparator<String> TEXT_ORDER = (left, right) -> Arrays.compareUnsigned(
            left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    public static final Comparator<ChunkKey> CHUNK_ORDER = Comparator
            .comparing((ChunkKey key) -> key.document().snapshotId(), TEXT_ORDER)
            .thenComparing(key -> key.document().documentId(), TEXT_ORDER)
            .thenComparing(key -> key.document().version(), TEXT_ORDER)
            .thenComparing(ChunkKey::chunkId, TEXT_ORDER);

    public enum SnapshotState { BUILDING, READY }
    public record DocumentRef(String documentId, String version) {}
    public record DocumentKey(String snapshotId, String documentId, String version) {}
    public record ChunkKey(DocumentKey document, String chunkId) {}

    public record IndexProfile(String embeddingModel, int embeddingDimension,
                               String preprocessingVersion, String tokenizerVersion,
                               String dictionaryVersion, String stopWordsVersion) {}

    public record CorpusSnapshot(String snapshotId, SnapshotState state, IndexProfile indexProfile,
                                 String contentHash, Instant createdAt, Instant publishedAt) {}
    public record SnapshotManifest(int documentCount, int chunkCount, String contentHash) {}

    public record DocumentMetadata(DocumentKey key, String title, LocalDate versionDate,
                                   String applicableScope, List<DocumentRef> supersedes,
                                   String contentHash) {
        public DocumentMetadata {
            supersedes = List.copyOf(supersedes);
        }
    }
    public record KnowledgeDocument(DocumentMetadata metadata, String content) {}
    public record KnowledgeChunk(ChunkKey key, String topicKey, String content,
                                 int startOffset, int endOffset, String contentHash) {}
    public record IndexedChunk(KnowledgeChunk chunk, DenseVector embedding, List<String> tokens) {
        public IndexedChunk {
            tokens = List.copyOf(tokens);
        }
    }
    public record ReadEvidence(DocumentMetadata document, KnowledgeChunk chunk) {}
    public record ChunkPreview(ChunkKey key, String title, LocalDate versionDate,
                               String applicableScope, String topicKey, String preview) {}
    public record RecallHit(ChunkPreview candidate, int rank, double rawScore) {}
    public record TopicExpansion(List<ChunkPreview> candidates, boolean truncated) {
        public TopicExpansion {
            candidates = List.copyOf(candidates);
        }
    }
}
