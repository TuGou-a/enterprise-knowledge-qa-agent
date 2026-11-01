# AgentScope Java、模型服务与 MyBatis：接入核查

本文件记录架构讨论中的来源与边界。核查日期为 2026-10-31。本轮对照用户提供的本地源码、发行版标签及官方资料，未使用真实 API Key，未运行模型调用，也未运行 Maven 解析、构建或集成测试。

## 本地源码核查结论

总体设计符合题目要求的能力方向：单 ReAct Agent 选择检索、读取、补查与结束，应用负责 MyBatis 混合检索、重排序、证据和任务状态。它能作为实施方案，但当前只有应用骨架与设计文档，不能据此认定已通过考核。

用户已明确固定 AgentScope Java `2.0.4`。核查使用 `reference-code/agentscope-java` 内的 `v2.0.4` 标签，提交为 `0b6743b263ba8e39eb417e35048026f1e0196548`。参考源码工作区 HEAD 为 `e190a8d1b855312af5f349334ec14c6ea1013d4f`，根 POM 为 `2.1.0-SNAPSHOT`；它与目标发行版不同。通过 `git -C reference-code/agentscope-java show v2.0.4:<文件路径>` 只读核查，不切换或修改参考仓库。

| 核查项 | 结论与设计处理 |
| --- | --- |
| 真实决策循环 | `ReActAgent` 提供模型推理、工具观察和继续执行；业务工具能承接题目要求，真实决策仍需运行记录证明。 |
| 检索职责 | `core.rag` 旧 API 已标记弃用。继续由应用实现混合检索和重排序，通过工具交给 Agent；官方应用层 RAG 示例只能作为分层参考，不能替代本题的动态决策。 |
| 依赖版本 | 需优先验证 Boot 与 AgentScope 的 Reactor、Jackson 基线差异，详见下表。不能仅凭避开 starter 宣称兼容。 |
| 工具调度与 MyBatis | 默认并行、工作线程执行；首期显式顺序执行，事务在工具调用的 Spring 代理服务内开启。 |
| 执行预算 | 框架默认超时及重试超出本方案预算，需要显式设置；最大轮次后仍会追加汇总。 |
| DeepSeek 配置 | 非思考模式和工具结构化回退可由 Provider 配置；模型名、基础地址需应用绑定，新模型名的上下文窗口需显式设置。 |

以上是源码静态核查结论；兼容性、实际检索效果和考核结果均未经过运行验证。

## 版本与 Maven 依赖

按用户确认，采用 AgentScope Java `2.0.4` 发行版。该版本的[根 POM](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/pom.xml) 声明版本 `2.0.4`，编译目标为 Java 17；另见[发行说明](https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.0.4)。

本项目需要 ReAct 决策与 Java 工具调用，可依赖 `agentscope-core` 和 OpenAI 兼容模型扩展。T01 已将以下依赖声明写入根 POM；配置与共享契约见 [T01 交接](08-t01-contracts.md)。最终已在 AgentScope 2.0.4、Boot 3.5.10、Reactor 3.7.15 和 Jackson 2.19.4 上完成真实工具循环、问答、HTTP 与测试：

```xml
<properties>
    <java.version>17</java.version>
    <agentscope.version>2.0.4</agentscope.version>
</properties>

<dependencies>
    <dependency>
        <groupId>io.agentscope</groupId>
        <artifactId>agentscope-core</artifactId>
        <version>${agentscope.version}</version>
    </dependency>
    <dependency>
        <groupId>io.agentscope</groupId>
        <artifactId>agentscope-extensions-model-openai</artifactId>
        <version>${agentscope.version}</version>
    </dependency>
</dependencies>
```

模型扩展的实际坐标和对核心模块的依赖见 [v2.0.4 模型扩展 POM](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai/pom.xml)。这里只使用一个版本属性统一模块版本；不需要为了两个依赖额外导入 BOM。

建议在 Spring 配置中自行创建模型与 Agent。该发行版的 [Spring Boot starters POM](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-extensions/agentscope-spring-boot-starters/pom.xml) 使用 Spring Boot `4.0.3`，而当前工程使用 `3.5.10`。这不等于核心模块与现有工程不兼容，但不能据此宣称 starter 已验证兼容现有工程。

### 需要优先验证的依赖基线差异

