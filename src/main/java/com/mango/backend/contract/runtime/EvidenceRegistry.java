package com.mango.backend.contract.runtime;

import java.util.List;
import java.util.Optional;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;

/** T07 创建请求独立实现；T05 登记、T06 查询，只容纳固定快照实际读取的证据。 */
public interface EvidenceRegistry {
    void register(ReadEvidence evidence);
    Optional<ReadEvidence> find(ChunkKey key);
    List<ReadEvidence> entries();
}
