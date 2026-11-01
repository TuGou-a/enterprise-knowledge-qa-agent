# 测试与评估

T09 按用户于 2026-11-01 确认的范围分阶段交付。本阶段补齐已合并模块的测试、固定资料参考标注、四方案检索评估工具和交付说明；T07 已以 `7da2897` 合入，实际问答 Mock 与后续验收采集入口已接入。具体决定与交接见 [T09 设计记录](t09-testing-delivery-design.md)。

## T09 合并后当前提交复验（2026-11-01）

用户明确授权使用项目 `.env` 中的现有凭据，将三组合成资料、固定评估问题及问答上下文发送给
SiliconFlow 和 DeepSeek，并接受可能产生的 API 费用。本轮以 `dev` 的
`9aaeaea71615664e512fecfbf1f4e169e8264ae0` 为源码基线，在临时 PostgreSQL 17／pgvector
容器中重新导入资料并完成以下复验；容器和测试应用已在运行结束后清理。

| 检查 | 实际结果 | 本次证据 |
| --- | --- | --- |
| `scripts/run-tests.py unit` | 184 个测试通过，0 失败／错误／跳过 | `examples/tests/unit/20261101T064650Z-e3c4042a938f4acfa382b324fb02321a/` |
| `scripts/run-tests.py postgres` | 189 个测试通过；Testcontainers 启动真实 pgvector | `examples/tests/postgres/20261101T064704Z-215b890de4c649acbb41f6e89c842965/` |
| `scripts/run-tests.py mock` | 8 个完整离线问答场景通过 | `examples/tests/mock/20261101T064716Z-405d95494d7849d9925e7addef4d1f24/` |
| 真实问答 | E01–E06、E08、E09 共 9 次资料阶段运行通过；E08 覆盖 normal 与 mutation | `examples/real/`、`examples/tests/qa/20261101T065134Z-968ea55c7ae1478782a5883da742dbbe/` 等 9 个运行目录 |
| 真实 HTTP E01 | HTTP `200`、`ANSWERED/EVIDENCE_SUFFICIENT`、1 条已读引用、2 条适用范围缺口 | `examples/http/E01_HTTP_REAL/9abeb766-4d7c-43ac-9b2a-96732827ed7f/` |
| 真实检索评估 | `COMPLETED`，16 题、0 失败，Rerank 回退率 0 | `examples/retrieval/real/20261101T065427Z-cf89a171-7f65-4b70-a2e1-346dc07e3a15/` |
| `mvn -DskipTests package` | 构建成功，生成可执行 JAR | `target/backend-0.0.1-SNAPSHOT.jar` |

受限沙箱中的首次 unit 和 E01 尝试分别被 Mockito JVM agent 附加及本机套接字权限拦截；E01
在测试方法开始前失败，没有连接数据库或调用模型。两次原始失败记录继续保留在
`examples/tests/unit/20261101T064555Z-27b0b515b6d4426f935f91e203c8ec52/` 和
`examples/tests/qa/20261101T064816Z-2885bfa7bf7a4a9aa5aa1ccbc2c6da80/`，成功结果来自
获得运行权限后的独立重跑。

本轮检索评估使用 normal `3ddd118c-8c15-4b70-a2d2-ae500900f962`、conflict
`dd95ed52-0804-4027-b83a-1d212aa6b597`、mutation
`6660ee85-5943-481f-b84e-1631e2a7039f`。三组快照均为 `READY`，文档／条款数量分别为
2／7、3／8、2／7，集合哈希与标注基准一致。E08 normal 和 HTTP E01 另行发布了 normal 快照
`2815792f-4dbd-47dc-98bd-c54c972d2a6e` 和
`3e4a6305-22c4-4dee-b8b2-d78e40e00125`。

| 真实问答用例 | 实际终态 | 主代理语义核对 |
| --- | --- | --- |
| E01 | `ANSWERED/EVIDENCE_SUFFICIENT` | 回答 30 个自然日，并说明差旅制度未另设期限 |
| E02 | `CONFLICT/APPLICABILITY_UNKNOWN` | 引用双方，但正文仍建议按较严格的 15 天把握，存在变相选边；该次冲突处理验收不通过 |
| E03 | `NO_EVIDENCE/OUT_OF_CORPUS` | 明确股票期权资料缺口，不生成引用 |
| E04 | `ANSWERED/EVIDENCE_SUFFICIENT` | 相似改写仍落到 D01 v1 的同一期限条款 |
| E05 | `ANSWERED/EVIDENCE_SUFFICIENT` | 依据历史正确回答差旅凭证；期限预览未读取原文的部分列入缺口 |
| E06 | `INCOMPLETE/ROUTE_FAILED` | 注入一次 Embedding 超时，保留 VECTOR 未恢复状态，未写成无资料 |
| E08 normal | `ANSWERED/EVIDENCE_SUFFICIENT` | 引用 D01 v1 的 30 天规定 |
| E08 mutation | `ANSWERED/EVIDENCE_SUFFICIENT` | 引用 D01 v2 的 20 天规定 |
| E09 | `ANSWERED/EVIDENCE_SUFFICIENT` | 注入一次 Rerank 超时，回退 RRF 并披露降级 |

