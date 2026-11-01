package com.mango.backend.qa.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.knowledge.KnowledgeStore;
import com.mango.backend.contract.qa.*;
import com.mango.backend.contract.runtime.TraceEvent;
import com.mango.backend.contract.search.KnowledgeSearch;
import com.mango.backend.evaluation.*;
import com.mango.backend.knowledge.T05TestFixtures;
import com.mango.backend.knowledge.evidence.KnowledgeEvidenceReader;
import com.mango.backend.qa.agent.QaAgentFactory;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;
import static com.mango.backend.contract.search.SearchTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 实际 AgentQaService + AgentScope + 真实工具包装／读取／校验，只有模型和资料存储是替身。 */
class QaOfflineMockTest {
    enum Scenario { ANSWER, CONFLICT, OUTSIDE, RECOVERY, UNOBSERVED, TOOL_LIMIT, ITERATION_LIMIT, MODEL_FAILURE }

    @ParameterizedTest
    @EnumSource(Scenario.class)
    void runsActualServiceAndFrameworkWithoutKeysOrDatabase(Scenario scenario) throws Exception {
        var properties = properties(scenario);
        var store = mock(KnowledgeStore.class);
        when(store.latestReadySnapshot(any())).thenReturn(Optional.of(T05TestFixtures.readySnapshot("snapshot-1")));
        when(store.read(any(), any())).thenAnswer(call -> {
            ChunkKey key = call.getArgument(0);
            String text = key.document().documentId().equals("D02") ? "报销期限为 15 天。" : "报销期限为 30 天。";
            return Optional.of(T05TestFixtures.evidence(key, "报销期限", text));
        });
        var searches = new AtomicInteger();
        KnowledgeSearch search = (request, context) -> {
            int count = searches.incrementAndGet();
            var scope = new SearchScope(context.snapshot().snapshotId(), request.query(), request.topK());
            var first = new Candidate(
                    T05TestFixtures.preview("D01", "v1", "clause-1", "报销期限", "报销期限为 30 天。"),
                    1, 1, 0.03, 0.9, CandidateOrigin.PRIMARY);
            var second = new Candidate(T05TestFixtures.preview("D02", "v1", "clause-1", "报销期限", "报销期限为 15 天。"),
                    2, 2, 0.02, 0.8, CandidateOrigin.PRIMARY);
            var candidates = scenario == Scenario.OUTSIDE ? List.<Candidate>of()
                    : scenario == Scenario.CONFLICT ? List.of(first, second) : List.of(first);
            boolean failed = scenario == Scenario.RECOVERY && count == 1;
            var errors = failed ? List.of(new RecallProblem(scope, RecallRoute.VECTOR,
                    new Failure(Failure.Code.TIMEOUT, "固定一次故障", true))) : List.<RecallProblem>of();
            var result = new SearchResult(scope, candidates, List.of(), List.of(),
                    new Routes(failed ? RouteStatus.ERROR : RouteStatus.OK, RouteStatus.OK,
                            candidates.isEmpty() ? RouteStatus.SKIPPED_EMPTY : RouteStatus.OK),
                    candidates.isEmpty() ? RankingMode.NONE : RankingMode.RERANK, List.of(), errors, failed, false,
                    new SearchMetrics(failed ? 0 : candidates.size(), candidates.size(), candidates.size(),
                            candidates.size(), candidates.size(), 0, 0, "mock", null,
                            candidates.stream().map(candidate -> candidate.source().key()).toList()));
            context.retrieval().recordSearch(result);
            return result;
        };
        var model = new ScriptedModel(scenario);
        var traces = new RecordingTraceFactory();
        var service = new AgentQaService(store, properties, new QaAgentFactory(model, search,
                new KnowledgeEvidenceReader(store, properties), properties), new DefaultAnswerValidator(), traces);
        var request = new QaRequest(scenario == Scenario.OUTSIDE ? "公司的股票期权兑现价格是多少？" : "报销期限是多久？", List.of());
        var answer = service.answer(request).block(Duration.ofSeconds(15));
        assertThat(answer).isNotNull();
        var events = traces.events(answer.runId());
        String caseId = switch (scenario) {
            case CONFLICT -> "E02";
            case OUTSIDE -> "E03";
            case RECOVERY -> "E06";
            case TOOL_LIMIT, ITERATION_LIMIT -> "E07";
            default -> "E10";
        };
        QaEvidence.write(Path.of("examples/mock"), caseId, request, answer, events, QaEvidence.Mode.MOCK,
                Map.of("scenario", scenario.name(), "model", model.getModelName(),
                        "reproduce", "mvn -Dtest=QaOfflineMockTest test"));
        switch (scenario) {
            case ANSWER -> {
                assertThat(answer.status()).isEqualTo(TaskStatus.ANSWERED);
                assertThat(answer.citations()).singleElement().satisfies(citation ->
                        assertThat(citation.quote()).isEqualTo("30 天"));
                assertThat(model.inputs.get(2).stream().flatMap(message -> message.getContent().stream()))
                        .anyMatch(block -> block instanceof ToolResultBlock result && result.getId().equals("read-1"));
                var toolPayloads = collectToolPayloads(model.inputs);
                assertThat(toolPayloads).anySatisfy(payload -> assertThat(payload)
                        .contains("\"primary\"", "\"documentId\"", "\"preview\"")
                        .doesNotContain("rrfScore", "rerankInputKeys", "snapshotId", "topicGroups"));
                assertThat(toolPayloads).anySatisfy(payload -> assertThat(payload)
                        .contains("\"content\"", "\"supersedes\"")
                        .doesNotContain("contentHash", "startOffset", "endOffset"));
            }
            case CONFLICT -> {
                assertThat(answer.status()).isEqualTo(TaskStatus.CONFLICT);
                assertThat(answer.citations()).extracting(AnswerEnvelope.Citation::documentId).containsExactly("D01", "D02");
                assertThat(searches.get()).isEqualTo(2);
                assertThat(answer.clarifyingQuestion()).isNotBlank();
            }
            case OUTSIDE -> assertThat(answer.status()).isEqualTo(TaskStatus.NO_EVIDENCE);
            case RECOVERY -> {
                assertThat(answer.status()).isEqualTo(TaskStatus.ANSWERED);
                assertThat(answer.retrieval().unresolvedErrors()).isEmpty();
                assertThat(answer.retrieval().degraded()).isTrue();
                assertThat(searches.get()).isEqualTo(2);
                assertThat(collectToolPayloads(model.inputs)).anySatisfy(payload -> assertThat(payload)
                        .contains("\"unresolvedErrors\"", "\"VECTOR\"", "\"TIMEOUT\"")
                        .doesNotContain("snapshotId", "\"scope\""));
            }
            case UNOBSERVED -> {
                assertThat(answer.reason()).isEqualTo(AnswerReason.OUTPUT_INVALID);
                assertThat(answer.citations()).isEmpty();
            }
            case TOOL_LIMIT -> {
                assertThat(answer.reason()).isEqualTo(AnswerReason.BUDGET_EXHAUSTED);
                assertThat(answer.budget().toolCalls()).isEqualTo(2);
                assertThat(searches.get()).isEqualTo(2);
            }
            case ITERATION_LIMIT -> {
                assertThat(answer.reason()).isEqualTo(AnswerReason.MAX_ITERATIONS);
                assertThat(answer.budget().iterationLimitExceeded()).isTrue();
            }
            case MODEL_FAILURE -> {
                assertThat(answer.reason()).isEqualTo(AnswerReason.MODEL_FAILED);
                assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(List.of(answer, events)))
                        .doesNotContain("upstream-secret");
            }
        }
        assertThat(events.get(0).detail()).isInstanceOf(TraceEvent.Started.class);
        assertThat(events.get(events.size() - 1).detail()).isInstanceOf(TraceEvent.Finished.class);
        assertThat(events).allSatisfy(event -> assertThat(event.runId()).isEqualTo(answer.runId()));
        if (scenario != Scenario.MODEL_FAILURE) {
            assertThat(collectToolPayloads(model.inputs)).isNotEmpty().allSatisfy(payload -> assertThat(payload)
                    .doesNotContain("vectorRank", "keywordRank", "rrfScore", "rerankScore", "origin",
                            "\"scope\"", "topicGroups", "metrics", "rerankMillis", "rerankInputKeys",
                            "rerankResponseId", "rerankUsage",
                            "snapshotId", "contentHash", "startOffset", "endOffset"));
        }
    }

    private static List<String> collectToolPayloads(List<List<Msg>> inputs) {
        return inputs.stream().flatMap(List::stream)
                .flatMap(message -> message.getContent().stream())
                .filter(ToolResultBlock.class::isInstance)
                .map(ToolResultBlock.class::cast)
                .flatMap(result -> result.getOutput().stream())
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .toList();
    }

    private static AgentProperties properties(Scenario scenario) {
        var base = T05TestFixtures.properties();
        var limits = base.limits();
        return new AgentProperties(new AgentProperties.DeepSeek("unused", base.deepseek().baseUrl(),
                "deterministic-test", 131072, 2048), base.siliconflow(), base.retrieval(),
                new AgentProperties.Limits(limits.questionCharacters(), limits.historyMessages(),
                        limits.historyMessageCharacters(), limits.historyCharacters(), limits.queryCharacters(),
                        96000, limits.readCharacters(), limits.toolTextCharacters(), limits.embeddingBatchSize(),
                        limits.embeddingTextCharacters(), limits.rerankCharacters(),
                        scenario == Scenario.ITERATION_LIMIT ? 2 : 6, scenario == Scenario.TOOL_LIMIT ? 2 : 12),
                base.timeouts(), base.index());
    }

    private static final class ScriptedModel implements Model {
        private final Scenario scenario;
        private final List<List<Msg>> inputs = new CopyOnWriteArrayList<>();

        private ScriptedModel(Scenario scenario) { this.scenario = scenario; }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(List.copyOf(messages));
            if (scenario == Scenario.MODEL_FAILURE) return Flux.error(new IllegalStateException("upstream-secret"));
            long observed = messages.stream().flatMap(message -> message.getContent().stream())
                    .filter(ToolResultBlock.class::isInstance).count();
            if (observed == 0 || scenario == Scenario.TOOL_LIMIT || scenario == Scenario.ITERATION_LIMIT
                    || scenario == Scenario.RECOVERY && observed == 1) {
                return response(new ToolUseBlock("search-" + observed, "searchKnowledge", Map.of(
                        "query", "报销期限", "topK", 6, "purpose", "查找期限原文")));
            }
            if (scenario == Scenario.OUTSIDE) {
                return response(finish(new AnswerDraft(TaskStatus.NO_EVIDENCE, AnswerReason.OUT_OF_CORPUS,
                        "资料没有相关规定", List.of(), List.of("缺少相关制度"), null)));
            }
            long readsBefore = scenario == Scenario.RECOVERY ? 2 : 1;
            if (observed == readsBefore) {
                var read = new ToolUseBlock("read-1", "readKnowledge", Map.of("documentId", "D01",
                        "version", "v1", "chunkId", "clause-1", "purpose", "核对期限原文"));
                return scenario == Scenario.UNOBSERVED ? response(read, finish(answer())) : response(read);
            }
            if (scenario == Scenario.CONFLICT && observed == 2) {
                return response(new ToolUseBlock("read-2", "readKnowledge", Map.of("documentId", "D02",
                        "version", "v1", "chunkId", "clause-1", "purpose", "核对冲突另一方")));
            }
            if (scenario == Scenario.CONFLICT && observed == 3) {
                return response(new ToolUseBlock("supplement", "searchKnowledge", Map.of("query", "适用范围和替代关系",
                        "topK", 6, "purpose", "查证制度适用关系")));
            }
            return response(finish(scenario == Scenario.CONFLICT
                    ? new AnswerDraft(TaskStatus.CONFLICT, AnswerReason.APPLICABILITY_UNKNOWN, "30 天与 15 天冲突",
                    List.of(new AnswerDraft.CitationDraft("D01", "v1", "clause-1", "30 天"),
                            new AnswerDraft.CitationDraft("D02", "v1", "clause-1", "15 天")),
                    List.of("适用范围未知"), "应适用哪份制度？") : answer()));
        }

        private static AnswerDraft answer() {
            return new AnswerDraft(TaskStatus.ANSWERED, AnswerReason.EVIDENCE_SUFFICIENT, "期限为30天",
                    List.of(new AnswerDraft.CitationDraft("D01", "v1", "clause-1", "30 天")), List.of(), null);
        }

        private static ToolUseBlock finish(AnswerDraft draft) {
            // SDK 的工具协议要求异构 JSON 对象，仅在这个外部协议边界转换为 Map。
            Map<String, Object> arguments = new ObjectMapper().convertValue(draft, new TypeReference<>() {});
            return new ToolUseBlock("finish", "generate_response", Map.of("response", arguments));
        }

        private static Flux<ChatResponse> response(ToolUseBlock... blocks) {
            var content = new java.util.ArrayList<ContentBlock>();
            for (var block : blocks) {
                content.add(ToolUseBlock.builder().id(block.getId()).name(block.getName()).input(block.getInput())
                        .content(JsonUtils.getJsonCodec().toJson(block.getInput())).build());
            }
            return Flux.just(ChatResponse.builder().id("mock-" + java.util.UUID.randomUUID()).content(content).build());
        }

        @Override public String getModelName() { return "deterministic-test"; }
        @Override public int getContextWindowSize() { return 131072; }
    }
}
