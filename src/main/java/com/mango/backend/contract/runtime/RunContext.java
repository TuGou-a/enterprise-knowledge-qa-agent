package com.mango.backend.contract.runtime;

import com.mango.backend.contract.knowledge.KnowledgeTypes.CorpusSnapshot;

/** T07 选择 READY 快照后创建；通过 AgentScope RuntimeContext 按类型注入，不是模型参数。 */
public record RunContext(String runId, CorpusSnapshot snapshot, ExecutionBudget budget,
                         EvidenceRegistry evidence, RetrievalLedger retrieval, TraceRecorder trace) {}
