package com.mango.backend.infra.pg;

import com.mango.backend.contract.knowledge.KnowledgeTypes.ChunkKey;
import com.mango.backend.contract.knowledge.KnowledgeTypes.CorpusSnapshot;
import com.mango.backend.contract.knowledge.KnowledgeTypes.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KnowledgeMapper {
    PgRows.Snapshot latestReadySnapshot();
    PgRows.Snapshot findSnapshot(@Param("snapshotId") String snapshotId);
    PgRows.Snapshot lockSnapshot(@Param("snapshotId") String snapshotId);
    void createSnapshot(@Param("snapshot") CorpusSnapshot snapshot);
    void insertDocuments(@Param("documents") List<KnowledgeDocument> documents);
    void insertChunks(@Param("chunks") List<ChunkInsert> chunks);
    List<PgRows.DocumentHash> documentHashes(@Param("snapshotId") String snapshotId);
    long countChunks(@Param("snapshotId") String snapshotId);
    int publishSnapshot(@Param("snapshotId") String snapshotId, @Param("contentHash") String contentHash);
    List<PgRows.Chunk> searchVector(@Param("snapshotId") String snapshotId,
                                  @Param("vector") String vector, @Param("limit") int limit);
    List<PgRows.Chunk> searchKeyword(@Param("snapshotId") String snapshotId,
                                   @Param("tokens") List<String> tokens, @Param("limit") int limit);
    List<PgRows.Chunk> expandTopics(@Param("snapshotId") String snapshotId,
                                  @Param("topics") List<String> topics,
                                  @Param("excluded") List<ChunkKey> excluded, @Param("limit") long limit);
    List<PgRows.Chunk> loadChunks(@Param("snapshotId") String snapshotId, @Param("keys") List<ChunkKey> keys);

    record ChunkInsert(com.mango.backend.contract.knowledge.KnowledgeTypes.KnowledgeChunk chunk,
                       String vector, String tokenText) {}
}
