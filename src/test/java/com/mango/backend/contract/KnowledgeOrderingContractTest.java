package com.mango.backend.contract;

import com.mango.backend.contract.knowledge.KnowledgeTypes.ChunkKey;
import com.mango.backend.contract.knowledge.KnowledgeTypes.DocumentKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.CHUNK_ORDER;
import static com.mango.backend.contract.knowledge.KnowledgeTypes.TEXT_ORDER;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeOrderingContractTest {
    @Test
    void ordersSupplementaryCharactersByUtf8BytesRatherThanUtf16CodeUnits() {
        // U+10000 的 UTF-16 代理项排在 U+E000 前面，但 UTF-8 字节序相反。
        var values = new ArrayList<>(List.of("\uD800\uDC00", "\uE000", "中", "a", "A"));
        values.sort(TEXT_ORDER);
        assertThat(values).containsExactly("A", "a", "中", "\uE000", "\uD800\uDC00");
    }

    @Test
    void ordersChunkIdentitiesBySnapshotDocumentVersionThenChunk() {
        var first = new ChunkKey(new DocumentKey("S1", "D1", "v1"), "C1");
        var nextChunk = new ChunkKey(new DocumentKey("S1", "D1", "v1"), "C2");
        var nextVersion = new ChunkKey(new DocumentKey("S1", "D1", "v2"), "C0");
        var nextDocument = new ChunkKey(new DocumentKey("S1", "D2", "v0"), "C0");
        var nextSnapshot = new ChunkKey(new DocumentKey("S2", "D0", "v0"), "C0");
        var values = new ArrayList<>(List.of(nextSnapshot, nextDocument, nextVersion, nextChunk, first));
        values.sort(CHUNK_ORDER);
        assertThat(values).containsExactly(first, nextChunk, nextVersion, nextDocument, nextSnapshot);
    }
}
