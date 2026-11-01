package com.mango.backend.evaluation;

import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.qa.AnswerDraft;
import com.mango.backend.contract.qa.AnswerReason;
import com.mango.backend.contract.qa.TaskStatus;
import com.mango.backend.contract.runtime.*;
import com.mango.backend.knowledge.T05TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;
import static com.mango.backend.contract.search.SearchTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AnswerValidationTest {
    private final DefaultAnswerValidator validator = new DefaultAnswerValidator();
    private ExecutionBudget budget;
    private EvidenceRegistry evidence;
    private RetrievalLedger ledger;
    private RunContext context;

    @BeforeEach
    void setUp() {
        budget = mock(ExecutionBudget.class);
        evidence = mock(EvidenceRegistry.class);
        ledger = mock(RetrievalLedger.class);
        when(budget.snapshot()).thenReturn(new BudgetSnapshot(2, 2, 6, 12, 100, 1000, 10, 20,
                false, false, false, false));
        when(ledger.summary()).thenReturn(summary(List.of(), List.of(), false));
        context = new RunContext("run", T05TestFixtures.readySnapshot("snapshot-1"),
                budget, evidence, ledger, mock(TraceRecorder.class));
    }

    @Test
    void acceptsOnlyReadQuotesAndComputesUtf16DocumentOffsets() {
        var key = new ChunkKey(new DocumentKey("snapshot-1", "D01", "v1"), "clause-1");
        var source = T05TestFixtures.evidence(key, "期限", "😀30 天");
        source = new ReadEvidence(source.document(), new KnowledgeChunk(key, "期限", "😀30 天", 8, 14, "hash"));
        when(evidence.find(key)).thenReturn(Optional.of(source));
        var result = validator.validate(answer(List.of(citation("D01", "30 天"))), context);
        assertThat(result.status()).isEqualTo(TaskStatus.ANSWERED);
        assertThat(result.citations()).singleElement().satisfies(quote -> {
            assertThat(quote.startOffset()).isEqualTo(10);
            assertThat(quote.endOffset()).isEqualTo(14);
            assertThat(quote.contentHash()).isEqualTo("hash");
        });
    }

    @Test
    void oneInvalidCitationRejectsWholeDraftAndItsGaps() {
        register("D01", "30 天");
        var draft = new AnswerDraft(TaskStatus.ANSWERED, AnswerReason.EVIDENCE_SUFFICIENT, "30 天",
                List.of(citation("D01", "30 天"), citation("D02", "15 天")), List.of("模型自报缺口"), null);
        var result = validator.validate(draft, context);
        assertThat(result.reason()).isEqualTo(AnswerReason.CITATION_INVALID);
        assertThat(result.citations()).isEmpty();
        assertThat(result.evidenceGaps()).doesNotContain("模型自报缺口");
        register("D02", "十五天");
        assertThat(validator.validate(draft, context).reason()).isEqualTo(AnswerReason.CITATION_INVALID);
    }

    @ParameterizedTest
    @CsvSource({"true,true,true,true,DEADLINE_EXCEEDED", "false,true,true,true,BUDGET_EXHAUSTED",
            "false,false,true,true,MAX_ITERATIONS", "false,false,false,true,INPUT_LIMIT_EXCEEDED"})
    void runtimeBudgetFlagsOverrideInvalidOutputInPriorityOrder(boolean deadline, boolean tools,
                                                               boolean iterations, boolean text, AnswerReason expected) {
        when(budget.snapshot()).thenReturn(new BudgetSnapshot(6, 12, 6, 12, 100, 0, 0, 0,
                deadline, tools, iterations, text));
        var result = validator.validate(null, context);
        assertThat(result.status()).isEqualTo(TaskStatus.INCOMPLETE);
        assertThat(result.reason()).isEqualTo(expected);
        assertThat(result.clarifyingQuestion()).isNull();
    }

    @Test
    void exactBudgetCountsWithoutExceededFlagsDoNotInvalidateAnswer() {
        register("D01", "30 天");
        when(budget.snapshot()).thenReturn(new BudgetSnapshot(6, 12, 6, 12, 100, 1000, 0, 0,
                false, false, false, false));
        assertThat(validator.validate(answer(List.of(citation("D01", "30 天"))), context).status())
                .isEqualTo(TaskStatus.ANSWERED);
    }

    @Test
    void necessaryRouteReadAndTopicFailuresRemainVisibleAndRetainValidQuotes() {
        register("D01", "30 天");
        var scope = new SearchScope("snapshot-1", "期限", 6);
        var route = new RecallProblem(scope, RecallRoute.VECTOR, new Failure(Failure.Code.TIMEOUT, "secret", true));
        var key = new ChunkKey(new DocumentKey("snapshot-1", "D02", "v1"), "clause-1");
        var read = new ReadProblem(key, new Failure(Failure.Code.DATABASE_UNAVAILABLE, "secret", true));
        when(ledger.summary()).thenReturn(summary(List.of(route), List.of(read), true));
        var result = validator.validate(answer(List.of(citation("D01", "30 天"))), context);
        assertThat(result.reason()).isEqualTo(AnswerReason.ROUTE_FAILED);
        assertThat(result.citations()).hasSize(1);
        assertThat(String.join("", result.evidenceGaps())).contains("VECTOR", "D02/v1/clause-1", "同主题")
                .doesNotContain("secret");
        when(ledger.summary()).thenReturn(summary(List.of(), List.of(read), true));
        assertThat(validator.validate(answer(List.of(citation("D01", "30 天"))), context).reason())
                .isEqualTo(AnswerReason.READ_FAILED);
        when(ledger.summary()).thenReturn(summary(List.of(), List.of(), true));
        assertThat(validator.validate(answer(List.of(citation("D01", "30 天"))), context).reason())
                .isEqualTo(AnswerReason.TOPIC_TRUNCATED);
    }

    @Test
    void conflictRetainsBothSidesAndClarificationUnderIncompleteTopicChecks() {
        register("D01", "30 天");
        register("D02", "15 天");
        var draft = new AnswerDraft(TaskStatus.CONFLICT, AnswerReason.APPLICABILITY_UNKNOWN,
                "两份规定冲突", List.of(citation("D01", "30 天"), citation("D02", "15 天")),
                List.of("适用范围未知"), "应适用哪份制度？");
        when(ledger.summary()).thenReturn(summary(List.of(), List.of(), true));
        var result = validator.validate(draft, context);
        assertThat(result.status()).isEqualTo(TaskStatus.CONFLICT);
        assertThat(result.citations()).hasSize(2);
        assertThat(result.answer()).contains("30 天", "15 天", "不能选取");
        assertThat(result.clarifyingQuestion()).contains("适用", "制度归口部门");
        assertThat(String.join("", result.evidenceGaps())).contains("适用范围", "同主题资料尚未完整查证");
        var oneSide = new AnswerDraft(draft.status(), draft.reason(), draft.answer(),
                List.of(citation("D01", "30 天")), draft.evidenceGaps(), draft.clarifyingQuestion());
        assertThat(validator.validate(oneSide, context).reason()).isEqualTo(AnswerReason.CITATION_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "冲突未消解，建议按较严格的 15 个自然日时限把握，这只是风险提示，不构成制度结论。",
            "D02 日期较新，暂按 15 天办理。",
            "为避免超期，可先遵守较短期限。",
            "建议按 30 天执行。",
            "差旅制度没有规定报销期限。"
    })
    void conflictDisplaysVerifiedQuotesWithoutModelAdviceOrUnsupportedClaims(String modelAnswer) {
        String first = "第1条 报销申请应在费用发生之日起 30 个自然日内提交。";
        String second = "第1条 报销申请应在费用发生之日起 15 个自然日内提交。";
        register("D01", first);
        register("D02", second);
        var draft = new AnswerDraft(TaskStatus.CONFLICT, AnswerReason.APPLICABILITY_UNKNOWN,
                modelAnswer, List.of(citation("D01", first), citation("D02", second)),
                List.of("为稳妥起见按 15 天办理", "未读差旅制度没有期限"), "是否先按 15 天提交？");

        var result = validator.validate(draft, context);

        assertThat(result.status()).isEqualTo(TaskStatus.CONFLICT);
        assertThat(result.reason()).isEqualTo(AnswerReason.APPLICABILITY_UNKNOWN);
        assertThat(result.citations()).extracting(quote -> quote.quote()).containsExactly(first, second);
        assertThat(result.answer()).contains(first, second, "D01", "D02", "不能选取")
                .doesNotContain(modelAnswer, "建议", "暂按", "差旅");
        assertThat(String.join("", result.evidenceGaps())).contains("适用范围", "优先级", "替代关系")
                .doesNotContain("15 天办理", "差旅");
        assertThat(result.clarifyingQuestion()).contains("制度归口部门", "适用")
                .doesNotContain("15 天", "30 天");
    }

    @Test
    void noEvidenceRequiresBothRoutesToCompleteAndCannotHideToolFailure() {
        var draft = new AnswerDraft(TaskStatus.NO_EVIDENCE, AnswerReason.OUT_OF_CORPUS,
                "当前资料没有相关规则", List.of(), List.of("缺少期权制度"), null);
        assertThat(validator.validate(draft, context).status()).isEqualTo(TaskStatus.NO_EVIDENCE);
        when(ledger.summary()).thenReturn(new RetrievalSummary(false, false,
                new Routes(RouteStatus.NOT_RUN, RouteStatus.OK, RouteStatus.SKIPPED_EMPTY), RankingMode.NONE,
                List.of(), List.of(), List.of(), List.of()));
        assertThat(validator.validate(draft, context).reason()).isEqualTo(AnswerReason.OUTPUT_INVALID);
        var problem = new RecallProblem(new SearchScope("snapshot-1", "期权", 6), RecallRoute.VECTOR,
                new Failure(Failure.Code.TIMEOUT, "受控故障", true));
        when(ledger.summary()).thenReturn(summary(List.of(problem), List.of(), false));
        assertThat(validator.validate(draft, context).reason()).isEqualTo(AnswerReason.ROUTE_FAILED);
    }

    @Test
    void traceFactoryProducesIndependentImmutableEventSnapshots() {
        var factory = new InMemoryTraceRecorderFactory();
        var first = factory.create("first");
        var second = factory.create("second");
        first.record(new TraceEvent.ModelRound(1, TraceEvent.ModelPhase.STARTED, "开始", null));
        var captured = first.events();
        first.record(new TraceEvent.ModelRound(1, TraceEvent.ModelPhase.COMPLETED, "完成", null));
        assertThat(captured).hasSize(1);
        assertThat(first.events()).extracting(TraceEvent::sequence).containsExactly(1L, 2L);
        assertThat(first.events()).allSatisfy(event -> {
            assertThat(event.runId()).isEqualTo("first");
            assertThat(event.timestamp()).isNotNull();
        });
        assertThat(second.events()).isEmpty();
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, captured::clear);
    }

    private void register(String documentId, String text) {
        var key = new ChunkKey(new DocumentKey("snapshot-1", documentId, "v1"), "clause-1");
        when(evidence.find(key)).thenReturn(Optional.of(T05TestFixtures.evidence(key, "期限", text)));
    }

    private static AnswerDraft.CitationDraft citation(String document, String quote) {
        return new AnswerDraft.CitationDraft(document, "v1", "clause-1", quote);
    }

    private static AnswerDraft answer(List<AnswerDraft.CitationDraft> citations) {
        return new AnswerDraft(TaskStatus.ANSWERED, AnswerReason.EVIDENCE_SUFFICIENT, "按原文办理",
                citations, List.of(), null);
    }

    private static RetrievalSummary summary(List<RecallProblem> routes, List<ReadProblem> reads, boolean truncated) {
        return new RetrievalSummary(!routes.isEmpty(), truncated,
                new Routes(RouteStatus.OK, RouteStatus.OK, RouteStatus.OK), RankingMode.RERANK,
                List.of(), routes, reads, List.of());
    }
}
