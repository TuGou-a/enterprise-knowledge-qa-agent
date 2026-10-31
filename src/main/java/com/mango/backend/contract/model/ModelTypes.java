package com.mango.backend.contract.model;

import java.util.List;

public final class ModelTypes {
    private ModelTypes() {}

    /** 服务未返回计量时，整个 usage 为 null；缺少某项时对应字段为 null。 */
    public record Usage(Long inputTokens, Long totalTokens) {}
    /** vectors 按原始输入顺序排列；外部返回的 index 由 T02 完整校验并重排。 */
    public record EmbeddingBatch(List<DenseVector> vectors, String responseId, Usage usage) {
        public EmbeddingBatch {
            vectors = List.copyOf(vectors);
        }
    }
    public record RerankScore(int inputIndex, double relevanceScore) {}
    public record RerankBatch(List<RerankScore> scores, String responseId, Usage usage) {
        public RerankBatch {
            scores = List.copyOf(scores);
        }
    }
}
