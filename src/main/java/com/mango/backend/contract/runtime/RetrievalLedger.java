package com.mango.backend.contract.runtime;

import com.mango.backend.contract.knowledge.KnowledgeTypes.ChunkKey;

import static com.mango.backend.contract.search.SearchTypes.*;

/** T07 创建请求独立实现；T05 每次操作恰登记一次，T06 只读取摘要。 */
public interface RetrievalLedger {
    void recordSearch(SearchResult result);
    void recordRead(ReadProblem problem);
    void resolveRead(ChunkKey key);
    RetrievalSummary summary();
}
