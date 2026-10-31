package com.mango.backend.contract.qa;

import java.util.List;

/** 仅由模型生成业务内容；缺字段或 null 列表由 T06 判 OUTPUT_INVALID，不自动补齐。 */
public record AnswerDraft(TaskStatus status, AnswerReason reason, String answer,
                          List<CitationDraft> citations, List<String> evidenceGaps,
                          String clarifyingQuestion) {
    public record CitationDraft(String documentId, String version, String chunkId, String quote) {}
}
