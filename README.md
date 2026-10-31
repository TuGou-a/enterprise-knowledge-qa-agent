# 企业知识问答 Agent

面向报销与差旅制度的知识问答项目。通过检索和原文读取工具，帮助员工获得有文档依据的回答；资料冲突或不足时，明确说明限制并请求补充信息。

本轮 T09 以 T01 的配置与共享契约为测试基线，补充契约测试和交付材料。集成时已同步 `dev` 上的 T02 模型客户端、T03 持久化与 T06 校验/Trace 实现；48 个现有测试已通过，T04 已完成本机临时 PostgreSQL／pgvector 与受控 Embedding 的导入验证。这些模块的业务实现测试仍需补齐，完整问答、离线 Mock、真实模型输出及评估结论仍待完成。其他并行模块的最新状态见开发计划及 Git 历史。

## 文档入口

- [HTML 架构文档](docs/architecture.html)：总体架构、混合检索与重排序、MyBatis 映射、动态决策及验收方案，可直接在浏览器打开。
- [需求基线](docs/01-requirements.md)：题目要求、已确认范围与验收点。
- [详细方案](docs/02-solution.md)：模块职责、工具契约和失败处理。
- [框架接入核查](docs/framework-verification.md)：官方来源、发行版 API 与兼容性边界。
- [开发计划](docs/06-parallel-development-plan.md)与[T01 契约](docs/08-t01-contracts.md)：任务边界、类型及待验证事项。
- [测试与评估](docs/03-evaluation.md)、[AI 使用记录](docs/04-ai-usage.md)和[阶段复盘](docs/05-retrospective.md)。
- [示例与证据说明](examples/README.md)：验收输入、标注模板和后续运行证据的保存方式，当前没有完整问答的实际输出或 Trace。
- [业务术语表](GLOSSARY.md)与[数据库选型决策](docs/adr/0001-postgres-hybrid-retrieval.md)。

## 当前测试入口

需要 Java 17 和已安装的 Maven（可使用 Maven 3.9 系列）；仓库未提供 Maven Wrapper。首次准备插件和依赖可能需要网络；契约测试本身不需要业务密钥、PostgreSQL 或云模型。收到测试指令后，在项目根目录执行：

```bash
mvn -Dtest='*ContractTest' test
```

测试覆盖向量合法性、配置约束、读取结果互斥、资料排序、JSON 契约和模型调用中断；当前 48 个测试已通过，其中 4 个内嵌 HTTP 服务用例需要在允许本机端口绑定的环境运行。依赖全部缓存后才可尝试加 `-o` 断网执行。当前入口不能证明完整 Mock 问答、真实检索或模型决策。

评审可先阅读 [需求映射和测试边界](docs/03-evaluation.md)，再检查 `src/test/` 和 `examples/`。T03 数据库初始化与 T04 资料导入已在本地临时环境验证，详见 [T04 运行验证](docs/t04-ingestion-verification.md)；完整问答运行步骤仍待 T05/T07 完成。

## 已确认的项目范围

- 优先完成考核要求，以题目建议的 3 天投入规模控制功能范围；实际用时在实施后记录。
- 沿用现有 Java 17、Spring Boot 3.5.10 和 Maven 后端，以 HTTP API 为主要入口。
- 第一版使用合成的报销与差旅制度，保留文档 ID、版本日期和可核对的原文。
- 采用 PostgreSQL、pgvector 和 MyBatis，实现向量与关键词混合检索。
- 中文分词采用 jieba，使用 Java 实现接入当前工程。
- DeepSeek 官方 API 负责问答与工具决策；硅基流动的 `BAAI/bge-m3` 负责 Embedding，`BAAI/bge-reranker-v2-m3` 负责重排序。
- 请求携带问题及可选对话历史，后端第一版不保存跨请求会话。
- 本期交付 HTTP API、调用示例、测试和运行记录；暂不开发聊天界面。

## 技术选型与取舍

### 使用 Java：优先利用熟悉的语言完成可验证的项目

我选择 Java，主要是因为自己更熟悉 Java。现有工程也已使用 Spring Boot 和 Maven，沿用这些基础有利于把时间用于业务实现、问题排查和测试。

这项选择基于个人经验与项目时间约束，不意味着 Java 在所有 Agent 场景中都更合适。相应代价是部分 AI 示例或工具优先提供 Python 接口，本项目需要核对 Java 版本的 API 和可用能力。

### 使用 AgentScope Java：在有限时间内聚焦业务开发

