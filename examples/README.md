# 验收输入与运行证据

当前目录包含计划请求和参考标注，没有完整问答的实际回答、真实 Trace 或检索成绩。48 个既有测试与 T04／T05 的历史记录见[评估文档](../docs/03-evaluation.md)，本阶段新增／修改测试尚未执行。

## 已提供材料

- [cases.json](cases.json)：E01–E10 的需求映射、请求、资料前提和预期检查，不是运行结果。
- `requests/E01.json` 至 `requests/E10.json`：可直接作为 `POST /api/qa` 请求体的计划输入，不含预期答案或测试开关。
- [retrieval-labels.json](retrieval-labels.json)：16 个固定检索问题、相关条款、冲突对和参考答案，绑定三组源文件摘要与集合哈希。
- [retrieval-labels.template.json](retrieval-labels.template.json)：新增标注用的未填写模板，不能直接计分。

参考标注来源为 `AI_ASSISTED_SOURCE_REVIEW`，状态为 `REFERENCE_REVIEW_PENDING`。它们经过原文逐条核对，但尚未由用户人工复核；条款存在性测试不能证明语义标注正确。修改资料、主题或元数据后，需要重新标注并更新源文件摘要，不能只改哈希绕过复核。

相关条款以 `corpusId + documentId + version + chunkId` 标识，在执行时绑定实际 `snapshotId`。同一资料每次导入生成新的 UUID，因此不预填随机快照。`null` 是未标注，空数组只表示已确认无相关条款；资料外问题不进入正例 Recall／MRR 分母。

## HTTP 调用

T07 合并、应用具备运行条件且收到测试指令后，在项目根目录调用：

```bash
curl --fail-with-body -sS http://localhost:8080/api/qa \
  -H 'Content-Type: application/json' \
  --data-binary @examples/requests/E01.json
```

先按[资料导入说明](../data/README.md)发布 normal 再执行 E01/E04/E05；发布 conflict 后执行 E02。E08 需先记录 normal 下的实际结果，再发布 mutation 并重新请求。HTTP 请求不接受用例 ID 或任意快照选择参数，不能在发布全部三组后假定系统会自动按用例选资料。

## 自动化测试证据

合并后获得测试指令，再执行：

```bash
python3 scripts/run-tests.py unit
python3 scripts/run-tests.py postgres
```

需要 Python 3、Java 和 Maven；`postgres` 另需 Docker 与 pgvector 镜像。输出位于 `tests/<mode>/<runId>/`，包含 `run.json`、`console.log` 和本次独立的 `reports/`。unit 是隔离单元测试，postgres 是真实数据库加模型替身；均不冒充完整问答 Mock 或真实模型验收。

每次记录实际命令、commit、运行开始前的未提交状态、时间、Maven 退出码和报告计数。没有报告时数量为 `null`，启动失败、中断或报告解析失败需保留；不复制历史报告制造本次成绩。这些目录只由实际运行生成，当前没有预填。

## 真实检索评估

评估入口与配置见[评估文档](../docs/03-evaluation.md)。显式指定三组实际 READY 快照后，入口只读取已有资料并调用硅基流动，不运行 DeepSeek 或问答 Agent。每道题共用一次原始召回生成四方案；单路不增加主题扩展，混合方案保持 T05 行为。

运行后才生成 `retrieval/real/<runId>/results.json` 和 `run.md`。结果包括问题、标注、实际快照、索引配置、原始召回、融合池、排名、指标、耗时、计量和失败；不产生“项目通过”的结论。7／8 条款的小型合成资料容易让 Recall@6 饱和，必须报告持平、退化和局限。

## 完整问答证据

T07 具备且获得运行指令后，每次真实问答在 `real/<caseId>/<runId>/` 保存 `request.json`、`response.json`、`trace.json` 和 `run.md`；完全离线问答替身运行放在 `mock/<caseId>/<runId>/`。当前不创建这些实际产物。

`run.md` 记录时间、实际 commit、命令、Java／框架／模型版本、资料快照与索引配置、前置条件、结束状态、失败或限制、人工核对者及意见。输入、输出与 Trace 必须属于同次运行。真实模型与 PostgreSQL 加故障注入的用例仍放在 `real/`，注明 `REAL_WITH_INJECTED_FAULT`、注入点和次数。

Trace 记录实际步骤、工具参数、返回摘要、后续动作、公开简要依据与终止状态；不保存密钥、鉴权头、上游原始错误体或模型内部思维。至少三组真实材料覆盖正常、资料不足、工具失败，并以至少两条真实路径证明模型观察工具结果后改变动作。检索结果、HTTP 替身和机械引用校验不能替代这些证据。
