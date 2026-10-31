package com.mango.backend.contract;

import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.knowledge.KnowledgeTypes.*;
import com.mango.backend.contract.search.SearchTypes.ReadResult;
import com.mango.backend.contract.search.SearchTypes.ReadStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ReadResultContractTest {
    @Test
    void keepsReadSuccessAndDependencyFailureDistinguishable() {
        var document = new DocumentKey("fixture-snapshot", "D01", "v1");
        var content = "报销期限为30天。";
        // 本测试仅检查读取结果互斥；原文哈希及引用支持性由后续实现测试核对。
        var metadata = new DocumentMetadata(document, "合成制度", LocalDate.of(2026, 1, 1),
                null, List.of(), "fixture-document-hash");
        var chunk = new KnowledgeChunk(new ChunkKey(document, "C01"), "reimbursement-deadline",
                content, 0, content.length(), "fixture-chunk-hash");
        var evidence = new ReadEvidence(metadata, chunk);
        var error = new Failure(Failure.Code.DATABASE_UNAVAILABLE, "合成读取故障", true);

        assertThat(new ReadResult(ReadStatus.OK, evidence, null).evidence()).isSameAs(evidence);
        assertThat(new ReadResult(ReadStatus.ERROR, null, error).error()).isSameAs(error);

        assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(ReadStatus.OK, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(ReadStatus.OK, evidence, error));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(ReadStatus.OK, null, error));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(ReadStatus.ERROR, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(ReadStatus.ERROR, evidence, error));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(ReadStatus.ERROR, evidence, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(null, evidence, null));
    }
}
