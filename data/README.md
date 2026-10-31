# 合成资料与导入

本目录提供企业知识问答的合成报销、差旅资料及导入步骤。所有制度、日期和金额规则均用于项目验收，不代表真实公司的制度。T04 已完成编译、全量测试、PostgreSQL／pgvector 初始化、三组资料导入及失败路径验证；外部 Embedding 使用本地受控服务替代，真实模型调用仍未覆盖。完整证据见[运行验证](../docs/t04-ingestion-verification.md)。

## 资料集合

| 文件 | 文档／条款数 | 内容与场景 |
| --- | --- | --- |
| [normal.json](corpus/normal.json) | 2／7 | D01 v1 规定 30 个自然日，D03 v1 提供差旅审批与凭证；对应 E01、E04、E05，并供失败场景使用。 |
| [conflict.json](corpus/conflict.json) | 3／8 | 正常组加 D02 v1 的 15 个自然日；D01、D02 的适用范围未知、未声明替代关系；对应 E02。 |
| [mutation.json](corpus/mutation.json) | 2／7 | D01 改为 v2 的 20 个自然日，其他条款及 D03 保持相同；对应 E08。 |

三组资料均没有股票期权内容，供 E03 的资料外问题核对。E01 和 E02 虽然使用相同问题，但必须在各自资料前提下运行；版本日期较新不代表制度优先级。

| 文档 | 片段 ID | 中性主题 |
| --- | --- | --- |
| D01 | `clause-1` | 报销期限 |
| D01 | `clause-2` | 报销凭证 |
| D01 | `clause-3` | 报销材料补充 |
| D02 | `clause-1` | 报销期限 |
| D03 | `clause-1` | 出差审批 |
| D03 | `clause-2` | 差旅凭证 |
| D03 | `clause-3` | 住宿报销 |
| D03 | `clause-4` | 差旅凭证补充 |

这张表只是资料定位，不是实际检索结果或运行证据。完整片段键还包含导入后生成的 `snapshotId`、文档 ID 和版本；[验收模板](../examples/README.md)的实际快照与人工标准标注由后续执行者填写。

## JSON 与条款格式

顶层只含非空 `documents` 数组；每个文档必须显式提供以下全部字段：

| 字段 | 类型与要求 |
| --- | --- |
| `documentId`、`version`、`title` | 非空白字符串；同一文件中的 `(documentId, version)` 唯一。 |
| `versionDate` | 有效的 `YYYY-MM-DD` 日期字符串。 |
| `applicableScope` | 非空白字符串或 `null`；未知时为 `null`。 |
| `supersedes` | 数组；每项含非空白 `documentId` 和 `version`，指向本文件内明确被替代的版本，不能重复或引用自身。原文没有明确替代关系时使用 `[]`。 |
| `content` | 非空白完整原文；不裁剪或归一化。 |
| `clauseTopics` | 对象，键为条款编号字符串，例如 `"1"`，值为非空白中性主题；与实际条款一一对应。 |

原文的编号格式为行首 `第1条`、`第2条`，编号从 1 连续递增，编号后以空白分隔正文。首条之前只允许一行与 `title` 完全一致的标题及空白；适用条件和其他有效说明必须写入编号条款。每个片段从本条编号延伸到下一条编号之前，末条包含原文剩余内容；空条款、错误编号、缺少主题或片段过长均明确报错，不自动修正、拆分或截断。

输入拒绝未知字段、重复 JSON 字段、尾随 JSON、类型强转及重复文档版本。字段映射错误指出字段路径，解析错误提供可用的行列位置，主题缺失或多余指出文档与条款编号。原文及元数据由导入边界集中校验后转换为内部知识类型。片段 ID、UTF-16 偏移、原文及片段 SHA-256 和资料集合哈希均由导入器计算；不在输入文件手填。校验规则不能代替对制度语义、适用条件和替代关系原文的人工核对。

## 导入前置条件

先完成 [PostgreSQL／pgvector 初始化](../src/main/resources/db/README.md)，准备 Java 17、Maven 及模型访问环境。配置通过终端、IDE 或容器注入，Spring Boot 不自动读取根目录 `.env`。

沿用 [环境变量模板](../.env.example)的数据库与模型配置。当前共享 `AgentProperties` 会校验全部模型配置，因此导入也要求 `DEEPSEEK_API_KEY` 非空，但导入过程只调用硅基流动 Embedding，不调用 DeepSeek 或 Rerank；这只是沿用既有配置边界，未新增模型请求。数据库要求目标表已存在，应用不会自动执行初始化 SQL。

## 导入命令

下列命令已在本地 PostgreSQL 17／pgvector 0.8.6 和受控 Embedding 服务下验证。实际使用需要按“导入前置条件”注入真实数据库和模型环境变量；本次没有验证真实硅基流动账号、网络或模型质量。

