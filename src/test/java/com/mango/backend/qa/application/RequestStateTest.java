package com.mango.backend.qa.application;

import com.mango.backend.contract.error.Failure;
import com.mango.backend.knowledge.T05TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;
import static com.mango.backend.contract.search.SearchTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestStateTest {
    @Test
    void budgetsReserveAtomicallyAndOnlyExceededAttemptsSetLimitFlags() {
        var properties = T05TestFixtures.properties();
        var budget = new RequestExecutionBudget(properties);
        for (int index = 0; index < properties.limits().toolCalls(); index++) {
            assertThat(budget.tryAcquireToolCall()).isTrue();
        }
        assertThat(budget.snapshot().toolLimitExceeded()).isFalse();
        assertThat(budget.tryAcquireToolCall()).isFalse();
        assertThat(budget.snapshot().toolCalls()).isEqualTo(properties.limits().toolCalls());
        assertThat(budget.snapshot().toolLimitExceeded()).isTrue();
        assertThat(budget.tryAcquireText(100, 200)).isTrue();
        assertThat(budget.tryAcquireText(properties.limits().readCharacters(), 1)).isFalse();
        assertThat(budget.snapshot().readCharacters()).isEqualTo(100);
        assertThat(budget.snapshot().toolTextCharacters()).isEqualTo(200);
        assertThat(budget.snapshot().textLimitExceeded()).isTrue();
        budget.markIterationLimitExceeded();
        budget.markDeadlineExceeded();
        assertThat(budget.remaining()).isZero();
        assertThat(budget.snapshot().iterationLimitExceeded()).isTrue();
        assertThat(budget.snapshot().deadlineExceeded()).isTrue();
    }

    @Test
    void evidenceRegistryRejectsOtherSnapshotsAndDoesNotShareEntries() {
        var first = new RequestEvidenceRegistry("snapshot-1");
        var second = new RequestEvidenceRegistry("snapshot-1");
        var key = new ChunkKey(new DocumentKey("snapshot-1", "D01", "v1"), "clause-1");
        var evidence = T05TestFixtures.evidence(key, "期限", "30 天");
        first.register(evidence);
        assertThat(first.find(key)).contains(evidence);
        assertThat(second.entries()).isEmpty();
        assertThrows(UnsupportedOperationException.class, () -> first.entries().clear());
        var other = new ChunkKey(new DocumentKey("snapshot-2", "D01", "v1"), "clause-1");
        assertThrows(IllegalArgumentException.class, () -> first.register(T05TestFixtures.evidence(other, "期限", "20 天")));
    }

    @Test
    void onlySameQueryWithEnoughTopKClearsItsFailureAndTruncation() {
        var ledger = new RequestRetrievalLedger();
        var initial = new SearchScope("snapshot", "报销期限", 6);
        ledger.recordSearch(result(initial, true));
        ledger.recordSearch(result(new SearchScope("snapshot", "期限改写", 6), false));
        ledger.recordSearch(result(new SearchScope("snapshot", "报销期限", 3), false));
        assertThat(ledger.summary().unresolvedErrors()).hasSize(1);
        assertThat(ledger.summary().truncated()).isTrue();
        ledger.recordSearch(result(initial, false));
        assertThat(ledger.summary().unresolvedErrors()).isEmpty();
        assertThat(ledger.summary().truncated()).isFalse();
        assertThat(ledger.summary().degraded()).isTrue();
        var key = new ChunkKey(new DocumentKey("snapshot", "D01", "v1"), "clause-1");
        ledger.recordRead(new ReadProblem(key, new Failure(Failure.Code.TIMEOUT, "读取超时", true)));
        assertThat(ledger.summary().unresolvedReadErrors()).hasSize(1);
        ledger.resolveRead(key);
        assertThat(ledger.summary().unresolvedReadErrors()).isEmpty();
    }

    private static SearchResult result(SearchScope scope, boolean failed) {
        var errors = failed ? List.of(new RecallProblem(scope, RecallRoute.VECTOR,
                new Failure(Failure.Code.TIMEOUT, "受控故障", true))) : List.<RecallProblem>of();
        return new SearchResult(scope, List.of(), List.of(), List.of(),
                new Routes(failed ? RouteStatus.ERROR : RouteStatus.OK, RouteStatus.OK, RouteStatus.SKIPPED_EMPTY),
                RankingMode.NONE, List.of(), errors, failed, failed,
                new SearchMetrics(0, 0, 0, 0, 0, 0, 0, null, null, List.of()));
    }
}
