# T04 资料与导入运行验证

## 范围与结论

验证时间为 2026-10-31。主要导入与失败路径验证开始时 `dev` 位于 `f6bf786`；这些运行发现重复 JSON 字段缺少行列位置。修复提交 `ba3f7a6` 以 `d0c70d4` 合并后，再完成全量测试、打包及错误定位回归。T04 的编译、全量测试、PostgreSQL／pgvector 初始化、三组资料导入、严格输入失败路径、分词资源、快照发布和失败隔离均已执行。

外部 Embedding 服务使用本地受控 HTTP 桩替代，固定返回 1024 维非零向量。因此本次证明了应用装配、HTTP 客户端、数据库、Mapper、分词、哈希、批处理和发布链路，不能证明真实硅基流动账号、网络和模型质量。未提供真实模型密钥，也未调用 DeepSeek 或 Rerank。

## 环境

- macOS，Asia/Shanghai
- Java 21.0.12，Maven 按 `release 17` 编译
- Maven 3.9.9
- PostgreSQL 17.11，pgvector 0.8.6
- 临时数据库集群仅监听 `127.0.0.1:55432`
- 受控 Embedding 桩仅监听 `127.0.0.1:55433`

## 编译、测试与打包

| 检查 | 结果 |
| --- | --- |
| `mvn -DskipTests compile` | 通过，55 个主源码文件按 Java 17 编译 |
| `mvn test` | 通过，48 个测试，0 失败、0 错误、0 跳过 |
| `mvn -DskipTests package` | 通过，生成 `target/backend-0.0.1-SNAPSHOT.jar` |
| `mvn dependency:tree -Dscope=runtime` | 通过，依赖关系可解析 |

首次在受限沙箱内运行测试时，4 个内嵌 HTTP 服务用例因本机端口绑定被拒绝而报错；在沙箱外以相同命令重跑后全部通过。该差异属于执行环境限制。

## 数据库与资料导入

项目初始化 SQL 在临时 PostgreSQL 中一次执行成功，创建 pgvector 扩展、三张 `kb_*` 表和三个索引。

| 输入 | 快照 ID | 状态 | 文档 | 片段 | 集合哈希 |
| --- | --- | --- | --- | --- | --- |
| `normal.json` | `7399a8d4-3557-4cd5-a950-b107efce5f0f` | `READY` | 2 | 7 | `f6dd50e861105ab38b1bed3d18c62e71322f73b53611e064419361c74c866de7` |
| `conflict.json` | `8e91f066-9125-4abc-85a6-ac60ceec720a` | `READY` | 3 | 8 | `f7f42a3d39c26838b26ac74c241de71631309355cb1a7e37b3705bc447f70d36` |
| `mutation.json` | `ee933887-f3cf-4bc8-84a2-0a045eb61249` | `READY` | 2 | 7 | `437c3055fbad7ed61f2bcb5bf654165df5402a1442b7b22aae115fc14db9b15f` |

三份快照均保留。冲突组中，`D01 v1` 与 `D02 v1` 分别包含 30 天和 15 天规则，二者共用中性主题「报销期限」；替换组只包含 `D01 v2` 的 20 天规则，未保留旧版 `D01 v1`。每次导入使用新 UUID，后续新问答只选择最新 `READY` 快照。

文档和片段的分布满足三组资料的预期：正常组 2／7，冲突组 3／8，替换组 2／7。受控 Embedding 日志记录对应的 3 次请求，批大小分别为 7、8、7，模型名为 `BAAI/bge-m3`。

随后通过实际 Maven `spring-boot:run` 入口再次导入 `normal.json`，成功创建快照 `af94b57b-cacc-461f-b4d7-2eacaaae0ccd`，证明交付文档记录的启动方式可执行。自定义词典和停用词导入创建 `6a92171c-1db7-4d2a-9d53-f52b746ebb39`，快照记录 `test-dict-v1`／`test-stop-v1`；`自然日` 成为完整词元，停用词 `的` 未进入 `tsvector`。

对当时全部成功导入的数据执行完整性查询：36 个片段均为 1024 维非零向量，关键词索引非空，36 个片段哈希与 13 个文档哈希全部匹配，36 个片段的 UTF-16 偏移均能精确切回原文，未发布快照中没有片段。

## 失败路径

| 场景 | 结果 |
| --- | --- |
| 未知 JSON 字段 | `INVALID_ARGUMENT`，指出字段路径与行列，未创建快照 |
| 重复 JSON 字段 | `INVALID_ARGUMENT`，指出字段路径与行列，未创建快照 |
| 缺少条款主题 | `INVALID_ARGUMENT`，指出文档及缺失条款，未创建快照 |
| 重复文档版本 | `INVALID_ARGUMENT`，指出重复文档，未创建快照 |
| 文件不存在 | `INVALID_ARGUMENT`，退出码 1，未创建快照 |
| 非正数导入预算 | `INVALID_ARGUMENT`，退出码 2 |
| 未知命令行参数 | `INVALID_ARGUMENT`，退出码 2 |
| 1 毫秒总预算 | `BUDGET_EXHAUSTED`，创建前终止 |
| 自定义词典不可读 | `INVALID_ARGUMENT`，在上下文启动边界终止 |
| Embedding 地址不可用 | `UPSTREAM_UNAVAILABLE`，快照 `32579e23-7bc4-4b18-83a0-bac08285244d` 保留 `BUILDING`、2 个文档、0 个片段 |

运行验证发现重复 JSON 字段只报告路径、没有使用 Jackson 已提供的行列位置。修复已合并在 `d0c70d4`：修复后重复字段输出 `documents.[0]（第 4 行，第 40 列）`，未知字段输出 `documents.[0].unknownField（第 16 行，第 27 列）`，失败前后快照总数保持不变。

## 未覆盖与后续边界

- 没有真实模型密钥，未验证硅基流动线上 Embedding、限流、真实延迟和向量语义质量。
- T04 不调用 DeepSeek 或 Rerank；相关客户端及 Agent 的真实决策仍待后续任务。
- 默认 Web 应用已启动到 Tomcat 初始化阶段，但当前 `dev` 尚未合并 T07 的 `QaService` 实现，应用因缺少对应 Bean 终止。这是后续任务依赖，不是 T04 导入失败。
- 项目当前没有聊天前端页面，Playwright 没有可运行的 UI 场景。T04 使用非 Web 命令行入口完成端到端验证。
- 数据库和模型 HTTP 服务均为本地测试资源；验证结束后已停止，临时集群目录、模型桩脚本、请求日志和测试夹具均已删除。

上述限制不得被表述为真实线上模型、完整问答或 HTTP 验收已经通过。
