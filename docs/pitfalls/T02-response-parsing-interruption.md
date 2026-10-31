# T02：响应解析与结果校验期间的线程中断

## 现象

HTTP 响应完整到达后，在 JSON 解析期间设置调用线程的中断标记，`embed()` 仍返回完整的 `EmbeddingBatch`。原问题已在 Java 17 的本地 HTTP 桩测试中复现：中断标记得到保留，但调用没有停止。

## 环境

Java 17、Spring Boot 3.5.10、Jackson 2.19.4。硅基流动 Embedding 和 Rerank 客户端共用 JDK `HttpClient`、JSON 解析及调用预算，调用方通过 `Duration` 传入剩余时限。

## 原因

原实现只在发起请求前检查中断，并在等待 HTTP Future 时处理 `InterruptedException`。Future 完成后，JSON 解析和向量、分数等本地校验不会自动响应线程中断；后续预算检查仅判断超时，因此可能将已经中断的调用作为成功结果返回。

## 解决方案

将中断检查集中到两个客户端共用的 `CallBudget.remainingNanos()`。每个现有预算检查点先判断超时，再通过 `Thread.currentThread().isInterrupted()` 检查中断；中断时沿用 `UPSTREAM_UNAVAILABLE` 和固定错误消息，保留中断标记。这样覆盖响应读取后、JSON 解析后，以及两个客户端完成结果校验后的返回边界。

该处理采用协作式取消，在下一个检查点停止，不强行终止正在执行的解析代码。HTTP 请求尚未完成时，仍由既有 `finally` 取消 Future。

## 回归方式

`SiliconFlowInterruptionTest` 使用本地 HTTP 桩和可控 `ObjectMapper`，分别在 JSON 解析阶段及响应元数据校验阶段设置中断，覆盖 Embedding 和 Rerank 共四种组合。每种组合均要求抛出既有中断异常并保留标记；测试在 `finally` 中清理标记，避免影响其他用例。

```bash
mvn -Dtest=SiliconFlowInterruptionTest test
```
