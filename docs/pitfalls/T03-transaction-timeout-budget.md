# T03：事务超时不自动覆盖连接等待与整秒取整

## 现象

静态核对 Spring JDBC 和 MyBatis 源码时发现：只给 `TransactionTemplate` 设置超时，不能据此认定一次数据库调用的总耗时受传入 `Duration` 严格约束。连接获取可能先消耗时间；事务剩余时间换算为 JDBC 秒数时，也可能向上取整。当前未运行真实数据库，未观察或复现运行故障。

## 环境

Java 17、Spring Boot 3.5.10、MyBatis starter 3.0.5、PostgreSQL JDBC。T01 默认 Hikari 连接等待上限 2000 毫秒，JDBC socket 和 MyBatis 语句兜底均为 5 秒。调用方传入的单次能力预算可能更小。

## 原因

Spring JDBC 在事务开始时取得连接，再为连接资源设置事务截止时间；连接等待不会自然从这份事务时间中扣除。MyBatis 将事务剩余秒数用于语句超时，而 Spring 资源持有者的秒数换算允许向上取整。T01 已约定向下取整且不足一秒不启动查询，因此仅依赖默认行为不能满足约定。具体实现参见 [Spring JDBC 事务源码](https://github.com/spring-projects/spring-framework/blob/v6.2.15/spring-jdbc/src/main/java/org/springframework/jdbc/datasource/DataSourceTransactionManager.java)、[Spring 资源剩余时间源码](https://github.com/spring-projects/spring-framework/blob/v6.2.15/spring-tx/src/main/java/org/springframework/transaction/support/ResourceHolderSupport.java)及 [MyBatis 语句超时源码](https://github.com/mybatis/mybatis-3/blob/mybatis-3.5.19/src/main/java/org/apache/ibatis/executor/statement/BaseStatementHandler.java)。源码阅读不等于实际依赖树已经验证。

## 解决方案

`PgKnowledgeStore` 为每次数据库调用建立独立短事务。获取连接后，从传入预算中减去单调时钟测得的已耗时间，再设置连接资源的剩余事务时间。`PgStatementTimeout` 在每次准备语句前重新读取剩余毫秒，向下换算为正整数秒；不足一秒时抛出明确预算错误，不把 0 传给 JDBC。

该拦截器只使用同步事务自身的连接资源；它不保存模型请求状态，也不要求预算在响应式线程之间通过 ThreadLocal 传播。模型调用、分词和向量生成不放入这份数据库事务。

连接池等待仍由全局 Hikari `connection-timeout` 限制。若调用预算小于这份连接等待上限，连接获取本身可能先超过预算；返回连接后会拒绝继续查询。外层问答截止时间仍由 T07 管理，不能把取消订阅解释为已经中断 JDBC 或连接获取。连接等待、锁等待、超时取消和回滚的实际行为均留待用户授权后的数据库验证。
