# T01：枚举注解不自动禁止数字序数

## 现象

子代理静态审查发现：仅给 Role 常量添加 `@JsonProperty("user")`、`@JsonProperty("assistant")` 并给字段加 `@NotNull`，不足以保证 HTTP 只接收这两个字符串。Jackson 默认允许按数字序数反序列化枚举，`role: 0` 可能成为 USER 并通过非空校验。此结论来自静态规则核查，未运行解析用例。

## 环境

Java 17、Spring Boot 3.5.10，Jackson 由 Boot 管理；HTTP 请求与 AgentScope 模型输出可能使用不同的 ObjectMapper。T01 契约要求角色、任务状态及原因按明确字符串表达。

## 原因

`@JsonProperty` 定义名称映射，`@NotNull` 只校验最终值是否为空；二者不关闭数字序数的转换。只调整 Spring 的 ObjectMapper，也不能自动覆盖框架内部创建的 ObjectMapper。

## 解决方案

在 Role、TaskStatus 和 AnswerReason 上分别声明显式字符串 `@JsonCreator`：角色只接受 user/assistant，状态和原因只接受枚举精确名称。数字转换成的字符串也无法匹配，直接拒绝；无效模型草稿仍由接收边界判定为 OUTPUT_INVALID。未新增全局 Mapper 改写或通用枚举解析层。授权测试后再覆盖数字、未知值、大小写变体、null 和正常字符串，目前不声称已运行通过。