四方案指标与上一轮相同：关键词 Recall@6／MRR@6 为 1.0000／0.8214，向量为
1.0000／0.9643，RRF 为 1.0000／0.9643，混合＋Rerank 为 1.0000／1.0000；混合方案的扩展后
Recall 与冲突成对召回均为 1.0000。正常组仅 7 条款、冲突组仅 8 条款，Recall@6 已饱和，
这些结果只能说明当前小样本上的表现，不能证明重排序的普遍收益。参考标注状态仍为
`REFERENCE_REVIEW_PENDING`。

E02 后续复核发现，上述运行的正确状态和双方引用没有阻止正文建议按 15 天办理，且正文对未读差旅条款作出无依据的否定结论。此次记录只证明请求完成，不能证明 R02 通过；保留原始输出，不以修改历史证据代替复验。修复后需重新采集 E02，确认双方完整引用、补查及后续观察，并核对所有展示字段均未推荐任一方。实现与待执行项见[冲突回答踩坑记录](pitfalls/T07-conflict-answer-advice.md)。

本次主代理的既有核对未识别上述 E02 风险，核对结论据此更正；这不等同于用户人工复核。R08 的真实
流式总时限／取消后迟到结果、R09 的真实并发实验仍只由确定性测试覆盖。运行记录中的
`workingTreeDirtyAtStart=true` 来自保留的自动化证据和 Mock 产物，产品源码未修改。

## 最终运行结果（2026-11-01）

最终验证以源码基线 `3cddda8` 和最终证据工作树完成，运行环境为 macOS、Java 21.0.12、Maven 3.9.9、
PostgreSQL 17／pgvector 0.8.6。真实模型为 DeepSeek `deepseek-chat`，Embedding 为
`BAAI/bge-m3`，Rerank 为 `BAAI/bge-reranker-v2-m3`。

| 检查 | 实际结果 |
| --- | --- |
| 默认 `python3 scripts/run-tests.py unit` | 通过，184 个测试，0 失败、0 错误、0 跳过 |
| `python3 scripts/run-tests.py postgres` | 通过，189 个测试；Testcontainers 启动真实 pgvector |
| 真实问答 E01/E02/E03/E04/E05/E06/E08/E09 | 8 个服务入口均完成；状态与引用见下表 |
| 真实 HTTP `POST /api/qa` E01 | HTTP `200`，`ANSWERED/EVIDENCE_SUFFICIENT`，2 条引用 |
| 真实检索评估 | `COMPLETED`，16 题、0 个失败；Rerank 回退率 0 |

| 用例 | 实际终态 | 说明 |
| --- | --- | --- |
| E01 | `ANSWERED/EVIDENCE_SUFFICIENT` | 引用 D01 v1 的 30 天期限，说明差旅制度未另设期限 |
| E02 | `CONFLICT/APPLICABILITY_UNKNOWN` | 同时引用 30 天与 15 天规定，不按日期擅选 |
| E03 | `NO_EVIDENCE/OUT_OF_CORPUS` | 完成多次检索后明确股票期权资料缺口 |
| E04 | `ANSWERED/EVIDENCE_SUFFICIENT` | 语义改写问题命中同一条款并引用已读片段 |
| E05 | `ANSWERED/EVIDENCE_SUFFICIENT` | 使用历史消歧到差旅凭证，重新读取当前快照 |
| E06 | `INCOMPLETE/ROUTE_FAILED` | 注入 1 次 Embedding 超时，未谎报无资料 |
| E08 | `ANSWERED/EVIDENCE_SUFFICIENT` | 资料替换后引用 D01 v2 的 20 天规定 |
| E09 | `ANSWERED/EVIDENCE_SUFFICIENT` | 注入 1 次 Rerank 超时，回退 RRF 并披露降级 |