在项目根目录执行独立导入主类：

```sh
mvn -DskipTests \
  -Dspring-boot.run.main-class=com.mango.backend.knowledge.ingest.IngestionApplication \
  -Dspring-boot.run.arguments='--corpus-file=data/corpus/normal.json --import-timeout-ms=600000' \
  spring-boot:run
```

`--corpus-file` 必填，`--import-timeout-ms` 可省略，默认 10 分钟；其余连接和模型配置使用环境变量，入口拒绝未知、重复或空参数。文件路径含空格时，须按 Maven 参数转发规则为路径值额外加引号。此入口加载非 Web 应用上下文，完成后关闭并退出；不加载问答 Controller，也不要求 T07 的 `QaService` 已实现。

换用 `conflict.json` 或 `mutation.json` 时修改文件路径。一次只导入一份完整资料集合；每次执行均创建新 UUID 快照，不覆盖或续传旧快照。

## 执行、发布与退出

入口先完成整份 JSON、条款和分词准备，再创建 `BUILDING` 快照。文档按批写入，片段按既有 Embedding 批量上限顺序生成向量并写入；模型请求不在数据库事务中。完整导入后核对文档数、片段数与集合哈希，再发布为 `READY`。

成功退出码为 0，输出实际 `snapshotId`、`contentHash`、`documentCount` 和 `chunkCount`。参数错误退出码为 2，其他导入或启动失败为 1；错误输出包含类别、固定说明和可用的文档、条款或快照标识，以及异常因果类型和项目代码位置。诊断不输出原始异常消息、凭据、上游响应或资料全文；导入主类默认关闭框架日志，避免默认启动错误报告回显配置值。

总预算独立于问答预算，使用单调时钟。Embedding 默认单批最多 32 条、单条最多 4000 个 Java 字符、单次 15 秒，数据库单次默认 5 秒；每次调用受剩余总预算收紧。解析和本地准备在检查点核对预算，不承诺在某个字符读取中间强行中断；连接等待限制沿用 T03。

失败时不自动重试、不清理失败快照。若已创建且尚未发布，保留为 `BUILDING`；再次执行使用新快照完整重导。创建或发布调用的响应失败时，服务端状态可能尚未得到确认，按输出的快照 ID 核查实际状态，不将错误反馈视为数据库已经回滚的证明。

发布后，后续新问答使用最新发布的快照，正在执行的请求仍使用原快照。建议先导入正常组，记录 E01/E04/E05；再导入冲突组，记录 E02；E08 需先重新导入正常组并记录，再导入替换组并记录。不要在发布全部三组后假定问答会根据用例自动选择资料。

## 分词资源

导入和 T05 查询共同调用 `KnowledgeTokenizer`，实现为 `JiebaKnowledgeTokenizer`。默认使用 jieba 1.0.2 内置词典，不启用额外停用词；保留词序与重复词，过滤纯标点及空白，字母转为 `Locale.ROOT` 小写。向量输入仍使用完整原始条款。

自定义资源支持普通本地路径、`file:` URI 或 `classpath:` 路径，文件使用严格 UTF-8。空白行及以 `#` 开头的注释行跳过；配置了路径却无法读取、没有有效条目或格式错误时停止初始化。命令行从 Spring 启动包装中识别本模块的配置异常，以 `INVALID_ARGUMENT` 输出具体的安全资源说明；其他未知启动错误保留固定提示与脱敏类型诊断。

- 自定义词典每行使用 `词语 频率`，可额外带一个词性字段；频率为有限正数，同词不重复。
- 停用词每行一个词，不含内部空白。制度中的否定词、数字和期限单位通常需要保留；首版不提供会删去这些内容的停用词表。
- 设置 `JIEBA_DICTIONARY_LOCATION` 时，将 `JIEBA_DICTIONARY_VERSION` 改为实际资源版本；设置 `JIEBA_STOP_WORDS_LOCATION` 时，同样更新 `JIEBA_STOP_WORDS_VERSION`。没有自定义路径时分别使用 `builtin-v1`、`none-v1`。
- 现有实现只接受 `INDEX_PREPROCESSING_VERSION=source-preserving-v1` 和 `JIEBA_TOKENIZER_VERSION=jieba-analysis-1.0.2`，不以修改标签冒充新算法。资源与版本在进程启动时固定，修改后重启并建立新快照。

资料快照记录完整 `IndexProfile`。T07 在建立问答上下文时须按 T01 核对配置；模型、分词或资源版本变化时重建索引。jieba 的自定义词典加载限制见 [T04 词典踩坑记录](../docs/pitfalls/T04-jieba-dictionary-loading.md)；本机 Java 代理导致 `127.0.0.1` 连接失败时的排查见 [本机代理踩坑记录](../docs/pitfalls/T04-java-local-proxy.md)。
