# 验收输入与运行证据

当前目录包含计划输入、参考标注、完整真实问答、真实 HTTP 调用、检索评估结果和自动化测试运行记录。历史基线 `5ff4dd0` 的 63 项测试与 T04／T05 记录继续保留；最终验证见[评估文档](../docs/03-evaluation.md)。

## 当前提交复验索引

2026-11-01 的 T09 合并后复验以源码基线 `9aaeaea` 执行。运行使用真实 PostgreSQL/pgvector、
DeepSeek `deepseek-chat` 和硅基流动两个模型，临时数据库与测试应用已清理。

| 检查或用例 | 本次证据 |
| --- | --- |
| 默认单元测试 `184/184` | `tests/unit/20261101T064650Z-e3c4042a938f4acfa382b324fb02321a/` |
| PostgreSQL 集成测试 `189/189` | `tests/postgres/20261101T064704Z-215b890de4c649acbb41f6e89c842965/` |
| 离线问答 Mock `8/8` | `tests/mock/20261101T064716Z-405d95494d7849d9925e7addef4d1f24/` |
| E01 | `real/E01/1736e41f-c8e1-4699-84b5-c5f2ee06b5af/` |
| E02 | `real/E02/ea61d620-9563-4a43-b3e7-e481eca8f6f1/` |
| E03 | `real/E03/da8ccae4-667f-46ca-820e-c87a9513ffc6/` |
| E04 | `real/E04/89e1be8f-7920-49b7-9e82-5305866476d1/` |
| E05 | `real/E05/ed9913d5-f81b-4b68-8683-73dc3c4278c7/` |
| E06 | `real/E06/0fb3acaa-0cde-4f54-823b-a40f46d3e125/` |
| E08 normal | `real/E08/c953d703-729f-4a06-8268-2035b571a2e7/` |
| E08 mutation | `real/E08/04dcfff9-657e-4d9d-9d61-93408db47f60/` |
| E09 | `real/E09/53bac830-a31a-409a-ab12-12448876b907/` |
| 真实 HTTP E01 | `http/E01_HTTP_REAL/9abeb766-4d7c-43ac-9b2a-96732827ed7f/` |
| 16 题检索评估 | `retrieval/real/20261101T065427Z-cf89a171-7f65-4b70-a2e1-346dc07e3a15/` |

参考标注仍为 `REFERENCE_REVIEW_PENDING`。主代理已核对本轮回答与资料、状态、引用和故障披露，
但不能替代用户人工复核；7／8 条款小样本也无法证明重排序的普遍收益。

E02 复核更正：上述 `ea61d620-9563-4a43-b3e7-e481eca8f6f1` 虽返回 `CONFLICT`，正文仍建议按较严格的 15 天把握，并对未读差旅条款作出无依据的否定结论，不能认定冲突处理验收通过。原始证据保持原样。本次修复与待执行检查见[冲突回答踩坑记录](../docs/pitfalls/T07-conflict-answer-advice.md)；修复后的结果待授权测试后另行采集。

`tests/unit/20261101T064555Z-27b0b515b6d4426f935f91e203c8ec52/` 和
`tests/qa/20261101T064816Z-2885bfa7bf7a4a9aa5aa1ccbc2c6da80/` 是受限沙箱造成的失败保留：
错误分别发生在 Mockito JVM agent 和本机套接字权限层。上表成功记录均由独立重跑生成；E01
沙箱失败没有启动数据库连接或模型请求。

## 历史最终证据索引

最终验证时间为 2026-11-01，源码基线为 `3cddda8`，运行使用最终证据工作树和 DeepSeek `deepseek-chat`。

