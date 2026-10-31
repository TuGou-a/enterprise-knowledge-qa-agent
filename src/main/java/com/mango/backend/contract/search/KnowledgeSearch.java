package com.mango.backend.contract.search;

import com.mango.backend.contract.runtime.RunContext;

import static com.mango.backend.contract.search.SearchTypes.*;

/** T05 实现；在运行固定快照内检索，登记每次检索的状态、降级和限制。
 * 预览文本预算由 T07 工具包装层在返回模型前预占；拒绝时返回工具错误，不暴露候选。 */
public interface KnowledgeSearch {
    SearchResult search(SearchRequest request, RunContext context);
}
