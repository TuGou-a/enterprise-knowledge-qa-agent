# T01 配置与跨任务契约

本文冻结 T02 至 T09 使用的共享类型、方法签名和边界语义。源文件位于 `src/main/java/com/mango/backend/contract/`；下文以相对包名引用，嵌套 record 使用 `外层类.类型`。本任务只提供真实的配置绑定与值约束、共享值类型和接口声明，不提供虚假的客户端、数据库或问答服务 Bean。接口的具体实现由后续任务交付，不代表已完成端到端集成。

## 依赖、配置与启动边界

沿用 Java 17、Spring Boot 3.5.10，声明 AgentScope core 与 OpenAI 模型扩展 2.0.4、MyBatis starter 3.0.5、jieba 1.0.2，以及由 Boot 管理的 PostgreSQL 驱动、Reactor 和 Jakarta Validation 实现。没有引入 AgentScope Boot starter，没有覆盖 Reactor 或 Jackson BOM。已有的基线差异见 [框架接入核查](framework-verification.md)；依赖解析、编译、启动及真实工具兼容性尚未验证。

`BackendApplication` 扫描 `contract.config`。`AgentProperties` 绑定 `knowledge-agent`，`DatabaseProperties` 绑定 `spring.datasource`，使用 `@Validated` 及嵌套 `@Valid` 做启动校验。真实模式缺少任一模型密钥或数据库密码时启动失败；数据库用户名有默认值，显式配置为空也会校验失败；数值必须满足声明的范围和组合约束。启动校验不调用真实模型，不证明密钥有效或服务可用。配置对象的 `toString()` 隐藏密钥及数据库连接信息；异常、HTTP 响应、提示词和 Trace 不得输出配置对象的原始字段或外部原始错误体。

当前默认只有真实配置。用户在 T09 范围讨论中确认：离线入口放在 `src/test/`，通过 Maven 测试运行，不新增 Mock HTTP 服务。本轮只准备契约测试与隔离配置绑定测试，不扫描真实应用、不创建数据源，也不用占位密钥启动真实模式；完整 Mock 问答待 T07 等业务实现合并后以模型和工具替身补齐，R10 仍待完成。此决定替代先前 Mock profile 的入口设想，见 [评估文档](03-evaluation.md)。根目录 `.env` 不会自动加载。

### 配置清单

所有环境变量、实际绑定键与初值均以 `application.properties` 和 `.env.example` 为准。下表中 `limits.*`、`timeouts.*` 等均位于 `knowledge-agent` 下；仅 PostgreSQL/Hikari/MyBatis 使用其标准前缀。