检索评估的 14 个正例、2 个资料外问题和 2 个冲突对结果：

| 方案 | Recall@6 | MRR@6 | 扩展后 Recall | 冲突成对召回 |
| --- | ---: | ---: | ---: | ---: |
| 关键词 | 1.0000 | 0.8214 | 不适用 | 1.0000 |
| 向量 | 1.0000 | 0.9643 | 不适用 | 1.0000 |
| RRF 混合 | 1.0000 | 0.9643 | 1.0000 | 1.0000 |
| 混合 + Rerank | 1.0000 | 1.0000 | 1.0000 | 1.0000 |

Recall@6 在当前 7／8 条款小语料上饱和，不能据此推断普遍收益；结果文件、快照 UUID、原始召回与计量见
`examples/retrieval/real/20261101T062009Z-5222daac-179c-4a87-95e4-7b5249fa2a52/`。

真实模型对照发现 `deepseek-flash` 偶发结构化草稿或引用无效，运行时均按契约拒绝；切换
`deepseek-chat` 后完成上述 8 个用例。非法引用和枚举不会被自动修复，相关排查见
[工具上下文踩坑](pitfalls/T07-tool-result-context-bloat.md)、
[状态原因配对](pitfalls/T07-status-reason-pairing.md)和
[模型输出稳定性](pitfalls/T07-model-output-stability.md)。

历史基线 `5ff4dd0` 已完成 63 个测试，包含原有 15 个 T05 JUnit，0 失败、0 错误、0 跳过；T04／T05 已完成相应层次的受控运行，见 [T04 验证](t04-ingestion-verification.md)和 [T05 验证](t05-search-verification.md)。这些历史结果早于最终运行，最终状态以上一节和实际证据文件为准。

## 已确认的交付边界

1. 主代理在独立 worktree 开发，子代理静态审查，修复后合并 `dev` 并删除 worktree；合并后等待用户指令再运行。
2. 默认 `mvn test` 不依赖 Docker、数据库或真实模型密钥。数据库集成测试和真实模型评估通过显式 profile 启用。默认测试中的本机 HTTP 服务只验证协议。
3. 完整离线 Mock 接入 T07 的实际问答服务、AgentScope、工具包装与校验，以确定性模型／资料替身驱动，不新建临时问答编排冒充 R10。HTTP 中的 `QaService` 替身只验证 HTTP 边界。
4. 无依据作答、引用原文不符、冲突时擅选一方、把工具失败写成无资料，均阻断最终交付。不预设没有基线支持的检索、延迟或排序收益门槛。
5. 沿用 normal、conflict、mutation 三组资料，保留四方案持平或退化的结果。正常组 7 条款、冲突组 8 条款、替换组 7 条款，Recall@6 容易饱和，不据此声称普遍收益。
6. AI 辅助生成的原文参考标注与人工语义复核分开记录。实际用时、个人修改和学习经历等待用户提供事实；不得推测填写。

## 历史基线 5ff4dd0 的执行记录

| 检查 | 实际结果 |
| --- | --- |
| `mvn test` | 通过，63 个测试，0 失败、0 错误、0 跳过 |
| T05 PostgreSQL 集成 | 通过，`T05PostgresIntegrationTest` 的 2 个用例在 Docker `pgvector/pgvector:pg17` 中完成 |
| `mvn -DskipTests package` | 通过，生成可执行 JAR |
| 可执行 JAR 启动 | 未通过；当时缺少 T07 `QaService` Bean，不是测试环境问题。T07 后已合入，尚未重验 |

运行环境为 macOS、Java 21.0.12、Maven 3.9.9 和已启动的 Docker Desktop，项目按 Java 17 编译目标构建。首次执行还发现并修复了以下测试缺陷：AssertJ 断言链错误、Testcontainers 动态属性早于容器启动，以及 Maven/Surefire 测试 JVM 的 SOCKS 代理阻断本地 JDBC。修复均经子代理静态审查后合并，记录见 [Testcontainers 启动时序](pitfalls/T09-testcontainers-startup.md)和 [本地 JDBC SOCKS 代理](pitfalls/T09-local-jdbc-socks-proxy.md)。

以上记录属于并行任务在本阶段新增代码和 T07 合并前的实际运行，不能据此声称最新代码已通过。

## 测试代码与运行入口

