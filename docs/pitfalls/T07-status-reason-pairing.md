# T07：真实模型混用任务状态与原因枚举

## 现象

真实 E09 运行中，模型曾输出：

```text
status=NEEDS_CLARIFICATION
reason=APPLICABILITY_UNKNOWN
```

草稿中的引用和已读原文均有效，但 `DefaultAnswerValidator` 只允许
`NEEDS_CLARIFICATION/CONTEXT_REQUIRED`，因此返回 `INCOMPLETE/OUTPUT_INVALID`。

## 环境

- macOS、Java 21.0.12、Maven 3.9.9
- Spring Boot 3.5.10、AgentScope Java 2.0.4
- 真实 PostgreSQL/pgvector 资料快照
- 真实硅基流动 Embedding/Rerank；E09 首次 Rerank 注入一次超时

## 原因

原系统提示词分别列出可用状态和“资料无法消解分歧时使用 `APPLICABILITY_UNKNOWN`”，但没有明确
说明该原因只能与 `CONFLICT` 配对。模型把“需要向用户追问适用范围”与
`NEEDS_CLARIFICATION` 组合，语义相近但违反 `AnswerDraft` 状态机。

## 解决方案

系统提示词改为列出完整且唯一的 `status/reason` 配对，并补齐校验器已有的执行前提：

- 未恢复的召回、读取或主题截断优先返回 `INCOMPLETE`；已成立的 `CONFLICT` 是例外。
- `CONFLICT` 必须引用至少两个不同 `documentId/version`。
- `NO_EVIDENCE` 要求向量和关键词召回均为 `OK`，且无读取失败或主题截断。
- 只有不同合理解释会指向不同处理对象或依据时才请求澄清；已有明确一般规定时应直接回答并说明边界。

运行时继续拒绝非法组合，不自动改写草稿或追加自动修复调用。最终真实问答证据保留实际状态、
引用与工具事件，静态测试负责防止校验器规则回退。
