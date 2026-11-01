# T09：Spring 动态属性早于 Testcontainers 启动

## 现象

运行包含 `@SpringBootTest` 的 PostgreSQL 集成测试时，Docker 已经可用，但应用上下文在加载阶段失败，最终原因为：

```text
Mapped port can only be obtained after the container is started
```

## 环境

- Java 17 编译目标，本机运行 Java 21
- Spring Boot 3.5.10
- JUnit Jupiter
- Testcontainers 1.21.4
- 已启动的 Docker
- `pgvector/pgvector:pg17` 容器
- 同时使用 `@Testcontainers`、静态 `@Container` 和 `@DynamicPropertySource`

## 原因

Spring 扩展可能先初始化应用上下文，再由 Testcontainers JUnit 扩展启动静态容器。`DynamicPropertyRegistry` 中的 supplier 是延迟求值，但 Spring Boot 在判断数据源配置等条件时会提前读取 `spring.datasource.url`。此时 `POSTGRES::getJdbcUrl` 会访问尚未启动容器的映射端口，因此抛出上述异常。

## 解决方案

在 `@DynamicPropertySource` 方法开头显式调用 `POSTGRES.start()`，再注册 JDBC URL、用户名和密码。这样 Spring 首次读取动态属性前容器已经启动；Testcontainers 扩展仍负责后续生命周期和关闭。测试文件同时保留一条注释，说明该启动顺序不能删除。

该问题属于 Spring 与 JUnit 扩展的初始化顺序边界，不是 Docker 未启动或 pgvector 镜像问题。
