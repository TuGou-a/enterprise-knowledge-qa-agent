package com.mango.backend.infra.pg;

import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.model.DenseVector;
import com.mango.backend.knowledge.T05TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PgKnowledgeStoreBoundaryTest {
    @Test
    void insufficientBudgetDoesNotStartTransactionOrQuery() {
        var mapper = mock(KnowledgeMapper.class);
        var transactions = mock(PlatformTransactionManager.class);
        var dataSource = mock(DataSource.class);
        var store = new PgKnowledgeStore(mapper, transactions, dataSource, T05TestFixtures.properties());
        var values = new float[1024];
        values[0] = 1;
        var failure = assertThrows(DependencyException.class, () -> store.searchVector("snapshot",
                new DenseVector(values), 6, Duration.ofMillis(999)));
        assertThat(failure.failure().code()).isEqualTo(Failure.Code.BUDGET_EXHAUSTED);
        verifyNoInteractions(mapper, transactions, dataSource);
    }

    @Test
    void emptyKeywordQueryDoesNotRequireDatabaseAndCrossSnapshotKeysAreRejected() {
        var mapper = mock(KnowledgeMapper.class);
        var transactions = mock(PlatformTransactionManager.class);
        var dataSource = mock(DataSource.class);
        var store = new PgKnowledgeStore(mapper, transactions, dataSource, T05TestFixtures.properties());
        assertThat(store.searchKeyword("snapshot", List.of(), 6, Duration.ofSeconds(5))).isEmpty();
        var wrongKey = new ChunkKey(new DocumentKey("another-snapshot", "D01", "v1"), "clause-1");
        assertThat(assertThrows(DependencyException.class, () -> store.expandTopics("snapshot",
                List.of("期限"), List.of(wrongKey), 12, Duration.ofSeconds(5))).failure().code())
                .isEqualTo(Failure.Code.INVALID_ARGUMENT);
        verifyNoInteractions(mapper, transactions, dataSource);
    }
}
