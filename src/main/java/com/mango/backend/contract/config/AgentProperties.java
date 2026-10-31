package com.mango.backend.contract.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

/** 真实运行配置；启动仅做本地校验，不发起模型请求。 */
@Validated
@ConfigurationProperties("knowledge-agent")
public record AgentProperties(
        @Valid @NotNull DeepSeek deepseek,
        @Valid @NotNull SiliconFlow siliconflow,
        @Valid @NotNull Retrieval retrieval,
        @Valid @NotNull Limits limits,
        @Valid @NotNull Timeouts timeouts,
        @Valid @NotNull Index index) {

    public record DeepSeek(@NotBlank String apiKey, @NotNull URI baseUrl,
                           @NotBlank String model, @Positive int contextWindow,
                           @Positive int maxOutputTokens) {
        @AssertTrue(message = "DeepSeek base-url 必须是无认证信息、查询串和片段的 HTTP(S) 地址")
        public boolean isEndpointValid() {
            return validEndpoint(baseUrl);
        }

        @AssertTrue(message = "max-output-tokens 必须小于 context-window")
        public boolean isWindowValid() {
            return maxOutputTokens < contextWindow;
        }

        @Override
        public String toString() {
            return "DeepSeek[apiKey=<redacted>, model=" + model + "]";
        }
    }

    public record SiliconFlow(@NotBlank String apiKey, @NotNull URI baseUrl,
                              @NotBlank String embeddingModel, @NotBlank String rerankModel) {
        @AssertTrue(message = "SiliconFlow base-url 必须是无认证信息、查询串和片段的 HTTP(S) 地址")
        public boolean isEndpointValid() {
            return validEndpoint(baseUrl);
        }

        @Override
        public String toString() {
            return "SiliconFlow[apiKey=<redacted>, embeddingModel=" + embeddingModel
                    + ", rerankModel=" + rerankModel + "]";
        }
    }

    public record Retrieval(@Positive int routeLimit, @Positive int candidatePoolLimit,
                            @Positive int primaryLimit, @Min(0) int relatedLimit,
                            @Positive int rrfK, @Positive int previewCharacters) {
        @AssertTrue(message = "primary-limit <= candidate-pool-limit <= 2 * route-limit")
        public boolean isPoolValid() {
            return primaryLimit <= candidatePoolLimit && candidatePoolLimit <= 2L * routeLimit;
        }
    }

    public record Limits(@Positive int questionCharacters, @Positive int historyMessages,
                         @Positive int historyMessageCharacters, @Positive int historyCharacters,
                         @Positive int queryCharacters, @Positive int modelInputCharacters,
                         @Positive int readCharacters, @Positive int toolTextCharacters,
                         @Positive int embeddingBatchSize, @Positive int embeddingTextCharacters,
                         @Positive int rerankCharacters, @Positive int reactIterations,
                         @Positive int toolCalls) {
        @AssertTrue(message = "单条历史上限不能超过累计历史上限；问题与历史上限之和不能超过模型输入上限")
        public boolean isInputSizeValid() {
            return historyMessageCharacters <= historyCharacters
                    && (long) questionCharacters + historyCharacters <= modelInputCharacters;
        }
    }

    public record Timeouts(@Positive long requestMs, @Positive long modelMs,
                           @Positive long toolMs, @Positive long embeddingMs,
                           @Positive long rerankMs, @Positive long databaseMs,
                           @Min(250) long connectionMs) {
        @AssertTrue(message = "各单次调用超时不能超过请求总超时")
        public boolean isDeadlineValid() {
            return modelMs <= requestMs && toolMs <= requestMs && embeddingMs <= requestMs
                    && rerankMs <= requestMs && databaseMs <= requestMs && connectionMs <= requestMs;
        }
    }

    public record Index(@NotBlank String preprocessingVersion, @NotBlank String tokenizerVersion,
                        @NotBlank String dictionaryVersion, @NotBlank String stopWordsVersion,
                        String dictionaryLocation, String stopWordsLocation) {}

    private static boolean validEndpoint(URI uri) {
        return uri == null || (("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null
                && uri.getRawQuery() == null && uri.getRawFragment() == null);
    }
}
