package com.mango.backend.contract.knowledge;

import com.mango.backend.contract.model.DenseVector;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;

/** T03 实现。每个数据库操作接收本次剩余预算裁剪后的超时，失败抛 DependencyException。 */
public interface KnowledgeStore {
    Optional<CorpusSnapshot> latestReadySnapshot(Duration timeout);
    Optional<CorpusSnapshot> findSnapshot(String snapshotId, Duration timeout);
    void createSnapshot(CorpusSnapshot snapshot, Duration timeout);
    void insertDocuments(List<KnowledgeDocument> documents, Duration timeout);
    void insertChunks(List<IndexedChunk> chunks, Duration timeout);
    /** 短事务核对 BUILDING、清单与数量后原子发布；失败保持旧 READY 快照可用。 */
    CorpusSnapshot publishSnapshot(String snapshotId, SnapshotManifest manifest, Duration timeout);
    /** 距离升序后按 CHUNK_ORDER 对应的 SQL COLLATE "C" 稳定键排序，再 LIMIT。 */
    List<RecallHit> searchVector(String snapshotId, DenseVector vector, int limit, Duration timeout);
    /** 关键词得分降序后使用同一稳定键排序，再 LIMIT。 */
    List<RecallHit> searchKeyword(String snapshotId, List<String> tokens, int limit, Duration timeout);
    TopicExpansion expandTopics(String snapshotId, List<String> topicKeys, List<ChunkKey> excluded,
                                int limit, Duration timeout);
    /** 按请求键顺序返回完整条款，仅供检索内部排序，不登记已读证据；缺任一键则整批失败。 */
    List<KnowledgeChunk> loadChunks(String snapshotId, List<ChunkKey> keys, Duration timeout);
    Optional<ReadEvidence> read(ChunkKey key, Duration timeout);
}
