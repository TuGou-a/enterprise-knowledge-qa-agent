# T02 模型客户端设计与交接

## 状态与范围

- 基线：`dev` 的 `ccdda70`；分支：`task/t02-model-clients`。
- 用户已确认全部设计并授权实现；代码已完成，当前等待子代理静态审查。尚未编译、启动或测试，也未调用真实模型。
- 实现范围为 `infra/model/**` 中的硅基流动 Embedding 和 Rerank 客户端；沿用 [T01 契约](08-t01-contracts.md)。DeepSeek 归 T07，RRF 回退和稳定排序归 T05，资料切分与导入分批归 T04。
- 开发完成后由子代理进行代码审查，修复后合并至 `dev` 并删除 worktree。编译、启动、依赖树、测试及真实调用等待用户另行指令。

## 已确认的设计决定

1. 使用 Java 17 自带 `HttpClient` 和现有 Jackson，提供两个同步 Spring Bean。仅复用 HTTP 调用与错误转换等真正共用的逻辑，不新增 SDK、通用模型框架或 Maven 依赖。
2. 保留现有字符限制，Embedding 与 Rerank 输入原样发送，不截断原文。本轮不引入 tokenizer；`bge-m3` 的 8192 token 限制不能用 4000 个 Java 字符代替。上游拒绝输入时明确失败，由 T04 调整条款切分。
3. 核心模型结果必须整批有效。向量或排序结果缺失、重复/越界下标、非法数值均为 `INVALID_RESPONSE`；不得返回部分结果、零向量或伪造成功。响应 ID 与计量允许缺失，保留 `null`。Rerank 失败由 T05 依据明确错误整批回退。
4. 输入处理、连接、完整响应读取、JSON 解析和结果校验共用传入的一份预算，使用单调时钟计时并在返回前复核。`timeout` 缺失为 `INVALID_ARGUMENT`，非正为 `BUDGET_EXHAUSTED`；超时取消 HTTP 请求，线程中断时保留中断标记并停止。本轮始终只发起一次请求。
5. 输入格式错误使用 `INVALID_ARGUMENT`；数量或字符超限使用 `INPUT_LIMIT_EXCEEDED`。Rerank query 使用 `queryCharacters`，候选数量使用 `candidatePoolLimit`，全文累计使用 `rerankCharacters`，`topN` 必须位于 1 至候选数，不在客户端静默夹取或拆批。
6. HTTP 错误按下表映射；仅 `TIMEOUT`、`RATE_LIMITED`、`UPSTREAM_UNAVAILABLE` 标为 `retryable=true`，只表示后续可再次尝试，不触发本轮自动重试。对外使用固定脱敏消息，不携带原始错误体、请求头、原文或密钥。
7. 空白响应 ID 规范为 `null`；已返回的计量必须为非负整数，否则整批 `INVALID_RESPONSE`。Embedding 使用 `usage.prompt_tokens/total_tokens`，Rerank 使用 `meta.tokens.input_tokens`；只有服务明确给出的总数才能填入 `totalTokens`，不根据其他计数推算。

| 情况 | Failure.Code |
| --- | --- |
| HTTP 400/422 | `INVALID_ARGUMENT` |
| HTTP 413 | `INPUT_LIMIT_EXCEEDED` |
| HTTP 401/403 | `AUTHENTICATION_FAILED` |
| HTTP 408/504 或本地调用超时 | `TIMEOUT` |
| HTTP 429 | `RATE_LIMITED` |
| 其他非成功状态或网络连接失败 | `UPSTREAM_UNAVAILABLE` |
| JSON/核心结果/已返回计量无效 | `INVALID_RESPONSE` |

## 实现约定

- `base-url` 表示完整 API 根路径。保留配置中的 `/v1` 或自定义前缀，去掉尾部斜杠后追加 `/embeddings` 或 `/rerank`，不推测或自动补版本路径。
- 复用已校验的 `AgentProperties`，不修改 T01 公共类型、依赖或配置。两个客户端之间只共享协议调用、错误转换及确实共用的边界处理。
- 不新增业务术语：Embedding、HTTP 超时及错误分类均为通用技术概念；业务术语表保持其既有定义。上述实现选择可局部替换，不单独创建 ADR。
- 用户已确认共同理解并授权按本设计开发。实现只使用既有共享接口与配置；新增文件见下文。

## 资料依据

- [官方 Embedding API](https://docs.siliconflow.cn/docs/api/embeddings-post)：`bge-m3` 上限为 8192 tokens；请求采用 `encoding_format=float`，不发送仅部分模型支持的 `dimensions`。
- [官方 Rerank API](https://docs.siliconflow.cn/docs/api/rerank-post)：使用文本 query/documents、top_n 与 return_documents；当前示例通过 `meta.tokens.input_tokens` 等字段提供计量。协议核查不代表真实服务调用通过。

## 实现内容与调用交接

| 文件 | 职责 |
| --- | --- |
| `infra/model/SiliconFlowEmbeddingClient.java` | 校验批次输入，发送 float 编码请求，按 index 还原输入顺序，通过 DenseVector 验证 1024 维和有限分量。 |
| `infra/model/SiliconFlowRerankClient.java` | 校验查询、候选全文和 topN，发送 return_documents=false，验证结果数量、唯一下标及有限分数，保留服务返回顺序。 |
| `infra/model/SiliconFlowHttpClient.java` | 复用 HTTP 连接、类型明确的请求协议、严格 JSON 读取、单次调用预算、可选计量解析和脱敏错误映射。 |

三个组件均由 Spring 扫描注册。T04/T05 按 `EmbeddingClient`、`RerankClient` 注入，无需依赖实现包；单次 timeout 仍由调用方取能力配置与当前剩余预算的较小值。客户端不注册请求级可变状态，启动不调用模型。既有连接超时配置作为建连上限，完整响应 future 的限时等待覆盖预算更短的调用。

T04 在客户端外拆分导入批次，不将问答总预算用于完整导入；T05 把 Rerank inputIndex 映射到本次候选稳定键，再按分数、RRF 名次和稳定键处理排序。客户端不读取服务返回文档文本，不修改原文，不做回退或排序业务决策。

## 静态审查与待验证项

- 基于 `ccdda70` 的主代理实现完成；待子代理分别核对项目规范与 T02 契约，发现问题由主代理修复。
- 尚未执行编译、启动、依赖树、测试、Mock 冒烟或真实接口调用。代码阅读和 Git 差异检查不是运行验证。
- T09 后续应覆盖乱序 Embedding、缺项/重复/越界/非整数下标、维度错误、非数值/非有限分量与分数、不完整 Rerank、空白/超长输入、预算耗尽、中断、HTTP 错误映射、缺失/非法计量及尾斜杠地址。
- 完整响应读取的预算与取消说明见 [T02 HTTP 响应体与预算](pitfalls/T02-http-response-budget.md)。JSON 解析和本地校验在返回前检查预算，但不会强行中断已开始的 CPU 处理；HTTP 取消为尽力而为，不保证上游已经停止计费或计算。
- 单文本字符上限不能证明 token 数符合模型限制；真实服务可用性、模型支持和 Spring/Jackson 装配均仍待用户授权验证。
