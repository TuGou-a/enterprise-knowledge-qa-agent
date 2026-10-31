package com.mango.backend.infra.model;

import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.model.DenseVector;
import com.mango.backend.contract.model.EmbeddingClient;
import com.mango.backend.contract.model.ModelTypes.EmbeddingBatch;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static com.mango.backend.contract.error.Failure.Code.*;
import static com.mango.backend.infra.model.SiliconFlowHttpClient.*;

@Component
public final class SiliconFlowEmbeddingClient implements EmbeddingClient {
    private final SiliconFlowHttpClient httpClient;
    private final AgentProperties properties;

    SiliconFlowEmbeddingClient(SiliconFlowHttpClient httpClient, AgentProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    @Override
    public EmbeddingBatch embed(List<String> texts, Duration timeout) {
        var budget = CallBudget.start(timeout);
        if (texts == null || texts.isEmpty()) {
            throw failure(INVALID_ARGUMENT, "Embedding 输入不能为空");
        }
        if (texts.size() > properties.limits().embeddingBatchSize()) {
            throw failure(INPUT_LIMIT_EXCEEDED, "Embedding 批次文本数量超限");
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw failure(INVALID_ARGUMENT, "Embedding 文本不能为空白");
            }
            if (text.length() > properties.limits().embeddingTextCharacters()) {
                throw failure(INPUT_LIMIT_EXCEEDED, "Embedding 单条文本长度超限");
            }
        }
        var request = new EmbeddingRequest(properties.siliconflow().embeddingModel(),
                List.copyOf(texts), "float");
        var root = httpClient.post("embeddings", request, budget);
        var data = requiredArray(root, "data");
        if (data.size() != request.input().size()) {
            throw failure(INVALID_RESPONSE, "Embedding 返回数量与输入不一致");
        }
        var vectors = new DenseVector[data.size()];
        for (var item : data) {
            var index = item.path("index");
            if (!index.isIntegralNumber() || !index.canConvertToInt()
                    || index.intValue() < 0 || index.intValue() >= vectors.length) {
                throw failure(INVALID_RESPONSE, "Embedding 返回下标无效");
            }
            int inputIndex = index.intValue();
            if (vectors[inputIndex] != null) {
                throw failure(INVALID_RESPONSE, "Embedding 返回下标重复");
            }
            var embedding = requiredArray(item, "embedding");
            var values = new float[embedding.size()];
            boolean hasNonZero = false;
            for (int i = 0; i < values.length; i++) {
                var value = embedding.get(i);
                if (!value.isNumber()) {
                    throw failure(INVALID_RESPONSE, "Embedding 向量分量必须为数值");
                }
                values[i] = value.floatValue();
                hasNonZero |= values[i] != 0.0f;
            }
            if (!hasNonZero) {
                throw failure(INVALID_RESPONSE, "Embedding 不能为零向量");
            }
            try {
                // 维度和有限性由内部值类型一次性约束，边界转换为模型协议错误。
                vectors[inputIndex] = new DenseVector(values);
            } catch (IllegalArgumentException exception) {
                throw failure(INVALID_RESPONSE, "Embedding 必须为 1024 维有限数值向量");
            }
        }
        var result = new EmbeddingBatch(Arrays.asList(vectors), responseId(root),
                usage(optionalObject(root, "usage"), "prompt_tokens"));
        budget.remainingNanos();
        return result;
    }
}
