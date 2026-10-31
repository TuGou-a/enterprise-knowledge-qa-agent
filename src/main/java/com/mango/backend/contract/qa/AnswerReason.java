package com.mango.backend.contract.qa;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum AnswerReason {
    EVIDENCE_SUFFICIENT, CONTEXT_REQUIRED, APPLICABILITY_UNKNOWN, OUT_OF_CORPUS,
    ROUTE_FAILED, TOPIC_TRUNCATED, READ_FAILED, BUDGET_EXHAUSTED,
    MAX_ITERATIONS, INPUT_LIMIT_EXCEEDED, DEADLINE_EXCEEDED, MODEL_FAILED, OUTPUT_INVALID, CITATION_INVALID;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static AnswerReason fromJson(String value) {
        return valueOf(value);
    }
}
