package com.mango.backend.contract.search;

import com.mango.backend.contract.runtime.RunContext;

import static com.mango.backend.contract.search.SearchTypes.*;

/** T05 实现；成功时在返回前登记已读证据；失败返回结构化错误并登记读取限制。 */
public interface KnowledgeReader {
    ReadResult read(ReadRequest request, RunContext context);
}
