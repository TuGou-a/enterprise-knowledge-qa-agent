# T02：完整 HTTP 响应与调用预算

## 现象

如果使用“取得响应头后就返回”的响应处理方式，随后在另一个阶段阻塞读取响应体，单独限制等待响应头的时间可能无法约束完整模型调用。上游已返回状态码、但响应体停滞时，工具可能长期占用线程并超过剩余预算。本项目未运行复现；此处记录接口语义核查与实施处理，实际取消效果待验证。

## 环境

Java 17、Spring Boot 3.5.10，T02 使用 JDK HttpClient 调用硅基流动 Embedding/Rerank。调用方通过 Duration 传入能力配置和剩余预算的较小值；输入、HTTP、解析和结果校验共享这一份预算。

## 原因

响应头到达与响应体完整到达是不同事件。JDK 的 BodyHandlers.ofInputStream 在响应体尚未全部读取时即可提供响应；因此不能把响应 future 完成一概当作正文已经完整读取，也不能只依赖外层 Reactor 取消去结束独立的阻塞 I/O。

## 解决方案

T02 使用 sendAsync 配合 BodyHandlers.ofByteArray，再按单调时钟计算的剩余时间等待 future，完整正文到达后才解析 JSON。请求自身设置 timeout，HttpClient 设置连接超时；超时或线程中断且 future 未完成时取消请求，线程中断标记继续保留。解析前后和最终协议校验完成后检查同一份预算，不把每个阶段都重新赋予完整时限。

该方案不会强行打断正在执行的本地 JSON 解析或数值校验，只会拒绝返回过期结果；取消 HTTP 请求也不保证上游已经停止处理。授权后应使用“立即返回响应头、随后延迟正文”的可控服务验证等待上限和取消行为，不以静态阅读代替运行结论。

依据：[JDK 17 BodyHandlers.ofInputStream](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpResponse.BodyHandlers.html#ofInputStream())、[JDK 17 HttpRequest.Builder.timeout](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html#timeout(java.time.Duration))。真实模型调用、编译和测试均尚未执行。
