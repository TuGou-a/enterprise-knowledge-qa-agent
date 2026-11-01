# T05 知识检索与读取运行验证

## 范围与结论

验证时间为 2026-11-01，`dev` 位于 `3a13376`。T05 的逻辑级验证、真实 PostgreSQL／pgvector 集成验证、现有测试与打包均已执行。实现在受控 Embedding／Rerank 服务下完成两路召回、RRF、完整候选池重排序、排序失败回退、主题扩展、固定快照读取与证据登记；没有发现需要修改 T05 实现的运行缺陷。

模型服务使用本地确定性 HTTP 桩，不能证明真实硅基流动模型的相关性排序质量、网络延迟、限流或账号可用性。T07 尚未合并，本次没有完整 Agent 工具循环、HTTP 问答或真实 DeepSeek 调用。

后续已把关键行为沉淀为永久 JUnit：`HybridSearchServiceTest`、`KnowledgeEvidenceReaderTest` 和 `T05PostgresIntegrationTest`。集成测试使用 Testcontainers 启动 Docker 中的 `pgvector/pgvector:pg17`，执行正式 schema 并导入 `normal.json`、`conflict.json`；测试尚未运行。

## 永久 JUnit 审查

审查基线为 `89fbce0`，初版测试提交为 `742d7b9`。两名子代理分别检查规范与契约，未发现生产代码问题或阻断项。审查补充了 RRF 输入顺序、重复候选双路排名与分数、稳定并列、keyword 单路失败、全文加载失败回退、Docker 集成候选数量、证据预算参数及失败路径不解除读取限制等断言。

新增测试共 15 个：`HybridSearchServiceTest` 9 个、`KnowledgeEvidenceReaderTest` 4 个、`T05PostgresIntegrationTest` 2 个。规范与契约复核均已闭环；由于项目流程要求合并后等待测试指令，这些 JUnit 尚未编译或执行，不能记录为通过。

## 环境

- macOS，Asia/Shanghai
- Java 21.0.12，Maven 按 `release 17` 编译
- Maven 3.9.9
- PostgreSQL 17.11，pgvector 0.8.6
- 以下历史受控验证使用临时数据库监听 `127.0.0.1:55432`；新增 JUnit 集成测试改用 Docker Testcontainers
- 受控 Embedding／Rerank HTTP 桩监听 `127.0.0.1:55433`

## 测试与打包

| 检查 | 结果 |
| --- | --- |
| `mvn test` | 通过，48 个既有测试，0 失败、0 错误、0 跳过 |
| T05 逻辑级临时验证程序 | 通过，45 项断言 |
| T05 PostgreSQL 集成临时验证程序 | 通过，26 项断言 |
| `mvn -DskipTests package` | 通过，生成 `target/backend-0.0.1-SNAPSHOT.jar` |

首次在受限沙箱内运行 `mvn test` 时，4 个既有内嵌 HTTP 测试因禁止绑定本机端口而报 `SocketException: Operation not permitted`；在沙箱外重跑同一命令后全部通过。该差异属于执行环境限制。

## 逻辑级验证

逻辑级验证使用确定性替身覆盖 T05 服务行为，共 45 项断言：

- 两路重复片段按稳定键融合，RRF 并列使用稳定键；完整融合池交给 Rerank，返回分数映射到本地候选后再截取 topK。
- 单路失败保留另一路候选与错误；双路失败保留两条错误，返回空候选并只登记一次检索。
- 全文批量加载失败或 Rerank 失败时整批回退 RRF，仍执行主题扩展；失败保留为排序警告，不写成必要召回错误。
- 全局主题扩展截断时，各种子主题组均保守标记完整性未确认；主题扩展致命错误向外传播且不产生虚假 `SearchResult`。
- 读取成功先取得原文预算，再登记证据并清除同键读取限制；预算拒绝和片段不存在只登记读取问题，不登记证据。

## PostgreSQL 集成验证

项目初始化 SQL 在临时 PostgreSQL 中一次执行成功。随后通过正式 `IngestionApplication` 入口和受控 Embedding 桩导入两组资料：

| 输入 | 快照 ID | 状态 | 文档 | 片段 | 集合哈希 |
| --- | --- | --- | --- | --- | --- |
| `normal.json` | `0b10fc5a-c21e-48e7-885a-6a09704b27b4` | `READY` | 2 | 7 | `f6dd50e861105ab38b1bed3d18c62e71322f73b53611e064419361c74c866de7` |
| `conflict.json` | `4d2962c7-5e78-4fe3-9da4-ae8ad0e25e45` | `READY` | 3 | 8 | `f7f42a3d39c26838b26ac74c241de71631309355cb1a7e37b3705bc447f70d36` |

临时数据库最终包含 2 个 `READY` 快照、5 个文档和 15 个片段。集成验证通过 Spring 装配实际 `PgKnowledgeStore`、MyBatis Mapper、jieba 分词器、`HybridSearchService`、`KnowledgeEvidenceReader` 和模型 HTTP 客户端，共 26 项断言：

- 最新快照选择为冲突快照，旧正常快照仍为 `READY`。
- 问题「报销期限」在冲突快照内两路召回均成功；8 个融合候选全部送入 Rerank，`topK=1` 最终选择 D01，D02 的 15 天版本保留在主题关联候选中。
- 检索候选和预览没有登记为已读证据；随后读取 D01 的 30 天条款成功，完整原文进入证据集合。
- 受控 Rerank 返回 HTTP 503 时，T05 映射为排序错误并整批回退 RRF；主候选保留，排序输入指标保留，主题扩展继续。
- 相同问题在旧正常快照内不泄漏 D02；跨快照读取 D02 返回 `NOT_FOUND`，未登记证据。

桩日志记录冲突快照发送 8 条重排序候选、正常快照发送 7 条，失败注入请求实际返回 503。日志合计来自两次完整集成运行，不证明并发容量。

## 未覆盖与限制

- 没有调用真实硅基流动 Embedding／Rerank 或 DeepSeek，未验证真实语义相关性、限流、网络延迟与账号。
- 新增 `HybridSearchServiceTest`、`KnowledgeEvidenceReaderTest`、`T05PostgresIntegrationTest` 已写入 `src/test/`；由于项目开发流程要求合并后等待测试指令，本次编码阶段尚未执行这些 JUnit。
- 未验证多并发请求下的独立召回调度器容量、阻塞 HTTP／JDBC 取消是否立即生效或吞吐上限。
- 未运行 T07 Agent 工具循环、T08 HTTP 接口、实际回答、引用支持性和 R01–R10 验收。
- 未完成二元相关条款的人工标注、四种检索方案指标比较或真实模型收益评估。

## 清理

验证结束已停止临时 PostgreSQL 与模型桩，删除 `/private/tmp` 下临时数据库集群、验证程序、桩脚本和日志。仓库未保留测试密钥或临时配置。
