package com.mango.backend.infra.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.model.ModelTypes.Usage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.mango.backend.contract.error.Failure.Code.*;

/** 两个硅基流动客户端共用的 HTTP、JSON 和错误边界，不保存请求级状态。 */
@Component
final class SiliconFlowHttpClient {
    private final AgentProperties.SiliconFlow properties;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String baseUrl;

    SiliconFlowHttpClient(AgentProperties properties, ObjectMapper mapper) {
        this.properties = properties.siliconflow();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.timeouts().connectionMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.mapper = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.baseUrl = this.properties.baseUrl().toString().replaceAll("/+$", "");
    }

    JsonNode post(String resource, ModelRequest payload, CallBudget budget) {
        budget.remainingNanos();
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(payload);
        } catch (IOException exception) {
            throw failure(INTERNAL_ERROR, "模型请求编码失败");
        }
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + resource))
                .timeout(Duration.ofNanos(budget.remainingNanos()))
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        var pending = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> response;
        try {
            // 等待整个 body 完成；仅等待响应头不能约束后续缓慢或停滞的读取。
            response = pending.get(budget.remainingNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            throw failure(TIMEOUT, "模型调用超时");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(UPSTREAM_UNAVAILABLE, "模型调用已中断");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof HttpTimeoutException) {
                throw failure(TIMEOUT, "模型调用超时");
            }
            if (exception.getCause() instanceof IOException) {
                throw failure(UPSTREAM_UNAVAILABLE, "模型服务连接或读取失败");
            }
            throw failure(INTERNAL_ERROR, "模型请求执行失败");
        } finally {
            if (!pending.isDone()) {
                pending.cancel(true);
            }
        }
        budget.remainingNanos();
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw switch (status) {
                case 400, 422 -> failure(INVALID_ARGUMENT, "模型服务拒绝请求参数");
                case 413 -> failure(INPUT_LIMIT_EXCEEDED, "模型服务输入超限");
                case 401, 403 -> failure(AUTHENTICATION_FAILED, "模型服务认证或授权失败");
                case 408, 504 -> failure(TIMEOUT, "模型服务响应超时");
                case 429 -> failure(RATE_LIMITED, "模型服务请求受限");
                default -> failure(UPSTREAM_UNAVAILABLE, "模型服务不可用");
            };
        }
        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (IOException exception) {
            throw failure(INVALID_RESPONSE, "模型响应不是有效 JSON");
        }
        if (root == null || !root.isObject()) {
            throw failure(INVALID_RESPONSE, "模型响应必须为 JSON 对象");
        }
        budget.remainingNanos();
        return root;
    }

    static JsonNode requiredArray(JsonNode root, String field) {
        var value = root.path(field);
        if (!value.isArray()) {
            throw failure(INVALID_RESPONSE, "模型响应缺少有效结果列表");
        }
        return value;
    }

    static JsonNode optionalObject(JsonNode root, String field) {
        var value = root.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return value;
        }
        if (!value.isObject()) {
            throw failure(INVALID_RESPONSE, "模型响应计量格式无效");
        }
        return value;
    }

    static String responseId(JsonNode root) {
        var value = root.path("id");
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw failure(INVALID_RESPONSE, "模型响应标识格式无效");
        }
        return value.textValue().isBlank() ? null : value.textValue();
    }

    static Usage usage(JsonNode node, String inputField) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return new Usage(tokenCount(node.path(inputField)), tokenCount(node.path("total_tokens")));
    }

    private static Long tokenCount(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0) {
            throw failure(INVALID_RESPONSE, "模型响应计量必须为非负整数");
        }
        return node.longValue();
    }

    static DependencyException failure(Failure.Code code, String message) {
        boolean retryable = code == TIMEOUT || code == RATE_LIMITED || code == UPSTREAM_UNAVAILABLE;
        // 原始异常可能携带响应体、请求参数或配置；对外只保留明确的脱敏错误。
        return new DependencyException(new Failure(code, message, retryable));
    }

    sealed interface ModelRequest permits EmbeddingRequest, RerankRequest {}

    record EmbeddingRequest(String model, List<String> input,
                            @JsonProperty("encoding_format") String encodingFormat) implements ModelRequest {}

    record RerankRequest(String model, String query, List<String> documents,
                         @JsonProperty("top_n") int topN,
                         @JsonProperty("return_documents") boolean returnDocuments) implements ModelRequest {}

    record CallBudget(long startedAt, long timeoutNanos) {
        static CallBudget start(Duration timeout) {
            long startedAt = System.nanoTime();
            if (timeout == null) {
                throw failure(INVALID_ARGUMENT, "模型调用必须提供超时预算");
            }
            if (timeout.isZero() || timeout.isNegative()) {
                throw failure(BUDGET_EXHAUSTED, "模型调用没有剩余预算");
            }
            try {
                return new CallBudget(startedAt, timeout.toNanos());
            } catch (ArithmeticException exception) {
                throw failure(INVALID_ARGUMENT, "模型调用超时预算超出可表示范围");
            }
        }

        long remainingNanos() {
            long remaining = timeoutNanos - (System.nanoTime() - startedAt);
            if (remaining <= 0) {
                throw failure(TIMEOUT, "模型调用超时");
            }
            // 解析与结果校验不会自动响应线程中断，在共享检查点拒绝已中断的结果。
            if (Thread.currentThread().isInterrupted()) {
                throw failure(UPSTREAM_UNAVAILABLE, "模型调用已中断");
            }
            return remaining;
        }
    }
}
