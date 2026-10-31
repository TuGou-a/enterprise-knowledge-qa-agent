package com.mango.backend.contract.error;

/** 模型或数据库边界抛出；工具边界转换为结构化观察，不能转换为空结果。 */
public final class DependencyException extends RuntimeException {
    private final Failure failure;

    public DependencyException(Failure failure) {
        super(failure.message());
        this.failure = failure;
    }

    public DependencyException(Failure failure, Throwable cause) {
        super(failure.message(), cause);
        this.failure = failure;
    }

    public Failure failure() {
        return failure;
    }
}