| 用例 | 实际结果 | 证据 |
| --- | --- | --- |
| E01 | `ANSWERED/EVIDENCE_SUFFICIENT`，连续 3 次 | `real/E01/c0e3035a-66b2-4b4e-9a4d-75c0dbfd4b1d/`、`real/E01/aafb2d25-e740-4453-9fc6-e33835950aee/`、`real/E01/f19441e2-7fce-4c7a-aaed-82043a30c303/` |
| E02 | `CONFLICT/APPLICABILITY_UNKNOWN` | `real/E02/328e269f-5306-4ac0-96c7-17025ca28c4f/` |
| E03 | `NO_EVIDENCE/OUT_OF_CORPUS` | `real/E03/57ed4ba0-7460-451f-b4e8-623e60e44471/` |
| E04 | `ANSWERED/EVIDENCE_SUFFICIENT` | `real/E04/094b311b-38a0-46a3-beb2-8a2e97fff758/` |
| E05 | `ANSWERED/EVIDENCE_SUFFICIENT` | `real/E05/d8763627-d4d0-46ae-9379-d1c56a88ed2b/` |
| E06 | `INCOMPLETE/ROUTE_FAILED`，注入 1 次 Embedding 超时 | `real/E06/5c42965e-8e90-4ac4-b9c4-7fdbbe6b98ce/` |
| E08 | `ANSWERED/EVIDENCE_SUFFICIENT`，引用 20 天的新版本 | `real/E08/25874ddd-8678-4497-97e1-b5cbcc613516/` |
| E09 | `ANSWERED/EVIDENCE_SUFFICIENT`，注入 1 次 Rerank 超时并披露回退 | `real/E09/b862ca60-d868-467b-a3fc-321fa7ae2fd0/` |
| 真实 HTTP E01 | HTTP `200`，带原始报文与服务端日志 | `http/E01_HTTP_REAL/f18bc320-5fbd-4080-8cea-75b7e9008d21/` |
| 16 题检索评估 | `COMPLETED`，0 个失败问题 | `retrieval/real/20261101T062009Z-5222daac-179c-4a87-95e4-7b5249fa2a52/` |
| 离线 Mock | 正常、冲突、资料外、恢复、未观察引用、工具/轮次上限、模型失败 | `mock/E02/`、`mock/E03/`、`mock/E06/`、`mock/E07/`、`mock/E10/` |

自动化测试证据位于 `tests/unit/`、`tests/postgres/`、`tests/qa/` 和 `tests/retrieval/`。最终
默认测试为 `184/184`，PostgreSQL 集成测试为 `189/189`。测试目录中的 `run.json` 记录 commit、
命令、起止时间、退出码和报告计数。

## 输入与标注

- [cases.json](cases.json)：E01–E10 的需求映射、请求、资料前提和预期检查，不是运行结果。
- `requests/E01.json` 至 `requests/E10.json`：可直接作为 `POST /api/qa` 请求体的计划输入，不含预期答案或测试开关。
- [retrieval-labels.json](retrieval-labels.json)：16 个固定检索问题、相关条款、冲突对和参考答案，绑定三组源文件摘要与集合哈希。
- [retrieval-labels.template.json](retrieval-labels.template.json)：新增标注用的未填写模板，不能直接计分。

参考标注来源为 `AI_ASSISTED_SOURCE_REVIEW`，状态为 `REFERENCE_REVIEW_PENDING`。它们经过原文逐条核对，但尚未由用户人工复核；条款存在性测试不能证明语义标注正确。修改资料、主题或元数据后，需要重新标注并更新源文件摘要，不能只改哈希绕过复核。

相关条款以 `corpusId + documentId + version + chunkId` 标识，在执行时绑定实际 `snapshotId`。同一资料每次导入生成新的 UUID，因此不预填随机快照。`null` 是未标注，空数组只表示已确认无相关条款；资料外问题不进入正例 Recall／MRR 分母。

## HTTP 调用

启动完整应用时设置 `SERVER_PORT=18080`（默认端口为 `8080`），然后在项目根目录调用：

```bash
curl --fail-with-body -sS http://127.0.0.1:18080/api/qa \
  -H 'Content-Type: application/json' \
  --data-binary @examples/requests/E01.json
```

