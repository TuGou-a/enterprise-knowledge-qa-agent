package com.mango.backend.qa.application;

import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.knowledge.KnowledgeStore;
import com.mango.backend.contract.qa.*;
import com.mango.backend.evaluation.DefaultAnswerValidator;
import com.mango.backend.evaluation.RecordingTraceFactory;
import com.mango.backend.knowledge.T05TestFixtures;
import com.mango.backend.qa.agent.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentQaServiceBoundaryTest {
    @Test
    void missingSnapshotAndIndexMismatchFailBeforeAgentCreation() {
        var store = mock(KnowledgeStore.class);
        var agents = mock(QaAgentFactory.class);
        var traces = new RecordingTraceFactory();
        var service = new AgentQaService(store, T05TestFixtures.properties(), agents, new DefaultAnswerValidator(), traces);
        when(store.latestReadySnapshot(any())).thenReturn(Optional.empty());
        var request = new QaRequest("期限", List.of());
        assertThat(assertThrows(DependencyException.class, () -> service.answer(request).block(Duration.ofSeconds(5)))
                .failure().code()).isEqualTo(Failure.Code.SNAPSHOT_UNAVAILABLE);
        var source = T05TestFixtures.readySnapshot("snapshot-1");
        var incompatible = new CorpusSnapshot(source.snapshotId(), source.state(),
                new IndexProfile("another-model", 1024, "source-preserving-v1", "jieba-analysis-1.0.2", "builtin-v1", "none-v1"),
                source.contentHash(), source.createdAt(), source.publishedAt());
        when(store.latestReadySnapshot(any())).thenReturn(Optional.of(incompatible));
        assertThat(assertThrows(DependencyException.class, () -> service.answer(request).block(Duration.ofSeconds(5)))
                .failure().code()).isEqualTo(Failure.Code.INDEX_PROFILE_MISMATCH);
        verifyNoInteractions(agents);
    }

    @Test
    void eachSubscriptionGetsIndependentStateAndForcedDeadlineReturnsMatchingBudget() {
        var store = mock(KnowledgeStore.class);
        when(store.latestReadySnapshot(any())).thenReturn(Optional.of(T05TestFixtures.readySnapshot("old")),
                Optional.of(T05TestFixtures.readySnapshot("new")));
        var agents = mock(QaAgentFactory.class);
        var states = new ArrayList<AgentRunState>();
        when(agents.create(any())).thenAnswer(call -> {
            AgentRunState state = call.getArgument(0);
            states.add(state);
            state.budget().markDeadlineExceeded();
            throw new AgentExecutionStopped(AnswerReason.DEADLINE_EXCEEDED);
        });
        var service = new AgentQaService(store, T05TestFixtures.properties(), agents,
                new DefaultAnswerValidator(), new RecordingTraceFactory());
        var cold = service.answer(new QaRequest("期限", List.of()));
        var first = cold.block(Duration.ofSeconds(5));
        var second = cold.block(Duration.ofSeconds(5));
        assertThat(first.runId()).isNotEqualTo(second.runId());
        assertThat(first.snapshotId()).isEqualTo("old");
        assertThat(second.snapshotId()).isEqualTo("new");
        assertThat(states.get(0).context().evidence()).isNotSameAs(states.get(1).context().evidence());
        assertThat(states.get(0).context().retrieval()).isNotSameAs(states.get(1).context().retrieval());
        assertThat(first.reason()).isEqualTo(AnswerReason.DEADLINE_EXCEEDED);
        assertThat(first.budget().deadlineExceeded()).isTrue();
        assertThat(second.budget().deadlineExceeded()).isTrue();
    }
}
