# T08 HTTP 接口设计与交接

本文补充 [T01 契约](08-t01-contracts.md)未展开的 HTTP 输入与错误边界。下列设计已由用户在本轮需求澄清中确认；运行表现仍待授权验证。

## 任务范围与基线

- 任务分支：`task/t08-http`，基线为 `dev` 的 `ccdda70`。
- 实现范围：`src/main/java/com/mango/backend/qa/api/**`，复用 `QaRequest`、`QaService`、`AnswerEnvelope` 与 `Result`，不修改共享类型或实现 T07 问答服务。
- 入口：`POST /api/qa`，返回单次最终 JSON 结果。长度限制和主要 HTTP 状态沿用 T01。
- 当前 T07 尚未交付服务实现。T08 的静态交接不代表接口已能启动、完成问答或通过运行验收。
- 开发完成后由子代理审查，合并至 `dev` 并删除任务 worktree；编译、启动、测试与真实接口验证等待用户后续指令。

## 已确认：严格校验 JSON

- 拒绝字段类型与契约不符的输入，不将数字或布尔值自动转换为字符串。
- 拒绝请求对象及历史消息对象中的未知字段，例如拼写错误的 `histroy`。
- 格式、类型或未知字段错误返回 HTTP 400。
- 错误提示指出字段或约束，不回显用户原文。
- `history` 缺省或为 `null` 时，沿用 `QaRequest` 的空列表语义。

## 已确认：保留对话历史

- 不要求 `user` 与 `assistant` 交替，也不限定第一条或最后一条历史的角色。
- 保留调用方提交的历史顺序与原始文本，不自动配对、重排、删减、裁剪或去除首尾空白。
- 历史角色只能为 `user` 或 `assistant`，内容非空白；按配置校验条数、单条长度和累计长度。
- 当前问题同样保留原始文本并校验非空白与长度。字符数采用 Java `String.length()`；超限明确拒绝，不静默截断。
- 历史仅用于理解追问，不能直接作为制度依据；服务端不保存跨请求会话。

## 已确认：错误与任务结果分开表达

- T07 已返回的 `AnswerEnvelope` 统一由 `Result.success` 包装，不因任务为冲突、无依据或未完成而改写 HTTP 状态。
- 以下映射只处理尚未转换为可靠任务结果的 `DependencyException`。T08 不推测运行阶段，也不自行生成缺少真实上下文的 `INCOMPLETE` 结果。

| `Failure.Code` | HTTP |
| --- | --- |
| `TIMEOUT` | 504 |
| `SNAPSHOT_UNAVAILABLE`、`INDEX_PROFILE_MISMATCH` | 503 |
| `RATE_LIMITED`、`AUTHENTICATION_FAILED`、`UPSTREAM_UNAVAILABLE`、`INVALID_RESPONSE`、`DATABASE_UNAVAILABLE` | 502 |
| `NOT_FOUND`、`INVALID_ARGUMENT`、`INPUT_LIMIT_EXCEEDED`、`BUDGET_EXHAUSTED`、`INTERNAL_ERROR` | 500 |

- 请求自身的格式、类型、必填及非空白校验失败返回 400；配置长度超限返回 413。服务内部异常不按请求输入错误处理，取舍见 [ADR 0003](adr/0003-http-failure-boundary.md)。
- 未预期异常返回 500。MVC 的方法、媒体类型等错误保留框架 HTTP 语义，例如 405、406、415，并统一响应外层。
- 错误响应为 `Result<Void>`，`code` 与 HTTP 状态一致，`data` 为 `null`。输入错误指出字段或限制；其他错误使用固定提示，不回显原始输入、上游错误体或堆栈。
- 日志只记录依赖错误类别或未处理异常类型，不记录异常消息、原始请求、堆栈或配置值。
- 若问答服务以空 `Mono` 结束，视为未能提供任务结果，返回 500，避免以空响应表示成功。

## 已确认：MVC 异步超时兜底

- MVC 异步超时取已配置的问答总预算加 5000 毫秒，默认 125 秒；T07 仍负责在其默认 120 秒预算内形成完整任务结果。
- MVC 超时返回脱敏的 HTTP 504，不伪造任务结果。HTTP 层不另启问答执行、不自动重试、不阻塞等待或重复订阅。
- 在 `qa/api` 的 `WebMvcConfigurer` 中设置 MVC 默认异步超时，沿用共享的 `AgentProperties`，不修改 T01 配置文件。
- Spring MVC 默认异步超时取决于底层 Servlet 容器；依据见 [Spring 6.2 官方说明](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-async.html)。

## 实现与后续静态交接

- `QaController`：执行配置长度校验，保留原始请求内容，通过 `Mono` 调用 T07 并包装最终响应。
- `QaWebConfiguration`：为 `QaRequest` 注册独立的 HTTP JSON Mapper 副本，拒绝未知字段和数字、布尔值向字符串强转；同时配置 MVC 超时。共享 Mapper 和模型草稿解析规则保持原有行为。
- `QaExceptionHandler`：统一输入、依赖、MVC 和未预期异常响应；不复用异常原始消息构造公开系统错误。
- `QaInputLimitException`：仅标识 HTTP 输入的配置长度超限，消息只包含字段和服务端上限。
- T07 须提供 Spring 可发现的 `QaService` Bean，内部可解释失败由其组装 `AnswerEnvelope`；T08 不提供占位服务。
- T09 后续编写 HTTP 用例：有效输入、全部五类任务状态、历史缺省/null/连续同角色、必填及类型错误、未知字段、各长度边界、全部依赖错误码、空结果及 MVC 超时。运行时需执行 MVC 异步分派，不能只核对第一次返回。

## 当前交付状态

T08 代码已实现，尚待子代理静态审查与合并。未执行编译、启动、测试、依赖树或真实接口调用；缺少 T07 实现时不具备完整启动与问答条件。
