package com.mango.backend.contract.qa;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum TaskStatus {
    ANSWERED, NEEDS_CLARIFICATION, CONFLICT, NO_EVIDENCE, INCOMPLETE;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static TaskStatus fromJson(String value) {
        return valueOf(value);
    }
}