| 依赖 | AgentScope 2.0.4 构建基线 | 当前 Spring Boot 3.5.10 管理基线 |
| --- | --- | --- |
| Reactor BOM | `2025.0.2`，对应 `reactor-core 3.8.2`。 | `2024.0.14`，对应 `reactor-core 3.7.15`。 |
| Jackson BOM | `2.21.1`。 | `2.19.4`。 |

依据：发行版 [AgentScope dependencies BOM 第 72、73 行](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-dependencies-bom/pom.xml#L72)，以及本机 Maven 缓存中的 `spring-boot-dependencies-3.5.10.pom` 与 `reactor-bom-2024.0.14.pom`。应用父 POM 的依赖管理会影响传递依赖。最终运行未出现由该版本差异导致的缺失方法或链接错误，实际选中版本为 Boot 管理的 Reactor 3.7.15 与 Jackson 2.19.4。

收到用户测试指令后，首先核查 `mvn dependency:tree -Dincludes=io.projectreactor:*,io.projectreactor.netty:*,com.fasterxml.jackson.core:*,com.fasterxml.jackson.datatype:*`，随后按项目约定验证 Java 17 编译、Boot 启动和真实工具结构化调用；本轮没有执行这些命令。保留用户选择的 Boot 3.5.10，不在设计阶段未经验证地覆盖整个 Reactor/Jackson BOM，也不引入面向 Boot 4 的 starter。若验证失败，根据实际错误调整兼容依赖后再继续业务实现。

## DeepSeek 官方 API

DeepSeek 官方提供 OpenAI 兼容 API，官方基础地址为 `https://api.deepseek.com`。初始核查记录列出过 `deepseek-flash` 与 `deepseek-v4-pro`；最终运行按实际稳定性测试选择 `deepseek-chat`。来源：[官方入门文档](https://api-docs.deepseek.com/)。

DeepSeek 支持模型返回工具调用，再由应用执行工具并回传结果。官方非思考模式示例使用 `thinking: {"type": "disabled"}`。来源：[工具调用文档](https://api-docs.deepseek.com/guides/tool_calls/)。建议第一版从非思考模式开始验证；该设置与 ReAct 多轮工具决策是不同维度，关闭思考模式不等于取消模型选择动作。

建议将下列内容配置化，真实密钥只通过环境变量提供：

```text
DEEPSEEK_API_KEY=<运行时设置>
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-chat
```

AgentScope 的[当前 DeepSeek 接入文档](https://java.agentscope.io/v2/en/integration/model/deepseek)说明 DeepSeek 通过 OpenAI 模型扩展接入。在线文档会随项目更新，其中的模型名称、字段处理修复与工厂 API 仍需对照 `v2.0.4` 源码核对，不能直接当作该发行版已具备的行为。

对照 [v2.0.4 DeepSeekModelProvider](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai/src/main/java/io/agentscope/extensions/model/openai/compat/deepseek/DeepSeekModelProvider.java)，可用 `ModelRegistry.resolve("deepseek:deepseek-chat", context)`，其中 `context` 使用 `ModelCreationContext.builder().enableThinking(false)` 创建。直接向 `ReActAgent.builder().model("deepseek:deepseek-chat")` 传字符串时无法在该重载中指定这一开关；应先创建模型实例再传入。

该 Provider 默认关闭原生结构化输出，结构化结果采用框架工具回退。发行版中的 DeepSeek formatter 与当前主分支对历史 reasoning 字段的处理不同，因此第一版显式采用非思考模式，并以真实工具调用验证。省略 `enableThinking(false)` 时 Provider 不发送 `thinking` 字段，不能认为此时默认关闭思考。

`DEEPSEEK_BASE_URL` 与 `DEEPSEEK_MODEL` 是本项目的配置约定，Provider 不会自动读取这两个环境变量。应用读取后分别传入 `ModelCreationContext.builder().baseUrl(...)` 与 `ModelRegistry.resolve("deepseek:" + modelName, context)`；`deepseek:` 选择 Provider，后缀原样作为模型名称发给服务端。Provider 可以直接读取 `DEEPSEEK_API_KEY`，应用也可显式传入。

发行版 [ModelContextWindows](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-core/src/main/java/io/agentscope/core/model/ModelContextWindows.java#L80) 只列出 `deepseek-v4-flash` 和 `deepseek-v4-pro`，不包含项目最终选择的 `deepseek-chat`。通过 `ModelCreationContext.builder().option("contextWindowSize", configuredWindow)` 显式设置核查后的窗口；应用仍限制实际历史、检索内容和输出预算。不能把工作区 main 新增的窗口识别及 formatter 修复视为 `2.0.4` 已有能力。

## 硅基流动 Embedding 与 jieba

Embedding 服务按用户选择使用 `https://api.siliconflow.cn/v1` 和 `BAAI/bge-m3`，密钥只通过 `SILICONFLOW_API_KEY` 环境变量传入。官方 [Embedding API](https://docs.siliconflow.cn/docs/api/embeddings-post) 支持 `POST /embeddings`、字符串或批量文本输入以及 `float` 输出；`dimensions` 参数仅支持其列明的 Qwen 系列，不能对 `BAAI/bge-m3` 随意发送。

[BAAI 官方模型卡](https://huggingface.co/BAAI/bge-m3) 列明稠密向量维度为 1024。数据库与接口按该维度设计，但仍需要校验真实服务响应及账号可用性。仅调用标准稠密 Embedding 接口，不把模型原生可能具有的稀疏或多向量能力当作云 API 已返回的内容；关键词路由由 jieba 与 PostgreSQL 全文检索承担。

中文分词按用户要求使用 jieba 的 Java 实现，候选坐标为 `com.huaban:jieba-analysis:1.0.2`。[项目源码](https://github.com/huaban/jieba-analysis) 展示 `JiebaSegmenter` 和用户词典能力；主分支 POM 为快照版本，不能直接把主分支行为全部归于该候选发行版。实施时验证 Maven 解析、分词模式、领域词典及并发使用方式，并固定词典版本。

## 硅基流动重排序

按用户新增选择，使用 `BAAI/bge-reranker-v2-m3`，与 Embedding 共用基础地址和密钥，模型变量独立为 `RERANK_MODEL`。[官方 Rerank API](https://docs.siliconflow.cn/docs/api/rerank-post) 提供 `POST /rerank`，以问题和候选文本列表生成相关性排名，返回下标与分数；`return_documents=false` 时应用依靠本次输入映射定位本地原文。该模型不使用只对 Qwen 系列支持的 `instruction`。

[BAAI 模型卡](https://huggingface.co/BAAI/bge-reranker-v2-m3) 区分重排序与 Embedding：前者联合处理问题与文本并输出相关性，后者生成向量。重排序分数不能推断制度效力，也不能直接当作回答正确概率。

应用需要验证下标、唯一性、返回数量、有限分数与请求预算，失败时整批回退 RRF 并披露错误；重排序前候选池的同主题版本独立扩展。候选数量、超时和回退规则为项目设计；账号是否可调用、响应结构、费用、延迟和实际排序效果均未通过真实请求验证。

## MyBatis 与 PostgreSQL

用户明确指定使用 MyBatis。建议采用 `org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.5` 和 PostgreSQL 驱动；[官方兼容表](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/) 列明 3.0 系列支持 Spring Boot 3.2 至 3.5、Java 17 及以上，另见 [3.0.5 发行说明](https://github.com/mybatis/spring-boot-starter/releases/tag/mybatis-spring-boot-3.0.5)。本设计不因文档首页示例已使用 4.0 就升级现有 Boot 基线。

Mapper 接口与 XML 管理两路检索、入库和原文读取；查询向量序列化为校验后的字符串，用参数绑定和 SQL 显式类型转换，避免首期额外维护向量对象 TypeHandler。`tsvector` 在 SQL 内构造，XML 转义 `<=>`。上述映射方案属于实施建议；需在真实 PostgreSQL 验证参数转换、快照过滤、全文索引和事务发布，不能只由框架兼容表推断检索已可运行。

## 框架与应用的职责

| 关注点 | 框架提供的能力 | 本项目需要实现的部分 |
| --- | --- | --- |
| 动态决策 | `ReActAgent` 推理与工具执行循环。 | 提示词、领域工具及对异常观察结果的处理策略。 |
| Java 工具 | `Toolkit.registerTool(...)` 扫描 `@Tool` 方法。 | 文档检索与指定版本原文读取。 |
| 输出结构 | 结构化调用与 `getStructuredData(...)`。 | 回答、引用、资料缺口、状态的数据契约与验证。 |
| 运行记录 | Agent 事件与中间件扩展点。 | 对事件进行取舍，记录工具参数、返回摘要、后续动作和公开的简要依据。 |
| 执行边界 | `maxIters(...)` 限制 ReAct 轮次。 | 把达到上限映射为明确的未完成状态，并设置调用超时。 |
| 知识检索 | 工具可调用应用检索能力。 | 资料解析、索引、排序、引用定位、证据校验与评估。 |

依据：[v2.0.4 ReActAgent 源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java)、[Toolkit 源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-core/src/main/java/io/agentscope/core/tool/Toolkit.java)、[应用层 RAG 示例](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-examples/documentation/src/main/java/io/agentscope/examples/documentation2/rag/ApplicationLayerRagExample.java)。

## 工具调度、事务与执行边界

`ToolkitConfig` 默认 `parallel=true`。首期采用 `ToolkitConfig.builder().parallel(false).build()`，同轮已选业务工具按顺序执行；模型仍根据返回选择下一步。`ToolExecutor.applyScheduling` 使用 `Schedulers.boundedElastic()`，因此同步 `@Tool` 方法可直接调用 MyBatis，不必为适配框架改为响应式数据库访问，也不必重复添加线程池。Spring 事务在实际执行工具的服务线程内开启，云端请求留在事务外。依据：[ToolkitConfig](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-core/src/main/java/io/agentscope/core/tool/ToolkitConfig.java#L29)、[ToolExecutor](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-core/src/main/java/io/agentscope/core/tool/ToolExecutor.java#L493)。

`RunContext` 是应用类型，通过 `RuntimeContext.builder().put(RunContext.class, runContext)` 携带快照、预算与证据。模型可填写参数标注 `@ToolParam`，注入的 `RunContext` 参数不标该注解。框架可按类型注入；不用共享 Agent 当前上下文或跨线程 `ThreadLocal`。依据：[ToolMethodInvoker](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-core/src/main/java/io/agentscope/core/tool/ToolMethodInvoker.java)。

`ExecutionConfig.MODEL_DEFAULTS` 是 5 分钟、最多尝试 3 次；`TOOL_DEFAULTS` 是 5 分钟、1 次。首期通过 `modelExecutionConfig(...)` 与 `toolExecutionConfig(...)` 明确设置超时和 `maxAttempts=1`，另在应用结构化 `call` 链上强制 120 秒总预算（建议初值）。单次调用、数据库查询与 HTTP 超时受剩余预算约束；总超时覆盖追加汇总和内部结构化工具。取消订阅不保证阻塞查询立即结束，数据库和 HTTP 客户端各自仍需设置超时。依据：[ExecutionConfig](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-core/src/main/java/io/agentscope/core/model/ExecutionConfig.java#L130)。

框架会把普通工具异常转成 `ToolResultBlock.error` 供模型观察，异常不一定冒泡到 HTTP 控制器。业务工具边界应记录错误类别和恢复情况，并返回明确的错误观察结果，不能把空候选或默认转换后的错误文本当作成功。Trace 通过 `MiddlewareBase.onAgent` 的完整事件流采集，观测 `ExceedMaxItersEvent`、工具调用和结果；模型推理内容不直接作为公开决策摘要保存。

`2.0.4` 在追加汇总成功或失败时均返回 `GenerateReason.MAX_ITERATIONS`；失败额外记录 `SUMMARY_FAILED` 元数据。应用先判断上限、总超时和错误，再调用 `getStructuredData(AnswerDraft.class)`。`generate_response` 为调用内的合成工具，不注册为第三个业务工具；若它与读取工具出现在同一轮，框架即使先执行读取，也不意味着模型已观察该结果。真实 Trace 应证明读取完成后还有模型推理，再生成最终草稿。依据：[ReActAgent 汇总错误处理](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L4071)、[结构化工具分派](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L3564)。

## 实现时必须验证的细节

- `maxIters` 表示 ReAct 轮次，同一轮可能执行多个工具调用；不能把它解释为精确的工具调用次数上限。
- 达到最大轮次后，框架仍会进行一次汇总调用。应用需先检查 `GenerateReason.MAX_ITERATIONS`，再处理结构化输出，不能把汇总文本直接标记为已完成。
- 覆盖汇总失败、总超时和业务工具次数耗尽，确认已经产生的 Trace 保留，最终状态仍为 `INCOMPLETE`。
- `call(..., 输出类型, RuntimeContext)` 支持结构化输出；`streamEvents(...)` 不提供相同的结构化类型重载。需要依据最终入口选取事件记录方式。
- 文本引用与原文相同，只能证明片段存在；是否足以支持答案，还需要语义层面的评估。
- 后续至少验证依赖解析、Java 编译、Spring Boot 启动、一次真实工具调用及结构化结果解析，才能说明目标组合已可运行。