先按[资料导入说明](../data/README.md)发布 normal 再执行 E01/E04/E05；发布 conflict 后执行 E02。E08 需先记录 normal 下的实际结果，再发布 mutation 并重新请求。HTTP 请求不接受用例 ID 或任意快照选择参数，不能在发布全部三组后假定系统会自动按用例选资料。

## 自动化测试证据

最终执行入口为：

```bash
python3 scripts/run-tests.py unit
python3 scripts/run-tests.py postgres
python3 scripts/run-tests.py mock
python3 scripts/run-tests.py qa --case E01 --snapshot '<当前最新normal快照ID>'
python3 scripts/run-tests.py retrieval --snapshots 'normal=<id>,conflict=<id>,mutation=<id>'
```

需要 Python 3、Java 和 Maven；`postgres` 另需 Docker 与 pgvector 镜像，`qa`／`retrieval` 需要实际资料与模型环境。输出位于从项目根目录计算的 `examples/tests/<mode>/<runId>/`，包含 `run.json`、`console.log` 和本次独立的 `reports/`。unit 为隔离回归，postgres 为真实数据库加模型替身，mock 为实际问答服务加确定性替身，qa／retrieval 才会调用各自所需的真实模型。

每次记录实际命令、commit、运行开始前的未提交状态、时间、Maven 退出码和报告计数。没有报告时数量为 `null`，启动失败、中断或报告解析失败需保留；不复制历史报告制造本次成绩。

## 真实检索评估

评估入口与配置见[评估文档](../docs/03-evaluation.md)。显式指定三组实际 READY 快照后，入口只读取已有资料并调用硅基流动，不运行 DeepSeek 或问答 Agent。每道题共用一次原始召回生成四方案；单路不增加主题扩展，混合方案保持 T05 行为。

实际运行生成 `retrieval/real/<runId>/results.json` 和 `run.md`。结果包括问题、标注、实际快照、索引配置、原始召回、融合池、排名、指标、耗时、计量和失败；不产生“项目通过”的结论。7／8 条款的小型合成资料容易让 Recall@6 饱和，必须报告持平、退化和局限。

## 完整问答证据

实际服务的离线入口为 `mvn -Dtest=QaOfflineMockTest test`，真实问答入口为 `mvn -Preal-evaluation -Dtest=RealQaEvaluationTest -Devaluation.case=E01 -Devaluation.snapshot='<实际最新normal快照ID>' test`。

离线用例将实际服务及框架生成的响应与 Trace 放在 `mock/<caseId>/<runId>/`，标明确定性模型／资料替身。真实问答在 `real/<caseId>/<runId>/` 保存 `request.json`、`response.json`、`trace.json`、`run.json` 和 `run.md`。

真实入口支持 E01/E02/E03/E04/E05/E06/E08/E09，E02 使用最新 conflict，其他普通用例使用 normal。E08 分别在 normal、mutation 发布后运行，设置 `evaluation.corpus` 和实际快照。E06/E09 的测试配置只在对应客户端首次调用注入一次 TIMEOUT，并记录实际注入次数，后续调用委托真实客户端。此入口直接调用实际服务，不代替 HTTP 端到端验证。

`run.json` 显式记录实际索引配置、模型版本、commit、工作树状态和执行前提；`run.md` 记录时间、Java／框架、快照及终态，并链接元数据和同次外层脚本的实际命令、起止时间与退出码。人工核对者、时间与意见明确标为待填写，不预判通过。输入、输出与 Trace 必须属于同次运行。直接 Maven 调用缺少外层记录时会标明证据不完整。真实模型与 PostgreSQL 加故障注入的用例仍放在 `real/`，注明 `REAL_WITH_INJECTED_FAULT`、注入点和次数。

Trace 记录实际步骤、工具参数、返回摘要、后续动作、公开简要依据与终止状态；不保存密钥、鉴权头、上游原始错误体或模型内部思维。至少三组真实材料覆盖正常、资料不足、工具失败，并以至少两条真实路径证明模型观察工具结果后改变动作。检索结果、HTTP 替身和机械引用校验不能替代这些证据。
