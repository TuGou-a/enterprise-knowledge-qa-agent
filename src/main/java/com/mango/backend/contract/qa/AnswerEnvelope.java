package com.mango.backend.contract.qa;

import com.mango.backend.contract.runtime.BudgetSnapshot;
import com.mango.backend.contract.search.SearchTypes.RetrievalSummary;

import java.util.List;

/** 经 T06 校验后，由 T07 使用真实运行信息组装；T08 外包 Result.success(envelope)。 */
public record AnswerEnvelope(String runId, String snapshotId, TaskStatus status, AnswerReason reason,
                             String answer, List<Citation> citations, List<String> evidenceGaps,
                             String clarifyingQuestion, RetrievalSummary retrieval,
                             BudgetSnapshot budget) {
    public AnswerEnvelope {
        citations = List.copyOf(citations);
        evidenceGaps = List.copyOf(evidenceGaps);
    }
    /** 偏移针对文档完整原文；quote 为该区间的原样子串，hash 为所读完整片段的哈希。 */
    public record Citation(String documentId, String version, String chunkId, String quote,
                           int startOffset, int endOffset, String contentHash) {}
}
