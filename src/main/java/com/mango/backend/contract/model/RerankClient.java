package com.mango.backend.contract.model;

import java.time.Duration;
import java.util.List;

import static com.mango.backend.contract.model.ModelTypes.*;

/** T02 实现；完整验证结果数量、下标唯一性/范围与有限分数；失败由 T05 整批回退。 */
public interface RerankClient {
    RerankBatch rerank(String query, List<String> documents, int topN, Duration timeout);
}
