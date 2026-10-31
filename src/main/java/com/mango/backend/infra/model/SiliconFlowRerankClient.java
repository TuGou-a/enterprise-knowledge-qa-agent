package com.mango.backend.infra.model;

import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.model.ModelTypes.RerankBatch;
import com.mango.backend.contract.model.ModelTypes.RerankScore;
import com.mango.backend.contract.model.RerankClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static com.mango.backend.contract.error.Failure.Code.*;
import static com.mango.backend.infra.model.SiliconFlowHttpClient.*;

@Component
public final class SiliconFlowRerankClient implements RerankClient {
    private final SiliconFlowHttpClient httpClient;
    private final AgentProperties properties;

    SiliconFlowRerankClient(SiliconFlowHttpClient httpClient, AgentProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    @Override
    public RerankBatch rerank(String query, List<String> documents, int topN, Duration timeout) {
        var budget = CallBudget.start(timeout);
        if (query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            throw failure(INVALID_ARGUMENT, "Rerank 查询和候选不能为空");
        }
        if (topN < 1 || topN > documents.size()) {
            throw failure(INVALID_ARGUMENT, "Rerank topN 必须位于 1 至候选数");
        }
        if (query.length() > properties.limits().queryCharacters()
                || documents.size() > properties.retrieval().candidatePoolLimit()) {
            throw failure(INPUT_LIMIT_EXCEEDED, "Rerank 查询长度或候选数量超限");
        }
        long characters = 0;
        for (String document : documents) {
            if (document == null || document.isBlank()) {
                throw failure(INVALID_ARGUMENT, "Rerank 候选全文不能为空白");
            }
            characters += document.length();
            if (characters > properties.limits().rerankCharacters()) {
                throw failure(INPUT_LIMIT_EXCEEDED, "Rerank 候选全文累计长度超限");
            }
        }
        var request = new RerankRequest(properties.siliconflow().rerankModel(), query,
                List.copyOf(documents), topN, false);
        var root = httpClient.post("rerank", request, budget);
        var results = requiredArray(root, "results");
        if (results.size() != topN) {
            throw failure(INVALID_RESPONSE, "Rerank 返回数量与 topN 不一致");
        }
        var seen = new HashSet<Integer>();
        var scores = new ArrayList<RerankScore>(topN);
        for (var item : results) {
            var index = item.path("index");
            if (!index.isIntegralNumber() || !index.canConvertToInt()
                    || index.intValue() < 0 || index.intValue() >= request.documents().size()) {
                throw failure(INVALID_RESPONSE, "Rerank 返回下标无效");
            }
            int inputIndex = index.intValue();
            if (!seen.add(inputIndex)) {
                throw failure(INVALID_RESPONSE, "Rerank 返回下标重复");
            }
            var score = item.path("relevance_score");
            if (!score.isNumber() || !Double.isFinite(score.doubleValue())) {
                throw failure(INVALID_RESPONSE, "Rerank 相关性分数必须为有限数值");
            }
            scores.add(new RerankScore(inputIndex, score.doubleValue()));
        }
        var result = new RerankBatch(scores, responseId(root),
                usage(optionalObject(optionalObject(root, "meta"), "tokens"), "input_tokens"));
        budget.remainingNanos();
        return result;
    }
}
