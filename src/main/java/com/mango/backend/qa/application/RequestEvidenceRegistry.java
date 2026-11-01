package com.mango.backend.qa.application;

import com.mango.backend.contract.runtime.EvidenceRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;

public final class RequestEvidenceRegistry implements EvidenceRegistry {
    private final String snapshotId;
    private final LinkedHashMap<ChunkKey, ReadEvidence> evidence = new LinkedHashMap<>();

    public RequestEvidenceRegistry(String snapshotId) {
        this.snapshotId = snapshotId;
    }

    @Override
    public synchronized void register(ReadEvidence value) {
        if (!snapshotId.equals(value.chunk().key().document().snapshotId())
                || !value.document().key().equals(value.chunk().key().document())) {
            throw new IllegalArgumentException("已读证据不属于本次资料快照或文档版本");
        }
        evidence.put(value.chunk().key(), value);
    }

    @Override
    public synchronized Optional<ReadEvidence> find(ChunkKey key) {
        return Optional.ofNullable(evidence.get(key));
    }

    @Override
    public synchronized List<ReadEvidence> entries() {
        return List.copyOf(evidence.values());
    }
}
