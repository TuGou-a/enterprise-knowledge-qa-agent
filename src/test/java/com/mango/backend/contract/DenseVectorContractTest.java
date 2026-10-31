package com.mango.backend.contract;

import com.mango.backend.contract.model.DenseVector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DenseVectorContractTest {
    @Test
    void preservesAllComponentsAndDoesNotExposeItsBackingArray() {
        var source = new float[1024];
        source[0] = -0.5f;
        source[1023] = 0.25f;
        var vector = new DenseVector(source);

        source[0] = 99;
        var returned = vector.values();
        returned[1023] = 99;

        assertThat(vector.values()).hasSize(1024);
        assertThat(vector.values()[0]).isEqualTo(-0.5f);
        assertThat(vector.values()[1023]).isEqualTo(0.25f);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1023, 1025})
    void rejectsDimensionsThatCannotBeStoredInVector1024(int dimension) {
        assertThatIllegalArgumentException().isThrownBy(() -> new DenseVector(new float[dimension]));
    }

    @ParameterizedTest
    @MethodSource("nonFiniteComponents")
    void rejectsNonFiniteComponentsAtTheResponseBoundary(float component) {
        var values = new float[1024];
        values[1023] = component;
        assertThatIllegalArgumentException().isThrownBy(() -> new DenseVector(values));
    }

    static Stream<Float> nonFiniteComponents() {
        return Stream.of(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY);
    }
}
