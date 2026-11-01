# T09：环境 SOCKS 代理阻断 Testcontainers 本地 JDBC

## 现象

PostgreSQL Testcontainers 容器已经启动，映射端口可用，但 Hikari 获取第一条连接时失败：

```text
org.postgresql.util.PSQLException: 尝试连线已失败。
Caused by: java.net.UnknownHostException: 127.0.0.1
```

同一环境中的 shell 和 macOS 名称解析可以正常访问该端口，异常链却显示连接经过 `SocksSocketImpl`。

## 环境

- macOS
- Java 21，项目编译目标为 Java 17
- Maven 3.9.9、Maven Surefire 3.5.4
- PostgreSQL JDBC 42.7.9
- Docker Desktop
- `pgvector/pgvector:pg17` Testcontainers 容器
- 终端配置 `HTTP_PROXY`、`HTTPS_PROXY` 和 `NO_PROXY`

## 原因

Maven/Surefire 启动的测试 JVM 中实际存在 `socksProxyHost=127.0.0.1` 和 `socksProxyPort=7897`，具体注入来源尚未验证。PostgreSQL JDBC 建立 Socket 时应用这两个系统属性，即使目标是本机映射端口也发送给 SOCKS 代理。代理无法完成对容器的回环连接，驱动最终抛出 `UnknownHostException`。

`NO_PROXY` 能让 shell 工具绕过 HTTP 代理，但不会自动清除 PostgreSQL JDBC 使用的 SOCKS 系统属性。

## 解决方案

在 PostgreSQL 集成测试首次获取连接前清除当前测试 JVM 的 `socksProxyHost` 和 `socksProxyPort`，使 JDBC 直连容器映射端口；测试结束后恢复原值，避免影响同一 Surefire JVM 中的其他测试。

修复已用独立 PostgreSQL JDBC 连接验证：保留 SOCKS 属性时，`127.0.0.1` 和 `localhost` 均失败；清除两个属性后，两者均连接成功。
