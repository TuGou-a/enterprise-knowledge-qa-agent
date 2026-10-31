# T08：HTTP JSON 类型与 MVC 异步超时边界

本文依据代码和官方资料做静态核查，尚未执行反序列化、启动、接口调用或超时验证。

## 字符串字段的非空校验不能代替 JSON 类型校验

**现象：**严格请求契约要求 `question` 和历史 `content` 使用 JSON 字符串。仅使用 `String` 与 `@NotBlank`，不能据此认定数字或布尔输入会被拒绝；原始字段被转换成字符串后仍可能通过非空校验。

**环境：**Java 17、Spring Boot 3.5.10、Boot 管理的 Jackson、Spring MVC；共享 `QaRequest` 已有 Bean Validation，T07 还需单独解析模型草稿。

**原因：**Jackson 的字符串反序列化有标量强转规则，Bean Validation 接收到的是反序列化后的值。Boot 的未知字段策略也不能直接当作本接口的严格字段契约。依据见 [Jackson 2.19.4 字符串解析实现](https://github.com/FasterXML/jackson-databind/blob/jackson-databind-2.19.4/src/main/java/com/fasterxml/jackson/databind/deser/std/StdDeserializer.java)。

**解决方案：**从 MVC JSON Converter 的 Mapper 创建副本，仅为 `QaRequest` 注册。对 Textual 类型的 Integer、Float、Boolean 输入指定 `CoercionAction.Fail`，启用未知字段与尾随 JSON 内容拒绝；空历史元素在反序列化边界拒绝。保留 `history` 缺省/null 的既有语义，继续由 Bean Validation 检查必填和非空白。不要修改共享 Mapper，避免改变模型草稿解析。类型专属注册依据见 [Spring Converter API](https://docs.spring.io/spring-framework/docs/6.2.15/javadoc-api/org/springframework/http/converter/json/AbstractJackson2HttpMessageConverter.html)。授权后仍需通过 HTTP 用例核对实际行为。

## MVC 等待超时可能早于 Agent 总预算

**现象：**服务契约返回 `Mono`，问答预算默认 120 秒，但若 HTTP 等待先结束，T07 可能来不及返回含真实执行状态的任务结果。当前尚未运行复现。

**环境：**`spring-boot-starter-web` 的 Spring MVC，异步返回单次最终结果；共享配置原本没有显式声明 MVC 异步超时。

**原因：**Spring MVC 的默认异步超时取决于底层 Servlet 容器，问答预算配置不会自动变成 MVC 的等待时间。依据见 [Spring 6.2 异步请求说明](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-async.html)。

**解决方案：**在 T08 的 `WebMvcConfigurer.configureAsyncSupport` 中将默认异步超时设为问答预算加 5 秒。T07 先按应用预算完成结果组装，MVC 超时仅作 504 兜底，不在 HTTP 层伪造 `INCOMPLETE`。授权后验证预算结束与异步分派顺序；取消订阅仍不代表底层阻塞 I/O 已立即结束。