| 资产 | 断言或职责 | 当前证据边界 |
| --- | --- | --- |
| `contract/*ContractTest`、`SiliconFlowInterruptionTest` | 向量、配置、JSON、读取结果互斥、稳定排序、模型中断。 | 已包含在最终默认测试的 184 项中并重新通过。 |
| `SiliconFlowProtocolTest` | 请求字段、批序、结果数量／下标、向量、重排分数、计量、HTTP 错误和脱敏。 | 最终默认测试通过；仍使用本机受控 HTTP，不证明服务商持续可用性。 |
| `PgKnowledgeStoreBoundaryTest` | 不足预算不启动事务、空关键词和跨快照边界。 | 最终默认测试与 PostgreSQL 集成测试通过。 |
| `CorpusIngestionTest` | 严格 JSON、资料与条款身份、UTF-16 偏移／哈希、分词资源、失败不发布、写入与发布顺序。 | 最终默认测试与 PostgreSQL 集成测试通过；数据库／模型替身边界不变。 |
| `HybridSearchServiceTest`、`KnowledgeEvidenceReaderTest` | 融合、排序回退、主题截断、原文读取和证据登记。 | 最终默认测试通过。 |
| `AnswerValidationTest` | 已读引用、全量拒绝无效引用、失败优先级、冲突例外、无依据门槛、UTF-16 定位和 Trace 快照。 | 最终默认测试通过；机械校验不证明语义支持性。 |
| `QaHttpTest` | 严格 JSON、历史、全部长度边界、五类任务状态、全部依赖错误码、空结果、协议头和异步超时分派。 | 最终默认测试通过；另有真实 HTTP E01 证据。超时用例触发 Servlet 超时事件，不是实测等待 125 秒。 |
| `RetrievalEvaluationTest` | 指标分母、排名、去重、冲突成对召回、空相关集、融合／排序与生产实现对齐、回退。 | 最终默认测试通过；确定性回归不替代真实检索成绩。 |
| `T05PostgresIntegrationTest` | 真实 schema／Mapper／分词／导入／召回／读取；补充发布失败、批次回滚、READY 不可写和替换快照。 | `postgres` profile 通过，真实 Docker pgvector，模型为替身。 |
| `RealRetrievalEvaluationTest` | 指定 READY 快照上的四方案实际比较与结果导出。 | 最终真实评估通过，16 题 0 失败，结果见 `examples/retrieval/real/`。 |
| `RequestStateTest`、`AgentQaServiceBoundaryTest` | 请求级状态、预算、同查询恢复、资料身份、订阅隔离及最终截止原因。 | 最终默认测试通过；总截止状态使用明确置位验证。 |
| `QaOfflineMockTest` | 实际问答服务与 AgentScope 循环，含正常、冲突、资料外、路由恢复、同轮引用拒绝、工具／轮次上限及模型失败。 | 最终默认测试与 PostgreSQL profile 均通过，证据见 `examples/mock/`。 |
| `FaultInjectionTest`、`RealQaEvaluationTest` | 故障固定一次后委托、真实问答输入／响应／Trace 采集。 | `FaultInjectionTest` 随默认测试通过；`RealQaEvaluationTest` 带 `real` 标签，共 10 次独立运行、覆盖 8 个用例（E01 运行 3 次），不计入默认 184 项。 |

获得测试指令后，在 Java 17 和 Maven 环境执行：

```bash
# 默认无数据库、无 Docker、无真实模型密钥
mvn test

# 默认测试加 Docker PostgreSQL 集成回归；需要 Docker 和 pgvector/pgvector:pg17
mvn -Ppostgres-it test
```

仓库没有 Maven Wrapper。首次下载依赖或镜像可能需要网络；`-o` 只控制 Maven 联网，不会消除 Docker 或真实模型依赖。

需要保存可交付证据时，使用下列等价入口，需要 Python 3：

```bash
python3 scripts/run-tests.py unit
python3 scripts/run-tests.py postgres
python3 scripts/run-tests.py mock
```

脚本记录实际 commit、开始前是否存在未提交改动、命令、开始／结束时间、Maven 退出码与报告计数，日志和报告放在 `examples/tests/<mode>/<runId>/`。每次为 Surefire 指定全新报告目录，避免把旧报告混入本次结果。最终 unit、postgres、qa 和 retrieval 运行记录已提交。

完整离线流程也可单独执行 `mvn -Dtest=QaOfflineMockTest test`。实际服务生成的每次响应与 Trace 会保存到 `examples/mock/<caseId>/<runId>/`，标记 `MOCK`。模型输出预设动作仅用于验证框架接线和异常路径，不能用这些动作声称真实模型具备动态决策能力。

