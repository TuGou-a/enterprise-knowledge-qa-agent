package com.mango.backend.contract.model;

import java.time.Duration;
import java.util.List;

import static com.mango.backend.contract.model.ModelTypes.*;

/** T02 实现；失败抛 DependencyException，不返回空向量或部分批次。 */
public interface EmbeddingClient {
    EmbeddingBatch embed(List<String> texts, Duration timeout);
}
