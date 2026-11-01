# T07：真实模型结构化输出稳定性差异

## 现象

同一工具、提示词、资料快照和故障配置下，`deepseek-flash` 出现偶发无效输出：

- E01 真实 HTTP 首次调用返回 `INCOMPLETE/OUTPUT_INVALID`，随后 3 次返回 `ANSWERED`。
- E04 首次运行返回 `INCOMPLETE/CITATION_INVALID`，随后 3 次返回 `ANSWERED`。
- E09 采样中曾输出非法的 `NEEDS_CLARIFICATION/APPLICABILITY_UNKNOWN` 组合。

运行时校验器拒绝了这些草稿，没有把无效回答返回给调用方，也没有自动重试或修复。

## 环境

- macOS、Java 21.0.12、Maven 3.9.9
- Spring Boot 3.5.10、AgentScope Java 2.0.4
- DeepSeek 官方 OpenAI 兼容 API
- 真实 PostgreSQL/pgvector、硅基流动 Embedding/Rerank

## 原因

模型在最终 `generate_response` 工具中生成结构化草稿时，对字段枚举、状态前提和原文引用的遵循程度
存在模型间差异。应用可以校验草稿，但不能从模型输出本身推断其为何选择了不存在的引用或错误枚举。

`deepseek-flash` 更适合低延迟探索；本次验收要求稳定的工具调用、结构化字段和引用复制，
因此不能仅靠单次成功结果忽略偶发失败。

## 解决方案

- 保留运行时结构、状态前提、已观察证据和原文一致性校验。
- 将运行配置切换为实测更稳定的 `deepseek-chat`；E01 连续 `3/3` 通过，E03、E04、E09 各运行
  一次均通过（关键场景合计 `6/6`），再完成全部 E01、E02、E03、E04、E05、E06、E08、E09 最终运行。
- 不增加自动修复模型调用；无效输出继续明确返回 `INCOMPLETE`。
- 在文档中保留模型选择的事实、对照边界和偶发失败，不把运行时拒绝描述成回答验收通过。

该结论只针对本次模型、提示词、工具协议和合成资料，不外推到其他模型或生产流量。