交付采集统一使用脚本：`mock` 仅运行离线问答；`qa --case E01 --snapshot '<实际ID>'` 运行选定真实问答；`retrieval --snapshots 'normal=<id>,conflict=<id>,mutation=<id>'` 运行四方案对比。E08 可附加 `--corpus normal|mutation`。脚本将外层 `run.json` 的路径传给测试 JVM，问答和检索产物反向链接同次 Maven 的实际命令、起止时间与退出码。直接 Maven 调用没有该记录时会注明证据不完整，不能视为最终交付证据。

## 参考标注与快照绑定

[retrieval-labels.json](../examples/retrieval-labels.json) 含 16 个固定问题，覆盖明确术语、语义改写、必要条件、交通票据遗失、冲突、资料替换和资料外问题。`annotationSource=AI_ASSISTED_SOURCE_REVIEW`，`annotationStatus=REFERENCE_REVIEW_PENDING`；这是依照原文形成的参考标注，未经用户人工复核，不作为已确认的质量结论。

每份资料固定源文件 SHA-256 与 T04 `contentHash`。源文件摘要覆盖元数据和主题标注；T04 集合哈希只覆盖排序后的文档 ID、版本、原文哈希，不能单独证明元数据未改变。相关条款使用 `corpusId + documentId + version + chunkId`，运行时绑定实际 `snapshotId`；不能把一次导入的随机 UUID 当成长期标注身份。

加载器拒绝空模板、未填列表、重复问题／条款和不属于相关集合的冲突对，并校验源文件摘要。`null` 表示未填写，不能计分；`[]` 只用于已确认为资料外的问题。条款存在性和集合哈希另由实际解析器回归检查。任何机械检查都不能替代人工判断条款是否相关、参考答案是否完整。

E01/E04 绑定 normal，E02 绑定 conflict，E08 绑定 mutation；上下文 E05 的历史消歧仍由 T07 完整问答验收，不把原始省略问句当作已经改写的检索查询。

## 四方案比较与指标

四种方案为关键词、向量、RRF 混合、混合加重排序。每道题只执行一次关键词和一次向量召回，再共用原始结果计算排名；RRF 和重排序共用同一融合池、候选数与并列规则。单路按原始相关性顺序报告主候选，不额外创造主题扩展行为；混合方案按 T05 的融合池主题种子扩展，排除各自已返回的主候选。

- **Recall@6：**前 6 条去重主候选命中的相关条款数／全部已标注相关条款数。资料外问题不进入正例分母。
- **MRR@6：**前 6 条首个相关条款排名的倒数，无命中为 0；对有相关条款的问题取均值。
- **扩展后 Recall@N：**主候选与主题扩展去重后的相关条款命中比例，同时记录实际返回数量 N 和截断。扩展集合没有统一相关性排名，不计算扩展 MRR；单路扩展指标为不适用。
- **冲突双方成对召回率：**双方都出现的冲突对数／全部已标注冲突对数，主候选和扩展分别报告；无冲突对时不适用。当前每个冲突问题只标注一对，汇总均值与成对计数口径一致。
- **重排序回退率：**仅以非空融合池、实际需要排序的调用为分母；空池跳过不算失败。返回警告、回退模式和分母，没有调用时为不适用。
- **耗时与计量：**记录关键词、向量、单题及排序耗时、Embedding 与 Rerank 的响应标识和服务返回的计量。排序计时包括加载候选全文，不能直接当作模型 HTTP 耗时。
- **失败：**必要检索失败的问题不生成排名分数，保存失败类别和总失败数；成功子集均值必须结合失败计数解读。重排序失败则按实际 RRF 回退结果计分并披露。

汇总同时记录各指标的有效问题数、成功问题中的冲突对数和因资料外排除的数量，避免只展示均值而隐藏分母。运行元数据记录生成输出目录前的工作树是否存在未提交改动；该标识仅说明可复现性边界，不阻止探索性评估。

真实检索评估前，按[资料说明](../data/README.md)在目标库导入三组资料并记录实际快照 ID，再获得运行指令后执行：

```bash
mvn -Preal-evaluation -Dtest=RealRetrievalEvaluationTest \
  -Devaluation.snapshots='normal=<正常快照ID>,conflict=<冲突快照ID>,mutation=<替换快照ID>' test
```

