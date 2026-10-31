package com.mango.backend.contract.error;

/** 可向调用方和 Trace 暴露的错误；message 必须经过边界脱敏。 */
public record Failure(Code code, String message, boolean retryable) {
    public enum Code {
        TIMEOUT, RATE_LIMITED, AUTHENTICATION_FAILED, UPSTREAM_UNAVAILABLE,
        INVALID_RESPONSE, DATABASE_UNAVAILABLE, NOT_FOUND, INVALID_ARGUMENT,
        INPUT_LIMIT_EXCEEDED, SNAPSHOT_UNAVAILABLE, INDEX_PROFILE_MISMATCH,
        BUDGET_EXHAUSTED, INTERNAL_ERROR
    }
}
