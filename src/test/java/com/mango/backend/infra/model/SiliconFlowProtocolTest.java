package com.mango.backend.infra.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.knowledge.T05TestFixtures;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SiliconFlowProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private HttpServer server;
    private volatile String response;
    private volatile int status;
    private SiliconFlowEmbeddingClient embedding;
    private SiliconFlowRerankClient rerank;

    @BeforeEach
    void setUp() throws Exception {
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", exchange -> {
            try {
                request.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
                var bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        var base = T05TestFixtures.properties();
        var properties = new AgentProperties(base.deepseek(), new AgentProperties.SiliconFlow(
                "synthetic-key", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                "BAAI/bge-m3", "BAAI/bge-reranker-v2-m3"), base.retrieval(), base.limits(),
                base.timeouts(), base.index());
        var http = new SiliconFlowHttpClient(properties, mapper);
        embedding = new SiliconFlowEmbeddingClient(http, properties);
        rerank = new SiliconFlowRerankClient(http, properties);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void restoresEmbeddingInputOrderAndKeepsOptionalUsage() throws Exception {
        var root = embeddingResponse();
        root.putObject("usage").put("prompt_tokens", 12).put("total_tokens", 15);
        response = mapper.writeValueAsString(root);
        var result = embedding.embed(List.of("甲", "乙"), Duration.ofSeconds(5));
        assertThat(result.vectors().get(0).values()[0]).isEqualTo(1);
        assertThat(result.vectors().get(1).values()[0]).isEqualTo(2);
        assertThat(result.usage().inputTokens()).isEqualTo(12L);
        assertThat(result.usage().totalTokens()).isEqualTo(15L);
        assertThat(request.get().path("input")).isEqualTo(mapper.valueToTree(List.of("甲", "乙")));
        assertThat(request.get().path("encoding_format").asText()).isEqualTo("float");
        assertThat(request.get().has("dimensions")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "duplicate", "outside", "fraction", "dimension", "zero", "overflow", "usage", "id"})
    void rejectsMalformedEmbeddingBatches(String defect) throws Exception {
        var root = embeddingResponse();
        var data = root.withArray("data");
        var first = (ObjectNode) data.get(0);
        switch (defect) {
            case "missing" -> data.remove(0);
            case "duplicate" -> first.put("index", 0);
            case "outside" -> first.put("index", 2);
            case "fraction" -> first.put("index", 0.5);
            case "dimension" -> first.withArray("embedding").remove(1);
            case "zero" -> first.withArray("embedding").set(0, mapper.valueToTree(0));
            case "overflow" -> first.withArray("embedding").set(0, mapper.valueToTree(1.0e100));
            case "usage" -> root.putObject("usage").put("prompt_tokens", -1);
            case "id" -> root.put("id", 7);
            default -> throw new AssertionError(defect);
        }
        response = mapper.writeValueAsString(root);
        var failure = assertThrows(DependencyException.class,
                () -> embedding.embed(List.of("甲", "乙"), Duration.ofSeconds(5)));
        assertThat(failure.failure().code()).isEqualTo(Failure.Code.INVALID_RESPONSE);
    }

    @Test
    void mapsRerankIndexesAndSendsFullDocuments() {
        response = """
                {"id":"rank-1","results":[{"index":1,"relevance_score":0.9},
                {"index":0,"relevance_score":0.2}],"meta":{"tokens":{"input_tokens":8}}}
                """;
        var result = rerank.rerank("期限", List.of("完整甲条款", "完整乙条款"), 2, Duration.ofSeconds(5));
        assertThat(result.scores()).extracting(score -> score.inputIndex()).containsExactly(1, 0);
        assertThat(result.usage().inputTokens()).isEqualTo(8L);
        assertThat(result.usage().totalTokens()).isNull();
        assertThat(request.get().path("top_n").asInt()).isEqualTo(2);
        assertThat(request.get().path("return_documents").asBoolean()).isFalse();
        assertThat(request.get().path("documents").get(1).asText()).isEqualTo("完整乙条款");
        assertThat(request.get().has("instruction")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"results\":[]}",
            "{\"results\":[{\"index\":2,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.2}]}",
            "{\"results\":[{\"index\":0,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.2}]}",
            "{\"results\":[{\"index\":0,\"relevance_score\":\"high\"},{\"index\":1,\"relevance_score\":0.2}]}",
            "{\"results\":[{\"index\":0,\"relevance_score\":1e999},{\"index\":1,\"relevance_score\":0.2}]}",
            "{\"results\":[],\"results\":[]}", "{} {}", "[]"
    })
    void rejectsInvalidRerankResponsesAsOneBatch(String body) {
        response = body;
        var failure = assertThrows(DependencyException.class,
                () -> rerank.rerank("期限", List.of("甲", "乙"), 2, Duration.ofSeconds(5)));
        assertThat(failure.failure().code()).isEqualTo(Failure.Code.INVALID_RESPONSE);
    }

    @ParameterizedTest
    @CsvSource({"400,INVALID_ARGUMENT,false", "401,AUTHENTICATION_FAILED,false",
            "403,AUTHENTICATION_FAILED,false", "413,INPUT_LIMIT_EXCEEDED,false",
            "422,INVALID_ARGUMENT,false", "408,TIMEOUT,true", "429,RATE_LIMITED,true",
            "504,TIMEOUT,true", "500,UPSTREAM_UNAVAILABLE,true", "302,UPSTREAM_UNAVAILABLE,true"})
    void mapsHttpFailuresWithoutLeakingResponseBodies(int httpStatus, Failure.Code code, boolean retryable) {
        status = httpStatus;
        response = "secret-upstream-body";
        var failure = assertThrows(DependencyException.class,
                () -> embedding.embed(List.of("甲"), Duration.ofSeconds(5)));
        assertThat(failure.failure().code()).isEqualTo(code);
        assertThat(failure.failure().retryable()).isEqualTo(retryable);
        assertThat(failure.toString()).doesNotContain("secret-upstream-body", "synthetic-key");
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void rejectsExhaustedBudgetsAndOversizedInputBeforeHttp() {
        assertThat(assertThrows(DependencyException.class,
                () -> embedding.embed(List.of("甲"), Duration.ZERO)).failure().code())
                .isEqualTo(Failure.Code.BUDGET_EXHAUSTED);
        assertThat(assertThrows(DependencyException.class,
                () -> embedding.embed(List.of("甲".repeat(4001)), Duration.ofSeconds(5))).failure().code())
                .isEqualTo(Failure.Code.INPUT_LIMIT_EXCEEDED);
        assertThat(assertThrows(DependencyException.class,
                () -> rerank.rerank("甲", List.of("乙"), 2, Duration.ofSeconds(5))).failure().code())
                .isEqualTo(Failure.Code.INVALID_ARGUMENT);
        assertThat(request.get()).isNull();
    }

    private ObjectNode embeddingResponse() {
        var root = mapper.createObjectNode().put("id", "embedding-1");
        var data = root.putArray("data");
        for (int index : List.of(1, 0)) {
            var item = data.addObject().put("index", index);
            var vector = item.putArray("embedding");
            vector.add(index + 1);
            for (int dimension = 1; dimension < 1024; dimension++) {
                vector.add(0);
            }
        }
        return root;
    }
}