我选择 AgentScope Java，是因为开发时间有限，希望重点实现业务决策、知识检索、冲突处理、引用校验和效果验证，而不从零开发底层大模型交互、工具协议解析与 ReAct 调度。

框架负责模型适配、工具注册与执行循环等通用能力；项目仍需负责制度资料、检索策略、工具返回、任务状态和验收结果。使用框架可以减少通用代码，也会引入版本和行为约束，因此需要固定发行版，并记录实际使用的功能。

按用户确认，固定使用 `agentscope-core:2.0.4` 和 `agentscope-extensions-model-openai:2.0.4`。在现有 Spring 配置中创建 Agent；该版本官方 starter 使用的 Spring Boot 基线与当前工程不同，暂不采用 starter，目标组合仍需编译与运行验证。来源见[接入核查](docs/framework-verification.md)。

已对照本地源码的 `v2.0.4` 标签核查：ReAct 循环、Java 工具、结构化调用与中间件可承接本方案；旧 `core.rag` API 已标记弃用，检索与重排序由应用实现。`reference-code/agentscope-java` 当前工作区是 `2.1.0-SNAPSHOT`，仅作为源码参考；业务工程仍通过 Maven 获取 `2.0.4`，不依赖该目录构建。

需优先验证依赖基线：AgentScope 2.0.4 使用 Reactor BOM `2025.0.2`、Jackson `2.21.1`，Boot 3.5.10 管理 Reactor BOM `2024.0.14`、Jackson `2.19.4`。实际依赖树已成功解析，当前采用 Boot 管理的 Jackson 2.19.4 和 Reactor 3.7.15；T04 的非 Web 导入上下文已运行，但 AgentScope 参与完整问答与模型调用后的兼容性仍需后续验证。暂不盲目覆盖版本或升级后端。具体来源见[依赖基线差异](docs/framework-verification.md#需要优先验证的依赖基线差异)。

### 使用 PostgreSQL＋pgvector：由一个数据库承载原文与两路检索

我希望采用相对简单的方式实现混合检索，因此选择 PostgreSQL 保存文档、版本与原文片段，使用 pgvector 做向量检索，使用 PostgreSQL 全文检索做关键词检索。这样可以在同一个数据库中维护资料与索引，减少部署组件及数据同步工作。

代价是需要自行编写两路查询、结果融合和版本过滤逻辑。该方案适用于当前小型合成知识库；大规模数据下的召回与性能需要另行测试，不能由本项目设计直接推出。小数据集先用向量精确检索，后续有性能证据时再考虑 HNSW。依据：[pgvector 官方文档](https://github.com/pgvector/pgvector#hybrid-search)。

### 使用向量＋关键词检索：同时覆盖语义改写和明确术语

向量检索用于发现语义相近但表达不同的内容；关键词检索用于匹配「报销期限」「差旅补贴」等明确术语。建议两路分别排序，再用 RRF（倒数排名融合）合并，避免直接相加量纲不同的相似度与全文检索分数。

混合检索增加了 Embedding 调用、查询与调参工作，不保证一定优于单路检索。评估时比较关键词、向量、混合、混合＋重排序四种方案，并检查冲突资料是否成对召回。

### 使用 MyBatis：集中管理 PostgreSQL 检索 SQL

按本项目选择，数据访问层使用 MyBatis，通过 Mapper 接口和 XML 管理文档入库、版本原文读取、全文检索和向量检索。SQL 可以直接表达 `ts_rank_cd`、`<=>` 和快照过滤，便于审阅及调整检索逻辑。

代价是需要维护 SQL、结果映射与 PostgreSQL 类型转换。建议使用 `org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.5`；官方兼容表列明 3.0 系列支持 Spring Boot 3.2 至 3.5、Java 17 及以上，目标工程仍需实际编译和运行验证。查询值使用 `#{}` 绑定，向量先校验维度与有限数值，再序列化并通过 `CAST(#{queryVector} AS vector)` 传入。来源：[官方兼容表](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)、[3.0.5 发行说明](https://github.com/mybatis/spring-boot-starter/releases/tag/mybatis-spring-boot-3.0.5)。

### 使用云端重排序：对召回候选做更细的相关性比较

按用户指定，使用硅基流动的 `BAAI/bge-reranker-v2-m3`。RRF 合并两路排名后，重排序模型联合比较问题与候选条款，选择优先提供给 Agent 的资料。复用现有硅基流动服务配置，可减少本地部署工作；它带来的相关性改进需要评估，不能预先保证。

建议初值为每路召回 20 条，RRF 去重后保留最多 20 条供重排序，最终返回最多 6 条主候选。同主题版本从重排序前的候选池扩展，最多额外返回 12 条，独立于主候选保留，避免低分版本掩盖制度冲突。分数仅用于相关性排序，不能确定制度效力或回答置信度。

代价是每次有效检索增加一次云端请求及候选文本传输，带来延迟、费用和可用性依赖。重排序失败时回退 RRF，返回 `rankingMode=RRF_FALLBACK` 和降级原因。该步骤属于排序增强；两路召回及必要主题查证完成、原文依据充分时，允许披露降级后正常回答。Embedding 等必要召回路由失败或必要查证截断时，仍按未完成策略处理。来源：[硅基流动 Rerank API](https://docs.siliconflow.cn/docs/api/rerank-post)、[BAAI 模型卡](https://huggingface.co/BAAI/bge-reranker-v2-m3)。

### 使用 jieba：在 Java 侧完成中文分词

中文分词按项目选择使用 jieba 的 Java 实现，候选 Maven 坐标为 `com.huaban:jieba-analysis:1.0.2`。文档入库和问题查询采用同一分词、停用词与领域词典配置，分词结果送入 PostgreSQL 的 `simple` 全文检索配置。

关键词排序使用 `ts_rank_cd`，不将其描述为 BM25。jieba 只负责分词，不负责 PostgreSQL 索引、向量生成或检索结果融合。需要用真实制度词汇验证分词质量，并注意 Java 实现与 Python jieba 的版本、词典和行为可能不同。来源：[jieba Java 项目](https://github.com/huaban/jieba-analysis)、[Maven Central 1.0.2](https://central.sonatype.com/artifact/com.huaban/jieba-analysis/1.0.2)、[PostgreSQL 全文检索](https://www.postgresql.org/docs/17/textsearch-controls.html)。

### 使用 DeepSeek 与硅基流动：分开承担决策、向量生成与重排序

按当前服务选择，DeepSeek 官方 API 负责模型选择动作与组织回答；硅基流动分别提供文档和查询向量化、候选重排序。两项检索能力由应用内部调用，Agent 继续使用检索与原文读取两个业务工具。本地只需运行 Java 应用与 PostgreSQL。

代价是运行依赖网络和两家模型服务，可能遇到限流、超时或服务不可用。文档与查询必须使用同一 Embedding 配置；更换 Embedding 模型或向量处理方式时重建索引。`BAAI/bge-m3` 的稠密向量维度为 1024，数据库使用 `vector(1024)`，并在入库和查询时校验实际响应维度。来源：[模型卡](https://huggingface.co/BAAI/bge-m3)、[硅基流动 Embedding API](https://docs.siliconflow.cn/docs/api/embeddings-post)。

### 使用单 Agent 与调用方历史：控制实现范围

一个 ReAct Agent 通过 `searchKnowledge` 与 `readKnowledge` 两个工具完成补充检索、原文查证和结束决策。当前题目没有必须拆成多 Agent 的职责，单 Agent 更便于观察和复现动作变化。

服务端限制模型请求的 `topK`、工具调用与输入长度。最终结果由应用补充检索降级、截断及预算信息，避免只依赖模型自报完成。必要召回路由仍失败或主题查证被截断时，第一版明确返回未完成；重排序回退单独披露，不直接等同于召回失败。

AgentScope 默认并行执行同轮工具。首期显式采用 `ToolkitConfig.parallel(false)`，便于按顺序管理证据、预算与 Trace；这只规定已选工具的执行方式，下一步动作仍由模型决定。框架工具执行器会把同步工具调度到 `boundedElastic` 工作线程，MyBatis 可以通过普通 `@Tool` 方法调用。数据库短事务在工具调用的 Spring 服务内开启，不在 `QaService` 外包裹整个 Agent 执行。模型和工具默认的 5 分钟超时也需要显式覆盖；模型重试初值设为 `maxAttempts=1`，并由应用强制执行请求总预算，避免把自动重试当作模型动作调整。

请求使用 `question` 和可选 `history`；后端只维护当前请求的执行上下文。调用方需要负责传回历史，服务端不提供跨请求会话恢复。历史仅用于理解追问，不能直接作为制度依据，每次回答仍需核对当前资料。

## Maven 依赖声明

根目录 [pom.xml](pom.xml) 已声明 AgentScope core/OpenAI 扩展 2.0.4、MyBatis starter 3.0.5、jieba 1.0.2、PostgreSQL 驱动、Reactor、Validation 和 Spring Boot Test；沿用现有 Spring Boot parent。T09 复用已有测试依赖，没有新增依赖或覆盖 BOM。实际依赖树、Java 17 编译和启动结果待后续验证。

PostgreSQL 驱动版本交由现有 Spring Boot 依赖管理。业务服务通过 MyBatis Mapper 完成持久化；Mapper XML 集中维护参数化 SQL，RRF 与重排序协调在 `HybridSearchService` 中完成。

## 配置与启动边界

配置清单见 [.env.example](.env.example) 与 [T01 契约](docs/08-t01-contracts.md)。配置绑定和值约束已经实现；真实模式需要两家模型服务密钥和数据库凭据，缺失时启动校验失败，账号可用性仍待实际调用验证。真实密钥通过环境变量提供；示例文件不包含真实密钥。Spring Boot 默认不会自动加载项目根目录的 `.env` 文件，需要由启动终端、IDE 或容器显式注入环境变量。当前契约测试入口与真实启动配置隔离。

应用负责读取 `DEEPSEEK_BASE_URL`、`DEEPSEEK_MODEL` 并传给 Provider；它不会自动读取这两个变量。固定版的窗口表尚不认识 `deepseek-flash`，需要在 `ModelCreationContext` 中通过 `option("contextWindowSize", configuredWindow)` 显式设置核查后的窗口。该名称仍按当前 DeepSeek 官方建议保留，实际输入预算由应用另行限制。

| 变量 | 用途或计划默认值 |
| --- | --- |
| `DEEPSEEK_API_KEY` | DeepSeek 官方服务密钥。 |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com`。 |
| `DEEPSEEK_MODEL` | 候选 `deepseek-flash`，实际可用性待运行验证。 |
| `SILICONFLOW_API_KEY` | 硅基流动服务密钥。 |
| `SILICONFLOW_BASE_URL` | `https://api.siliconflow.cn/v1`。 |
| `EMBEDDING_MODEL` | `BAAI/bge-m3`。 |
| `RERANK_MODEL` | `BAAI/bge-reranker-v2-m3`。 |
| `RERANK_TIMEOUT_MS` | 建议初值 `10000` 毫秒，同时受请求剩余总预算约束。 |
| `SPRING_DATASOURCE_URL` | 本地 PostgreSQL 连接地址。 |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | 数据库账号与密码。 |

硅基流动请求使用 `POST /embeddings` 和 `encoding_format=float`；对 `BAAI/bge-m3` 不发送仅对其他系列支持的 `dimensions` 参数。维度由响应校验，不能通过配置随意改变。

重排序请求使用同一基础地址下的 `POST /rerank`，发送 `model`、`query`、候选条款文本数组 `documents`、`top_n` 和 `return_documents=false`。返回的 `index` 映射回本次输入片段键；校验索引范围、唯一性、数量与分数有效性，异常时整批回退 RRF。`instruction` 不适用于本次 BGE 模型。候选池为空时跳过重排序，记录 `SKIPPED_EMPTY`；Reranker 更换不要求重建文档向量，但需重新评估排序效果。

## 运行与验证状态

48 个现有测试已通过，`mvn -DskipTests package` 可生成可执行 JAR。T03 的[手动数据库初始化脚本及说明](src/main/resources/db/README.md)已在 PostgreSQL 17／pgvector 0.8.6 临时数据库中成功执行；T04 的三组资料、严格失败路径、自定义词典和快照隔离已用受控 Embedding 服务完成验证，详见[T04 运行验证](docs/t04-ingestion-verification.md)。真实硅基流动调用、完整问答、HTTP 接口和 Mock 流程仍待后续任务与真实模型验证。

验收材料至少覆盖明确答案、文档冲突、资料外问题、相似改写、上下文追问和工具失败；另验证执行上限与文档替换。Mock 验证流程，真实模型 Trace 证明动作调整，二者分别标注，结果引用对应文件。

当前 Git 根目录、`pom.xml`、`src/`、README 与 `docs/` 位于同一项目根目录。最终交付仍需补齐完整问答业务、R01–R10 验收证据、真实模型与检索评估及实测结论，记录实际投入时间和未完成项，并提交题号、GitHub URL 与最终 commit SHA；当前不预填。T04 已按独立 Worktree、子代理静态审查、合并后运行验证的流程完成资料导入；其他任务仍按相同流程推进。
