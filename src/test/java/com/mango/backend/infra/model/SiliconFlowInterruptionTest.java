package com.mango.backend.infra.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.mango.backend.contract.config.AgentProperties.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SiliconFlowInterruptionTest {
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        var mapper = new ObjectMapper();
        var vector = new float[1024];
        vector[0] = 1.0f;
        byte[] embedding = mapper.writeValueAsBytes(Map.of("id", "embedding-response", "data",
                List.of(Map.of("index", 0, "embedding", vector))));
        byte[] rerank = mapper.writeValueAsBytes(Map.of("id", "rerank-response", "results",
                List.of(Map.of("index", 0, "relevance_score", 0.9))));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> respond(exchange, embedding));
        server.createContext("/v1/rerank", exchange -> respond(exchange, rerank));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @ParameterizedTest
    @CsvSource({"EMBEDDING, JSON_PARSING", "RERANK, JSON_PARSING",
            "EMBEDDING, RESULT_VALIDATION", "RERANK, RESULT_VALIDATION"})
    void stopsInterruptedCallsAndPreservesInterruptFlag(ClientKind client, InterruptionPoint point) {
        var properties = new AgentProperties(
                new DeepSeek("synthetic", URI.create("https://example.test"), "model", 32768, 2048),
                new SiliconFlow("synthetic", URI.create("http://127.0.0.1:"
                        + server.getAddress().getPort() + "/v1"), "embedding", "rerank"),
                new Retrieval(20, 20, 6, 12, 60, 300),
                new Limits(2000, 20, 4000, 12000, 512, 24000, 20000, 24000,
                        32, 4000, 64000, 6, 12),
                new Timeouts(120000, 60000, 30000, 15000, 10000, 5000, 2000),
                new Index("pre", "token", "dict", "stop", "", ""));
        var httpClient = new SiliconFlowHttpClient(properties, new InterruptingMapper(point));
        try {
            var exception = assertThrows(DependencyException.class, () -> {
                switch (client) {
                    case EMBEDDING -> new SiliconFlowEmbeddingClient(httpClient, properties)
                            .embed(List.of("报销期限"), Duration.ofSeconds(5));
                    case RERANK -> new SiliconFlowRerankClient(httpClient, properties)
                            .rerank("报销期限", List.of("报销期限为30天。"), 1, Duration.ofSeconds(5));
                }
            });
            assertThat(exception.failure().code()).isEqualTo(Failure.Code.UPSTREAM_UNAVAILABLE);
            assertThat(exception.failure().message()).isEqualTo("模型调用已中断");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // 客户端须保留中断；测试结束后清理，避免影响其他用例。
            Thread.interrupted();
        }
    }

    private static void respond(HttpExchange exchange, byte[] body) throws IOException {
        try {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    enum ClientKind { EMBEDDING, RERANK }
    enum InterruptionPoint { JSON_PARSING, RESULT_VALIDATION }

    private static final class InterruptingMapper extends ObjectMapper {
        private final InterruptionPoint point;

        private InterruptingMapper(InterruptionPoint point) {
            this.point = point;
        }

        private InterruptingMapper(InterruptingMapper source) {
            super(source);
            this.point = source.point;
        }

        @Override
        public ObjectMapper copy() {
            return new InterruptingMapper(this);
        }

        @Override
        public JsonNode readTree(byte[] content) throws IOException {
            var root = super.readValue(content, ObjectNode.class);
            if (point == InterruptionPoint.JSON_PARSING) {
                Thread.currentThread().interrupt();
            } else {
                // 在 post 已返回后，客户端读取响应 ID 时触发中断。
                root.set("id", new TextNode("interrupted-response") {
                    @Override
                    public String textValue() {
                        Thread.currentThread().interrupt();
                        return super.textValue();
                    }
                });
            }
            return root;
        }
    }
}
