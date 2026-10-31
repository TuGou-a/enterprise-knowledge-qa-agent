package com.mango.backend.contract.model;

/** 在模型响应边界一次性验证的不可变向量；内部调用方无需重复校验维度和有限值。 */
public final class DenseVector {
    public static final int DIMENSION = 1024;
    private final float[] values;

    public DenseVector(float[] values) {
        if (values.length != DIMENSION) {
            throw new IllegalArgumentException("Embedding 必须包含 1024 个分量");
        }
        this.values = values.clone();
        for (float value : this.values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Embedding 分量必须为有限数值");
            }
        }
    }

    public float[] values() {
        return values.clone();
    }

    @Override
    public String toString() {
        return "DenseVector[dimension=" + DIMENSION + "]";
    }
}
