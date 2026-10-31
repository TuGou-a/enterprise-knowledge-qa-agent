package com.mango.backend.infra.pg;

import com.mango.backend.contract.knowledge.KnowledgeTypes.DocumentRef;
import com.mango.backend.contract.knowledge.KnowledgeTypes.SnapshotState;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Mapper 的扁平结果，公共领域对象由 PgKnowledgeStore 组装。 */
public final class PgRows {
    private PgRows() {}

    public record Snapshot(String snapshotId, SnapshotState state, String embeddingModel,
                           int embeddingDimension, String preprocessingVersion, String tokenizerVersion,
                           String dictionaryVersion, String stopWordsVersion, String contentHash,
                           Instant createdAt, Instant publishedAt) {}

    public record DocumentHash(String documentId, String version, String contentHash) {}

    public record Chunk(String snapshotId, String documentId, String version, String title,
                        LocalDate versionDate, String applicableScope, List<DocumentRef> supersedes,
                        String documentHash, String chunkId, String topicKey, String content,
                        int startOffset, int endOffset, String contentHash, double rawScore) {}
}
