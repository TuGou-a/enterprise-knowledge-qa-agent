# T09 测试配置被其他测试扫描

## 现象

新增真实检索评估的配置位于 `knowledge.ingest` 包。静态审查发现，既有 T05 PostgreSQL 测试会对同一个包执行 `@ComponentScan`；若直接添加普通 `@Configuration`，它可能被 T05 扫描，继而注册真实模型客户端，与 T05 的受控客户端产生 Bean 歧义或改变测试边界。这是静态识别的风险，未运行复现。

## 环境

Spring Boot 3.5.10，JUnit Jupiter，测试类与业务包位于同一测试 classpath。`T05PostgresIntegrationTest.Config` 扫描资料导入包，并提供受控 Embedding／Rerank；`RealRetrievalEvaluationTest.Config` 需要扫描真实模型客户端。

## 原因

JUnit 标签只决定是否运行测试，不限制 Spring 的组件扫描。即使默认排除了 `real` 标签，带 `@Configuration` 的嵌套配置仍可能被其他测试上下文发现。

## 解决方案

真实评估配置增加 `@Profile("real-evaluation")`，只有真实评估测试通过 `@ActiveProfiles("real-evaluation")` 启用。JUnit 标签继续控制执行入口，Spring profile 负责配置可见性。HTTP 测试使用独立 MockMvc 配置，不扫描真实应用、数据源或模型客户端。

后续需在合并后获得测试指令，分别执行默认测试和 `postgres-it`，确认没有真实客户端混入。当前只完成静态核对，不宣称运行验证通过。
