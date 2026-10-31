# 验收输入与运行证据

当前目录只有计划输入和未填写的标注模板，没有完整问答的实际输出或 Trace。已有 48 个契约/模型中断测试通过，T04 的数据库与资料导入验证记录见[运行验证](../docs/t04-ingestion-verification.md)；这些结果不能替代 E01–E10 的完整问答验收。

- [cases.json](cases.json)：E01 至 E10 的请求、前置条件、预期检查及需求映射。`request` 可在 HTTP 接口实现并获得测试指令后作为请求体；其余字段供测试执行者阅读，不传给 API。
- [retrieval-labels.template.json](retrieval-labels.template.json)：检索标注模板。`null` 表示尚未标注，不能当成「没有相关条款」或计入指标。
- [评估文档](../docs/03-evaluation.md)：当前测试范围、执行条件、指标口径及后续工作。

## 标注方法

T04 资料合并后，先固定快照，再为每个问题填写 `snapshotId`、相关条款、冲突对和标准答案。E01 与 E02 虽然问法相同，但资料前提不同，必须绑定各自的快照；E04 与 E01 使用相同快照。不得用检索返回的候选直接充当人工标准。

`relevantClauses` 填写相关条款的完整 `ChunkKey`：包含 `document.snapshotId`、`document.documentId`、`document.version` 和 `chunkId`。`conflictPairs` 填写双方的条款键；没有冲突时填写空数组。标准答案保留规则的必要条件和资料缺口。只有确认为资料外的问题才允许相关条款为空数组，并从正例 Recall/MRR 分母中排除。

模板只是起点；实际评估应补齐固定问题集，至少包含明确术语、语义改写和冲突问题。问题、标注和配置在四方案对比期间保持一致。

## 收集真实证据

收到测试指令且对应实现具备后，每次运行在 `real/<caseId>/<runId>/` 保存 `request.json`、`response.json`、`trace.json` 和 `run.md`。完全离线的替身运行放在 `mock/<caseId>/<runId>/`。以上是未来产物约定，当前没有创建这些结果文件。

`run.md` 至少记录运行时间、代码 commit、实际命令、Java/框架/模型版本、快照及索引配置、环境前提、结束状态、失败或限制、人工核对意见，以及同目录证据文件。输入、输出与 Trace 应来自同一次运行。真实模型与真实数据库加故障注入的用例仍放在 `real/`，但须注明 `REAL_WITH_INJECTED_FAULT`、注入点和次数。

Trace 保留步骤、实际工具参数与返回摘要、后续动作、公开简要依据和终止状态；不保存密钥、鉴权头、上游原始错误体或模型内部思维。不得编写固定日志冒充模型运行记录。

## 收集自动化测试证据

当前 48 个测试已通过；`target/surefire-reports/` 是本地生成物，未复制到仓库。T04 的编译、测试、数据库、导入及失败路径摘要记录在[运行验证](../docs/t04-ingestion-verification.md)。这里的契约与导入验证不表示已经验证完整 Mock 问答流程。后续完整离线 Mock 应继续按 `mock/contracts/<executionId>/` 保存命令、commit、退出码和实际报告。

最终至少提交三组真实输入、实际输出和 Trace，覆盖正常、资料不足及工具失败，并用 E01/E02 等至少两条实际路径证明真实模型的动作调整。其余用例补足全部验收点。契约测试、Mock 和故障注入应分别说明边界，不能互相替代。