共享配置仍要求数据库与两家模型的必填变量；该评估只调用硅基流动 Embedding／Rerank，不调用 DeepSeek。入口不导入或修改资料，检索与真实问答共用 `CorpusEvaluationBinding`，校验 READY、集合哈希、IndexProfile 和实际每条原文／元数据；适用范围、替代关系、主题及偏移不能只靠正文哈希判断。实际运行后才会生成 `examples/retrieval/real/<runId>/results.json` 和 `run.md`，包含输入、快照、原始召回、排名、指标、计量、失败、commit 与参考标注状态。源码中的默认测试不运行此入口。

## R01–R10 与剩余验收

| 需求 | 用例 | 当前交付与剩余证据 |
| --- | --- | --- |
| R01 明确答案 | E01 | 真实运行 `ANSWERED`，3 轮模型决策、2 次检索、1 次读取；人工语义复核仍待填写。 |
| R02 文档冲突 | E02 | 真实运行 `CONFLICT`，读取双方并补查适用关系，引用 3 条已读原文。 |
| R03 资料外问题 | E03 | 真实运行 `NO_EVIDENCE`，多次检索后说明资料范围与缺口。 |
| R04 相似改写 | E04 | 真实运行 `ANSWERED`，与 E01 使用同一条期限依据。 |
| R05 上下文追问 | E05 | 真实运行 `ANSWERED`，利用历史指向差旅凭证并重新读取当前快照。 |
| R06 工具失败 | E06、E09 | E06 注入 Embedding 超时后返回 `ROUTE_FAILED`；E09 注入 Rerank 超时后回退 RRF、披露降级并正常回答。 |
| R07 动态调整 | E01、E02 | E01 和 E02 的真实 Trace 均显示检索/读取后继续模型推理，不是固定调用脚本。 |
| R08 执行上限 | E07 | 实际 Mock 覆盖工具与轮次上限并得到 `BUDGET_EXHAUSTED`／`MAX_ITERATIONS`；真实流式总时限和取消后迟到结果仍有测试缺口。 |
| R09 资料替换 | E08 | normal 为 30 天，mutation 为 20 天；新请求重新读取并引用 D01 v2。同一进行中请求的并发实时验证仍由单元状态回归覆盖，未做真实并发压测。 |
| R10 离线入口 | E10 | 默认测试和 PostgreSQL profile 均执行实际服务、AgentScope、工具与校验；证据标记为 `MOCK`。 |

最终至少提交三组真实输入、实际输出及运行记录，覆盖正常、资料不足与工具失败，并覆盖全部验收点。答案支持性、冲突解释、缺口与限制逐项人工核对，记录核对者和时间。引用有效不等于答案受到支持，检索高分也不是回答置信度。

真实问答采集入口使用 T07 实际服务，保留真实模型与 PostgreSQL。E06 只在测试配置包装 `EmbeddingClient` 首次调用注入一次 TIMEOUT；相同查询必要路由恢复并补足查证后才解除限制，未恢复时不能返回 `NO_EVIDENCE`。E09 只在首次 Rerank 注入一次 TIMEOUT，后续委托真实客户端；必要查证完整且依据充分时允许披露降级后回答。包装器不能通过生产 HTTP 参数启用，单次注入与后续委托另有单元测试。

```bash
mvn -Preal-evaluation -Dtest=RealQaEvaluationTest \
  -Devaluation.case=E01 -Devaluation.snapshot='<当前最新normal快照ID>' test
```

E02 要求最新 conflict 快照，E01/E03/E04/E05/E06/E09 使用 normal；E08 先以 `-Devaluation.corpus=normal` 运行并保存，再发布 mutation，以 `-Devaluation.corpus=mutation` 和新快照重新运行。入口支持上述用例，真实输入／响应／Trace 和运行元数据在 `examples/real/<caseId>/<runId>/` 实际生成。注入用例注明 `REAL_WITH_INJECTED_FAULT` 及实际次数；没有触发预期故障时会在保存实际结果后报错。人工核对者、时间、答案支持性及路径是否符合要求仍需补充，不以执行结束自动判定通过。

## 审查与历史

第一阶段 T09 实现及修复见 `6e04317`、`27db0bb` 和 Git 历史。Bacon／McClintock 分别完成规范与需求静态审查及定向复核，无剩余阻断项；其后 48 个测试有历史通过记录。T04／T05 的临时受控验证见前述文档，临时环境和原始日志已清理；不能表述为仓库内仍保留可执行的原始产物。

本阶段的实现、审查问题及合并记录追加到 [T09 设计记录](t09-testing-delivery-design.md)。最终代码已在 `3cddda8` 完成自动化、真实数据库、真实模型、HTTP 和检索评估；人工语义复核及用户个人事实仍不能由静态审查或自动测试代替。
