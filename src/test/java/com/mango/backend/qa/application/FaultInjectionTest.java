package com.mango.backend.qa.application;

import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.model.ModelTypes.EmbeddingBatch;
import com.mango.backend.contract.model.ModelTypes.RerankBatch;
import com.mango.backend.infra.model.SiliconFlowEmbeddingClient;
import com.mango.backend.infra.model.SiliconFlowRerankClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class FaultInjectionTest {
    @Test
    void embeddingFaultOccursExactlyOnceThenDelegatesWithoutRetry() {
        var delegate = mock(SiliconFlowEmbeddingClient.class);
        var expected = new EmbeddingBatch(List.of(), "controlled", null);
        when(delegate.embed(anyList(), any())).thenReturn(expected);
        var plan = new RealQaEvaluationTest.FaultPlan();
        plan.mode = RealQaEvaluationTest.FaultMode.EMBEDDING_ONCE;
        var wrapper = new RealQaEvaluationTest.Config().evaluationEmbedding(delegate, plan);
        var timeout = Duration.ofSeconds(1);
        assertThat(assertThrows(DependencyException.class, () -> wrapper.embed(List.of("期限"), timeout))
                .failure().code()).isEqualTo(Failure.Code.TIMEOUT);
        verifyNoInteractions(delegate);
        assertThat(wrapper.embed(List.of("期限"), timeout)).isSameAs(expected);
        verify(delegate, times(1)).embed(List.of("期限"), timeout);
        assertThat(plan.injected.get()).isEqualTo(1);
        assertThat(plan.embeddingCalls.get()).isEqualTo(2);
    }

    @Test
    void rerankFaultIsIndependentAndDefaultEmbeddingPassesThrough() {
        var plan = new RealQaEvaluationTest.FaultPlan();
        var config = new RealQaEvaluationTest.Config();
        var embedding = mock(SiliconFlowEmbeddingClient.class);
        when(embedding.embed(anyList(), any())).thenReturn(new EmbeddingBatch(List.of(), null, null));
        config.evaluationEmbedding(embedding, plan).embed(List.of("期限"), Duration.ofSeconds(1));
        assertThat(plan.injected.get()).isZero();
        plan.mode = RealQaEvaluationTest.FaultMode.RERANK_ONCE;
        var rerank = mock(SiliconFlowRerankClient.class);
        var expected = new RerankBatch(List.of(), "rank", null);
        when(rerank.rerank(anyString(), anyList(), anyInt(), any())).thenReturn(expected);
        var wrapper = config.evaluationRerank(rerank, plan);
        assertThrows(DependencyException.class,
                () -> wrapper.rerank("期限", List.of("原文"), 1, Duration.ofSeconds(1)));
        verifyNoInteractions(rerank);
        assertThat(wrapper.rerank("期限", List.of("原文"), 1, Duration.ofSeconds(1))).isSameAs(expected);
        assertThat(plan.injected.get()).isEqualTo(1);
        assertThat(plan.rerankCalls.get()).isEqualTo(2);
    }
}