| 环境变量 | 绑定键 | 初值与语义 |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | `deepseek.api-key` | 必填，无真实默认值。 |
| `DEEPSEEK_BASE_URL` / `DEEPSEEK_MODEL` | `deepseek.base-url` / `deepseek.model` | `https://api.deepseek.com` / `deepseek-flash`；服务可用性待验证。 |
| `DEEPSEEK_CONTEXT_WINDOW` / `DEEPSEEK_MAX_OUTPUT_TOKENS` | `deepseek.context-window` / `deepseek.max-output-tokens` | 32768 / 2048；应用采用的保守窗口与输出初值，不宣称是服务商最大窗口。输出上限须小于窗口。 |
| `SILICONFLOW_API_KEY` / `SILICONFLOW_BASE_URL` | `siliconflow.api-key` / `siliconflow.base-url` | 必填密钥 / `https://api.siliconflow.cn/v1`。基础地址限 HTTP(S)，不带认证信息、查询串或片段。 |
| `EMBEDDING_MODEL` / `RERANK_MODEL` | `siliconflow.embedding-model` / `siliconflow.rerank-model` | `BAAI/bge-m3` / `BAAI/bge-reranker-v2-m3`。向量固定为 1024 维，不支持靠配置修改数据库维度。 |
| `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | `spring.datasource.url` / `username` / `password` | 本地 `knowledge_agent` 数据库、同名用户、无密码默认值；密码必填。 |
| `DB_CONNECTION_TIMEOUT_MS` | `timeouts.connection-ms`、`spring.datasource.hikari.connection-timeout` | 2000 毫秒，至少 250 毫秒且不大于请求总超时。 |
| `QA_REQUEST_TIMEOUT_MS` / `MODEL_TIMEOUT_MS` / `TOOL_TIMEOUT_MS` | `timeouts.request-ms` / `model-ms` / `tool-ms` | 120000 / 60000 / 30000 毫秒。 |
| `EMBEDDING_TIMEOUT_MS` / `RERANK_TIMEOUT_MS` / `DATABASE_TIMEOUT_MS` | `timeouts.embedding-ms` / `rerank-ms` / `database-ms` | 15000 / 10000 / 5000 毫秒；各单次超时均不大于总超时。 |
| `RETRIEVAL_ROUTE_LIMIT` / `RETRIEVAL_POOL_LIMIT` | `retrieval.route-limit` / `candidate-pool-limit` | 各路最多 20 / 融合池最多 20。候选池不超过两路容量之和。 |
| `RETRIEVAL_PRIMARY_LIMIT` / `RETRIEVAL_RELATED_LIMIT` | `retrieval.primary-limit` / `related-limit` | 主候选最多 6 / 独立主题扩展最多 12；主候选不大于候选池。 |
| `RETRIEVAL_RRF_K` / `RETRIEVAL_PREVIEW_CHARACTERS` | `retrieval.rrf-k` / `preview-characters` | 60 / 每条预览最多 300 个 UTF-16 字符。 |
| `QA_QUESTION_CHARACTERS` / `QA_HISTORY_MESSAGES` | `limits.question-characters` / `history-messages` | 当前问题最多 2000 字符 / 历史最多 20 条。 |
| `QA_HISTORY_MESSAGE_CHARACTERS` / `QA_HISTORY_CHARACTERS` | `limits.history-message-characters` / `history-characters` | 每条历史最多 4000 / 历史内容累计最多 12000 字符。 |
| `QA_QUERY_CHARACTERS` / `QA_MODEL_INPUT_CHARACTERS` | `limits.query-characters` / `model-input-characters` | 每次检索词最多 512 / 每次完整模型输入最多 24000 字符。 |
| `QA_READ_CHARACTERS` / `QA_TOOL_TEXT_CHARACTERS` | `limits.read-characters` / `tool-text-characters` | 每次运行累计返回原文最多 20000 / 累计工具文本最多 24000 字符。 |
| `EMBEDDING_BATCH_SIZE` / `EMBEDDING_TEXT_CHARACTERS` / `RERANK_CHARACTERS` | `limits.embedding-batch-size` / `embedding-text-characters` / `rerank-characters` | 每批最多 32 文本 / 单文本最多 4000 字符 / 重排序候选原文累计最多 64000 字符。 |
| `QA_REACT_ITERATIONS` / `QA_TOOL_CALLS` | `limits.react-iterations` / `tool-calls` | 6 次模型 ReAct 轮次 / 12 次已获执行许可的业务工具调用，独立计量。 |
| `INDEX_PREPROCESSING_VERSION` | `index.preprocessing-version` | `source-preserving-v1`。 |
| `JIEBA_TOKENIZER_VERSION` / `JIEBA_DICTIONARY_VERSION` / `JIEBA_STOP_WORDS_VERSION` | `index.tokenizer-version` / `dictionary-version` / `stop-words-version` | `jieba-analysis-1.0.2` / `builtin-v1` / `none-v1`。 |
| `JIEBA_DICTIONARY_LOCATION` / `JIEBA_STOP_WORDS_LOCATION` | `index.dictionary-location` / `stop-words-location` | 空表示内置词典、不启用额外停用词。T04 加载并校验所指定资源。 |

固定默认项：`spring.sql.init.mode=never`，由 T03/T04 交付明确的建库和导入入口；Mapper XML 为 `classpath*:mapper/knowledge/**/*.xml`，开启下划线到驼峰映射。JDBC 连接/读取兜底为 2/5 秒，MyBatis 默认语句超时 5 秒；T03 仍必须按传入预算收紧单次操作。JDBC 只支持整秒时向下取整，不足一秒不开始新查询。Reactor 取消不保证底层阻塞 I/O 立即结束，此项需授权后实际验证。

所有数值是待验证的初值。字符上限采用 Java `String.length()`，不能解释为 token 上限；T07 还需遵守框架窗口和输出 token 限制。历史超限直接拒绝，不静默截断。候选预览可以缩短，但原文、Embedding 和重排序输入不能静默切掉适用条件。各预算分别生效，单项未超限不代表整体模型输入一定能容纳全部材料。

## 类型与实现归属

| 契约 | 实现者与调用方 | 返回及错误 |
| --- | --- | --- |
| `qa.QaService.answer(QaRequest)` | T07 实现，T08 调用。 | `Mono<AnswerEnvelope>`；每次订阅创建独立运行。T08 映射为 `Result<AnswerEnvelope>`，不在事件线程阻塞等待。 |
| `model.EmbeddingClient.embed(List<String>, Duration)` | T02 实现，T04/T05 调用。 | `ModelTypes.EmbeddingBatch`，顺序与输入一一对应；失败抛 `DependencyException`。 |
| `model.RerankClient.rerank(String, List<String>, int, Duration)` | T02 实现，T05 调用。 | `ModelTypes.RerankBatch`，下标映射到这一次输入；失败抛 `DependencyException`，T05 整批回退。 |
| `knowledge.KnowledgeStore` | T03 实现，T04/T05/T07 调用。 | 快照、写入、两路召回、主题扩展、原文读取；具体签名见该接口。数据库失败抛 `DependencyException`。 |
| `knowledge.KnowledgeTokenizer.tokenize(String)` | T04 实现，T04/T05 共同调用。 | 有序词元列表；词典、停用词与预处理配置一致。 |
| `search.KnowledgeSearch.search(SearchRequest, RunContext)` | T05 实现，T07 工具调用。 | `SearchResult`，必要路由失败作为结构化错误保留；每次结果恰登记一次。 |
| `search.KnowledgeReader.read(ReadRequest, RunContext)` | T05 实现，T07 工具调用。 | `ReadResult`，成功先登记已读证据，失败登记对应片段读取限制。 |
| `qa.AnswerValidator.validate(AnswerDraft, RunContext)` | T06 实现，T07 调用。 | `ValidatedAnswer`；无效草稿转换为明确 `INCOMPLETE`，不原样返回未校验草稿。 |
| `runtime.TraceRecorder` 与 `TraceRecorder.Factory.create(String runId)` | T06 实现记录器及工厂 Bean，T07 按运行创建。 | 分配事件序号/时间；返回不可变事件快照。 |
| `runtime.ExecutionBudget`、`EvidenceRegistry`、`RetrievalLedger` | T07 提供请求独立实现；T05 写入，T06 读取。 | 预算、已读证据、运行聚合状态；不能注册为带可变请求状态的单例。 |

`contract` 中的接口是后续任务真实需要实现的边界，不是「每个类一个接口」。不存在返回常量、空结果或 `UnsupportedOperationException` 的方法桩。T01 只冻结签名，具体调用方落地后仍须静态对接；运行兼容性另等测试指令。

## 问答与输出

`QaRequest.question` 必填、非空白；`history` 缺省/null 规范化为空列表。历史只接受 `Message(role, content)`，role 的 JSON 值仅为 `user`、`assistant`；content 非空白。Role、TaskStatus、AnswerReason 均使用显式 Jackson 字符串 creator，拒绝数字序数、大小写变体和未知值，不依赖 ObjectMapper 的默认枚举宽松规则。T08 在 HTTP 反序列化/校验边界一次性验证角色、内容和配置长度，随后传递明确类型，不在内部重复验证已成立约束。

模型只能产生 `AnswerDraft(status, reason, answer, citations, evidenceGaps, clarifyingQuestion)`。`CitationDraft` 只包含 `documentId/version/chunkId/quote`；没有运行 ID、资料快照、预算或检索状态。模型草稿属于不可信输入，缺字段/null 列表等由 T06 在接收边界判定 `OUTPUT_INVALID`。

T06 返回 `ValidatedAnswer`；T07 据此与实际 `RunContext` 组装 `AnswerEnvelope`。其 `runId/snapshotId/retrieval/budget` 只能由应用填写。`Citation` 的偏移由已读原文计算，quote 必须是该片段的非空、连续、原样子串；重复出现时取片段内第一次匹配。偏移相对完整文档，采用 `[startOffset, endOffset)`。`contentHash` 是已读完整片段的哈希，不是模型生成的 quote 哈希。

| status | 正常业务 reason | 应用最低前提 |
| --- | --- | --- |
| `ANSWERED` | `EVIDENCE_SUFFICIENT` | 至少一条有效的本次已读引用，无未恢复的必要召回/读取错误或主题截断。 |
| `NEEDS_CLARIFICATION` | `CONTEXT_REQUIRED` | 有明确待补充背景及追问，不把执行失败伪装成仅缺用户背景。 |
| `CONFLICT` | `APPLICABILITY_UNKNOWN` | 至少两个不同文档版本的已读引用及待确认缺口；可披露仍有检索限制，但不能宣称已穷尽资料。 |
| `NO_EVIDENCE` | `OUT_OF_CORPUS` | 至少一次正常完成的相关检索，无未恢复限制；不能把依赖失败当作无资料。 |
| `INCOMPLETE` | `ROUTE_FAILED`、`TOPIC_TRUNCATED`、`READ_FAILED`、`BUDGET_EXHAUSTED`、`MAX_ITERATIONS`、`INPUT_LIMIT_EXCEEDED`、`DEADLINE_EXCEEDED`、`MODEL_FAILED`、`OUTPUT_INVALID`、`CITATION_INVALID` | 应用按真实失败选择原因并保留限制明细。 |

多原因同时存在时采用稳定优先级：总超时、业务工具预算、轮次上限、文本预算、模型失败、草稿结构、引用无效、召回失败、读取失败、主题截断。已达到执行上限/模型失败等不能被草稿中的 `ANSWERED` 或 `CONFLICT` 覆盖；只有非终止性的检索限制允许已读双方的真实冲突正常返回。该优先级由 T06/T07 落实。

任务状态独立于 HTTP 和 `Result.code`。T08 使用现有 `Result.success(envelope)`；HTTP 输入/格式错误为 400，输入长度超限为 413。建立运行前没有 READY 快照或快照配置不匹配为 503；没有可靠运行结果可返回的系统异常为 500，外部依赖不可用为 502、外部超时为 504。已建立上下文并能解释失败时返回 HTTP 200、`Result.code=200` 与 `status=INCOMPLETE`。T08 可通过现有 `Result` 的 setter 构造与 HTTP 一致的错误 code，无需更改原有成功语义。不能返回裸堆栈、密钥或上游原始错误体。

机械校验只证明引用存在与一致、状态符合最低前提，不证明语义支持、资料相关性或冲突已经正确解释。后续真实 Trace 还须证明读取完成后发生过模型推理，不能用同轮预先生成的框架 `generate_response` 证明模型已观察原文。

## 资料快照、原文与数据库交接

`KnowledgeTypes.DocumentKey(snapshotId, documentId, version)` 唯一标识快照中的文档版本；`ChunkKey(document, chunkId)` 是稳定片段键。稳定并列顺序按 `snapshotId/documentId/version/chunkId` 逐项 UTF-8 无符号字节序确定；Java 使用 `KnowledgeTypes.TEXT_ORDER/CHUNK_ORDER`，PostgreSQL 使用 UTF-8 数据库及显式 `COLLATE "C"`。不能使用受环境影响的数据库默认 collation 或 Java UTF-16 自然顺序替代。

`IndexProfile` 包含 Embedding 模型、固定维度、预处理、分词器、领域词典和停用词版本。T04 在发布时写入，T07 固定 READY 快照时与应用配置核对；不匹配时拒绝问答而非混用索引。更改上述任何内容须建立新快照，更改 Reranker 不要求重建向量。

`CorpusSnapshot` 包含资料集合哈希及创建/发布时间；BUILDING 的发布时间可为空，READY 必须已完成导入与发布。`DocumentMetadata` 包含标题、版本日期、适用范围、明确替代的 `DocumentRef` 列表及完整文档哈希；未知适用范围可为 null，未明确替代时列表为空。版本日期仅是资料元数据，不提供制度优先级。

`KnowledgeDocument` 保存不可改写的完整原文；`KnowledgeChunk` 保存原文子串、相对原文的 UTF-16 范围及片段哈希。文档/片段哈希统一对原始字符串的 UTF-8 字节计算 SHA-256，并转小写十六进制；不先改变换行、空白或 Unicode。资料集合哈希对按上述 UTF-8 字节序的文档键排序的文档列表计算：依次写入 documentId、version、文档 contentHash，每项以 UTF-8 字节长度的四字节大端整数作前缀，再写入该项 UTF-8 字节，最后计算 SHA-256。不得将多个值无分隔拼接后哈希。

T04 在导入边界一次性校验资料字段、唯一键、偏移/substring/哈希、主题标签及替代关系；索引归一化不修改原文。`IndexedChunk` 携带已验证的 `DenseVector` 和同一分词器产生的词元；T03 不必重复验证模型响应。

`KnowledgeStore.createSnapshot` 仅建立 BUILDING 快照。向量调用发生在数据库事务外，原文和片段分批写入；`publishSnapshot(snapshotId, SnapshotManifest, timeout)` 在短事务内核对状态、文档/片段数及预期集合哈希并发布。失败不得把 BUILDING 当 READY，也不影响旧快照。`latestReadySnapshot` 按发布时间降序、snapshotId 的上述 UTF-8 字节序打破并列，问答固定选择结果，不在每个工具中重新查询最新资料。

每个查询方法显式接收快照/片段键及 `Duration timeout`。`read` 返回 `Optional.empty()` 仅表示指定键不存在；数据库错误必须抛异常。向量查询先按余弦距离升序，关键词查询先按 `ts_rank_cd` 降序，再分别按 `snapshot_id/document_id/version/chunk_id COLLATE "C" ASC` 打破同分，然后才执行 LIMIT；`RecallHit.rank` 均从 1 开始，`rawScore` 是各自原始距离/分数，只用于该路诊断，不跨路比较。关键词查询接收词元而非未经处理的 `tsquery`；SQL 使用绑定参数，固定表名与排序表达式。

主题扩展的 `topicKeys` 来源为重排序前候选池；`excluded` 只排除最终主候选的稳定片段键，避免把被重排序排除的其他版本一起丢掉。主题扩展先按 topic_key 的上述 UTF-8 字节序、再按稳定片段键升序排序后截取。T03 需要额外探测至少一条来确定 `truncated`，刚好返回 limit 条并不自动等于截断。T05 将数据库扩展上限与工具文本预算造成的截断合并披露。

## 模型客户端交接

`DenseVector` 在构造时验证 1024 维与有限浮点值，并复制数组，读取也返回副本。T02 对外部 JSON 的数量、下标、非空及数值格式校验后构造；不完整/越界/重复下标或维度错误整批失败，不能返回零向量、空列表或部分结果。

Embedding 输入非空、数量及单文本长度在 T02 接收边界检查；`EmbeddingBatch.vectors[i]` 严格对应输入 `texts[i]`，外部返回顺序由 T02 按 index 还原。按架构请求 `encoding_format=float`，不发送 `dimensions`。服务响应 ID 和 usage 可为空，不能把缺失计量伪造为 0。

Rerank 输入保持本地条款全文，不信任服务返回的文本。`topN` 必须位于 1 至候选数量，返回数量必须等于 topN，`RerankScore.inputIndex` 不重复且处于输入范围，相关性分数必须有限。T02 只验证协议，不做 RRF 回退。T05 将 inputIndex 映射到本次 `SearchMetrics.rerankInputKeys`，按分数降序、RRF 名次和稳定片段键打破并列。

`KnowledgeStore.loadChunks(snapshotId, keys, timeout)` 批量读取重排序前候选池对应的完整本地条款，返回顺序严格对应 keys；只接受同一固定快照的去重键。缺任一条款、快照不符或数据库失败时整批抛 DependencyException，不返回部分列表。这是检索内部素材读取，不调用 EvidenceRegistry，不能代替模型主动调用 readKnowledge。T05 在该批量读取失败时跳过云端重排序、整批回退 RRF，把错误记入 rankingWarnings；主题扩展仍执行，若主题扩展本身也失败，必须作为工具失败传播，不能伪装成完整扩展。

模型与数据库客户端的 `Duration timeout` 为调用方已取「该能力配置超时与当前剩余预算的较小值」，只接受正值；没有剩余预算不发起调用。客户端仍设置连接及响应超时，不仅靠外层订阅取消。T02 将超时、限流、认证失败、不可用及无效响应转换为 `Failure`/`DependencyException`；不吞掉异常、不泄露原始服务响应或请求头。T04 导入不共用问答 120 秒总预算，每批使用能力级超时，失败停止发布。

## 检索结果与恢复规则

T07 工具边界检查查询非空白及长度，去除首尾空白后创建 `SearchRequest`；原始 topK 缺省取配置主候选上限，其余夹取到 1 至配置上限，再将非空 int 传给 SearchRequest。当前默认上限为 6。T07 Trace 同时记录 requested 和 effective 参数，T05 使用 effective 值生成 `SearchScope(snapshotId, query, topK)`。

`Candidate` 包含预览、两路排名、RRF 分数、可空重排序分数和来源。未进入某一路的 rank 为 null；主题扩展在融合池外时 RRF 为 0、两路 rank 与 rerankScore 可为空。排名从 1 开始，分数不是置信度或制度优先级。`TopicGroup.members` 按 topicKey 关联本次返回的主候选与相关候选；truncated 仅表示该主题尚未完整呈现。

`Routes` 分别表示 vector、keyword、rerank。必要召回正常且为空仍为 OK；无词元时关键词正常返回空，不伪造失败。无候选时 Rerank 为 `SKIPPED_EMPTY`，rankingMode 为 `NONE`；成功排序为 `RERANK`。重排序超时、无效下标、输入受限等整批使用 RRF，标记 `ERROR/RRF_FALLBACK`、rankingWarnings 和 degraded；不加入必要召回 unresolvedErrors，且仍执行独立主题扩展。

`SearchMetrics` 记录两路数量、去重数、实际送入重排序数、最终主/关联数、重排序耗时、响应 ID、usage，以及输入下标对应的完整稳定键列表。未请求重排序时数量/耗时为 0、键列表为空、ID/usage 为 null。`MIXED` 仅用于多次检索聚合摘要，单次 SearchResult 不使用它。

`KnowledgeSearch` 在返回前调用 `context.retrieval().recordSearch(result)`，每次恰一次。检索预览的累计文本预占归 T07 工具包装层：拿到 SearchResult 后，按实际返回的预览内容字符数调用 `tryAcquireText(0, previewCharacters)`。拒绝时保留 recordSearch 和实际检索 Trace，但仅向模型返回结构化 `BUDGET_EXHAUSTED` 工具错误、在同一条 ToolCompleted 中保留 SearchOutcome，并以 deliveryError 记录 BUDGET_EXHAUSTED，不展示候选；不将此错误填入 RecallProblem、不伪装成空检索。textLimitExceeded 使最终结果为 INCOMPLETE/INPUT_LIMIT_EXCEEDED。主题扩展等无法生成可靠 SearchResult 的致命失败由 T05 抛 DependencyException，T07 工具边界记录 ToolError 并结束为 INCOMPLETE/ROUTE_FAILED，不能被之后的草稿覆盖。`KnowledgeReader` 成功时先预占原文/工具文本预算，再登记证据并 `resolveRead(key)`；失败只登记 `ReadProblem`，不能把失败读取或未交给模型的文本加入证据集合。

T07 的 RetrievalLedger 按下列规则聚合，T06 不重新猜测：

1. 必要召回错误按固定快照、去除首尾空白后的相同查询、路由分组；后续相同路由 OK 且 effective topK 不小于先前值时才可清除该错误。改写、无关查询、较小 topK 或仅另一条路由成功均不能清除。
2. 召回恢复与主题查证补足分别检查。只有相同查询后续未截断且 topK 不小于先前值，才可清除对应 truncatedSearches。全局累计文本预算不足时不能虚构补查完成。
3. 读取失败按同一 ChunkKey 保存；该片段成功读取并登记证据后才清除。不存在的版本不能因读取了其他版本而清除。
4. 原始失败事件始终留在 Trace。请求中曾发生降级则摘要 degraded 保持 true，rankingWarnings 保留全部排序警告；各必要路由有未恢复问题时摘要为 ERROR，否则有成功时为 OK，从未调用为 NOT_RUN。
5. 摘要 rankingMode：忽略无候选的 NONE；成功排序与回退均出现时为 MIXED，只有其中一种时保留对应模式，全部无候选时为 NONE。rerank 路由曾发生回退则为 ERROR，否则有成功为 OK，仅空池跳过为 SKIPPED_EMPTY，未调用为 NOT_RUN。
6. 未恢复的必要召回/读取错误或主题截断阻止无保留 ANSWERED、NO_EVIDENCE。实际已读双方冲突可返回 CONFLICT 并披露限制；重排序单独失败并回退不阻止查证充分后的回答。

这是一项保守恢复约定，用户已经确认，其权衡见 [ADR 0002](adr/0002-query-scoped-recovery.md)。

## 运行上下文、预算和 Trace

T07 在每次订阅内创建独立的 `RunContext(runId, snapshot, budget, evidence, retrieval, trace)`，通过 `RuntimeContext.builder().put(RunContext.class, runContext)` 注入工具。不得使用单例上的「当前请求」字段或跨线程 ThreadLocal，也不把快照和预算做成模型参数。

`ExecutionBudget` 使用单调时钟，remaining 最低为 `Duration.ZERO`。`recordModelIteration()` 在实际推理开始时计数；框架发出最大轮次事件时调用 `markIterationLimitExceeded()`，恰好在最后允许轮次内成功不能误判上限。`tryAcquireToolCall()` 原子预占实际业务工具次数，被拒绝不执行且不增加次数，记录 toolLimitExceeded；框架内部 `generate_response` 不占业务工具次数。模型和工具重试均固定 `maxAttempts=1`，工具顺序执行，DeepSeek 显式 `enableThinking(false)`。

`tryAcquireText(readCharacters, toolTextCharacters)` 原子预占累计输出字符：T05 原文读取在登记证据前预占两项，T07 检索包装层只预占工具文本；工具文本在此特指交给模型的候选预览和原文内容，完整序列化元数据另受每次 modelInputCharacters 约束；重复返回的相同片段仍实际消耗字符预算。拒绝时两项均不扣减，置 textLimitExceeded，并返回输入/文本预算错误；不部分切割制度原文。`BudgetSnapshot` 包含实际轮次、工具数、两项字符消耗、最大轮次/工具数、耗时/剩余毫秒和各类上限标记。modelInputCharacters 是每次完整模型输入的上限，由 T07 在模型边界核对，不是跨轮累计输入上限。

`TraceRecorder.Factory.create(runId)` 返回绑定本次 runId 的请求独立空记录器，导出的每条 TraceEvent 顶层均包含该 runId。T06 的 record 为每次事件分配从 1 开始的单调序号与 Instant 时间，events 返回不可变快照；T07 保留取消/超时前事件。`TraceEvent.Detail` 的 JSON type 区分 RUN_STARTED、MODEL_ROUND、TOOL_STARTED、TOOL_COMPLETED、RUN_FINISHED；输入与观察也有明确 type。Started 记录框架/模型/索引与查询参数，工具事件关联 callId、iteration、请求/执行参数、公开用途及类型明确的结果摘要，Finished 记录最终状态、预算及通过校验后实际返回的引用稳定键列表；没有引用时为空列表，不能用所有已读片段代替最终引用子集。

SEARCH_KNOWLEDGE、READ_KNOWLEDGE 分别对应工具名 `searchKnowledge`、`readKnowledge`；其他框架工具不伪装成业务工具。公开用途和摘要只能是用于展示的简短说明，不保存模型原始推理。检索观察保留下标映射、候选键、各路状态及错误；读取观察只存键、字符数和哈希，不复制整篇文档或历史对话。同一 callId 恰有一个 ToolCompleted：正常交付时 deliveryError 为空；检索已完成但预览预算拒绝时，outcome 保留实际 SearchOutcome、deliveryError 保存 BUDGET_EXHAUSTED，模型只观察预算错误；产生 SearchResult 前即失败时 outcome 为 ToolError、deliveryError 为空。不能靠两条完成事件重复计数同一调用。

## 调用示例与后续静态交接

以下片段只说明调用次序和类型，依赖实现尚属 T02 至 T08，不是运行通过的示例。

```java
// T05：context 已由应用固定 READY 快照，properties 已在启动边界验证。
Duration remaining = context.budget().remaining();
Duration configured = Duration.ofMillis(properties.timeouts().embeddingMs());
Duration timeout = remaining.compareTo(configured) < 0 ? remaining : configured;
// timeout 为零时在工具边界结束，不发起外部请求。
EmbeddingBatch batch = embeddingClient.embed(List.of(request.query()), timeout);
DenseVector queryVector = batch.vectors().get(0);
List<RecallHit> hits = knowledgeStore.searchVector(
        context.snapshot().snapshotId(), queryVector,
        properties.retrieval().routeLimit(), databaseTimeout);
```

```java
// T07：模型业务草稿先交 T06；运行字段始终由应用填充。
AnswerValidator.ValidatedAnswer checked = answerValidator.validate(draft, context);
AnswerEnvelope envelope = new AnswerEnvelope(
        context.runId(), context.snapshot().snapshotId(), checked.status(), checked.reason(),
        checked.answer(), checked.citations(), checked.evidenceGaps(), checked.clarifyingQuestion(),
        context.retrieval().summary(), context.budget().snapshot());
// T08：Mono 继续映射，复用已有统一响应。
Mono<Result<AnswerEnvelope>> response = qaService.answer(request).map(Result::success);
```

T02/T03 完成后核对实际参数映射及超时；T04 核对原文/索引标识和批次顺序；T05 核对工具结果及每次登记；T06/T07 核对状态优先级、恢复、证据和 Trace；T08 核对输入、错误映射及响应外层。所有任务依赖已合并 dev 的类型，不自行复制 DTO 或修改公共配置。

未执行：依赖树、编译、启动、测试、Mock 冒烟、真实 PostgreSQL 或云模型调用。上述组合是否可用、配置绑定的实际启动表现及行为验收，都等待用户后续测试指令。
