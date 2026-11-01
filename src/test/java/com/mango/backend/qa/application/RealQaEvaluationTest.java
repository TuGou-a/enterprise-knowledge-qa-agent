package com.mango.backend.qa.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.config.DatabaseProperties;
import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.knowledge.KnowledgeStore;
import com.mango.backend.contract.model.EmbeddingClient;
import com.mango.backend.contract.model.RerankClient;
import com.mango.backend.contract.qa.QaRequest;
import com.mango.backend.contract.qa.QaService;
import com.mango.backend.evaluation.QaEvidence;
import com.mango.backend.evaluation.RecordingTraceFactory;
import com.mango.backend.evaluation.RetrievalEvaluation;
import com.mango.backend.infra.model.SiliconFlowEmbeddingClient;
import com.mango.backend.infra.model.SiliconFlowRerankClient;
import com.mango.backend.infra.pg.KnowledgeMapper;
import com.mango.backend.knowledge.ingest.CorpusEvaluationBinding;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 显式真实问答评估；故障开关只存在于测试配置，不注册 HTTP 参数或生产开关。 */
@Tag("real")
@ActiveProfiles("real-qa-evaluation")
@SpringBootTest(classes = RealQaEvaluationTest.Config.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RealQaEvaluationTest {
    @Autowired private QaService service;
    @Autowired private KnowledgeStore store;
    @Autowired private RecordingTraceFactory traces;
    @Autowired private FaultPlan faults;
    @Autowired private AgentProperties properties;

    @Test
    void recordsActualAnswerAndTraceForExplicitCaseAndSnapshot() throws Exception {
        String caseId = System.getProperty("evaluation.case", "");
        String expectedSnapshot = System.getProperty("evaluation.snapshot", "");
        if (!Set.of("E01", "E02", "E03", "E04", "E05", "E06", "E08", "E09").contains(caseId)
                || expectedSnapshot.isBlank()) {
            throw new IllegalArgumentException("需要 evaluation.case=E01/E02/E03/E04/E05/E06/E08/E09 和 evaluation.snapshot=<实际ID>");
        }
        String corpusId = caseId.equals("E02") ? "conflict" : caseId.equals("E08")
                ? System.getProperty("evaluation.corpus", "mutation") : "normal";
        if (caseId.equals("E08") && !Set.of("normal", "mutation").contains(corpusId)) {
            throw new IllegalArgumentException("E08 的 evaluation.corpus 只接受 normal 或 mutation");
        }
        var labels = RetrievalEvaluation.load(Path.of("examples/retrieval-labels.json"));
        var corpus = labels.corpora().stream().filter(value -> value.corpusId().equals(corpusId)).findFirst().orElseThrow();
        var snapshot = store.latestReadySnapshot(Duration.ofMillis(properties.timeouts().databaseMs())).orElseThrow();
        if (!snapshot.snapshotId().equals(expectedSnapshot)) {
            throw new IllegalArgumentException("最新 READY 快照与指定用例资料不符，请先按资料说明发布所需资料");
        }
        CorpusEvaluationBinding.verify(corpus, snapshot, store, properties);
        var mapper = new ObjectMapper();
        QaRequest request = null;
        for (var planned : mapper.readTree(Path.of("examples/cases.json").toFile()).path("cases")) {
            if (planned.path("id").asText().equals(caseId)) {
                request = mapper.treeToValue(planned.path("request"), QaRequest.class);
                break;
            }
        }
        if (request == null) throw new IllegalArgumentException("缺少对应计划请求");
        faults.mode = caseId.equals("E06") ? FaultMode.EMBEDDING_ONCE
                : caseId.equals("E09") ? FaultMode.RERANK_ONCE : FaultMode.NONE;
        faults.embeddingCalls.set(0);
        faults.rerankCalls.set(0);
        faults.injected.set(0);
        var response = service.answer(request).block(Duration.ofMillis(properties.timeouts().requestMs() + 5000));
        assertThat(response).isNotNull();
        var mode = faults.mode == FaultMode.NONE ? QaEvidence.Mode.REAL : QaEvidence.Mode.REAL_WITH_INJECTED_FAULT;
        QaEvidence.write(Path.of("examples/real"), caseId, request, response, traces.events(response.runId()), mode,
                Map.of("caseId", caseId, "corpusId", corpusId, "annotationStatus", labels.annotationStatus(),
                        "model", properties.deepseek().model(), "embeddingModel", properties.siliconflow().embeddingModel(),
                        "rerankModel", properties.siliconflow().rerankModel(), "faultMode", faults.mode.name(),
                        "injectedCount", Integer.toString(faults.injected.get()),
                        "embeddingCalls", Integer.toString(faults.embeddingCalls.get()),
                        "rerankCalls", Integer.toString(faults.rerankCalls.get())));
        assertThat(response.snapshotId()).isEqualTo(expectedSnapshot);
        if (faults.mode != FaultMode.NONE) assertThat(faults.injected.get()).isEqualTo(1);
        // 实际状态及语义质量保留给人工核对，不把模型执行完成等同于验收通过。
    }

    enum FaultMode { NONE, EMBEDDING_ONCE, RERANK_ONCE }
    static final class FaultPlan {
        volatile FaultMode mode = FaultMode.NONE;
        final AtomicInteger embeddingCalls = new AtomicInteger();
        final AtomicInteger rerankCalls = new AtomicInteger();
        final AtomicInteger injected = new AtomicInteger();
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("real-qa-evaluation")
    @EnableAutoConfiguration
    @EnableConfigurationProperties({AgentProperties.class, DatabaseProperties.class})
    @MapperScan(basePackageClasses = KnowledgeMapper.class)
    @ComponentScan(basePackages = {"com.mango.backend.infra.pg", "com.mango.backend.infra.model",
            "com.mango.backend.knowledge.ingest", "com.mango.backend.knowledge.search",
            "com.mango.backend.knowledge.evidence", "com.mango.backend.evaluation",
            "com.mango.backend.qa.agent", "com.mango.backend.qa.application"})
    static class Config {
        @Bean FaultPlan faultPlan() { return new FaultPlan(); }
        @Bean @Primary RecordingTraceFactory recordingTraces() { return new RecordingTraceFactory(); }

        @Bean @Primary
        EmbeddingClient evaluationEmbedding(SiliconFlowEmbeddingClient delegate, FaultPlan plan) {
            return (texts, timeout) -> {
                int call = plan.embeddingCalls.incrementAndGet();
                if (plan.mode == FaultMode.EMBEDDING_ONCE && call == 1) {
                    plan.injected.incrementAndGet();
                    throw new DependencyException(new Failure(Failure.Code.TIMEOUT, "评估注入一次 Embedding 超时", true));
                }
                return delegate.embed(texts, timeout);
            };
        }

        @Bean @Primary
        RerankClient evaluationRerank(SiliconFlowRerankClient delegate, FaultPlan plan) {
            return (query, documents, topN, timeout) -> {
                int call = plan.rerankCalls.incrementAndGet();
                if (plan.mode == FaultMode.RERANK_ONCE && call == 1) {
                    plan.injected.incrementAndGet();
                    throw new DependencyException(new Failure(Failure.Code.TIMEOUT, "评估注入一次 Rerank 超时", true));
                }
                return delegate.rerank(query, documents, topN, timeout);
            };
        }
    }
}
