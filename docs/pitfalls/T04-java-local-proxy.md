# T04 Java 测试进程无法连接本机 PostgreSQL

## 现象

本机 `psql` 可以连接 `127.0.0.1:55432`，但同一终端中的 Java 导入进程在建链时抛出 `java.net.UnknownHostException: 127.0.0.1`。Spring 将数据库错误映射为 `DATABASE_UNAVAILABLE`，PostgreSQL 日志没有收到应用连接。

## 环境

- macOS
- Java 21.0.12
- Maven 3.9.9
- PostgreSQL 17.11 与 pgvector 0.8.6

## 原因

问题 JVM 的 `java -XshowSettings:properties -version` 实际显示：

```text
http.proxyHost = 127.0.0.1
http.proxyPort = 7897
socksProxyHost = 127.0.0.1
socksProxyPort = 7897
```

PostgreSQL 驱动最终在 `SocksSocketImpl.connect` 中把 `127.0.0.1` 报为未知主机。关闭 `socksProxyHost` 后，相同 JDBC 地址可以连接临时数据库，因此可以确认本机 JVM 的代理属性参与了失败过程。

JDK 21 的 `java.net.useSystemProxies` 默认值不是 `true`，当前也没有找到把上述属性注入该 JVM 的全局参数。因此“代理属性由 macOS 系统设置自动注入”只能列为待验证原因，不能仅凭现象断言。即使尚未找到注入来源，测试进程仍应显式消除已观测到的代理属性，才能稳定连接本机服务。

## 解决方案

以下命令均需在项目根目录执行，成功后会创建并发布 `normal.json` 的新快照。`JAVA_TOOL_OPTIONS` 前缀会同时影响 Maven JVM 和分叉出的应用 JVM：

```sh
JAVA_TOOL_OPTIONS='-Djava.net.useSystemProxies=false -Dhttp.proxyHost= -Dhttps.proxyHost= -DsocksProxyHost=' \
  mvn -DskipTests \
  -Dspring-boot.run.main-class=com.mango.backend.knowledge.ingest.IngestionApplication \
  -Dspring-boot.run.arguments='--corpus-file=data/corpus/normal.json --import-timeout-ms=600000' \
  spring-boot:run
```

数据库和模型环境变量继续按 [资料导入说明](../../data/README.md)提供。如果依赖已缓存且只想调整应用 JVM，可通过 Spring Boot 插件传参：

```sh
mvn -DskipTests \
  "-Dspring-boot.run.jvmArguments=-Djava.net.useSystemProxies=false -Dhttp.proxyHost= -Dhttps.proxyHost= -DsocksProxyHost=" \
  -Dspring-boot.run.main-class=com.mango.backend.knowledge.ingest.IngestionApplication \
  -Dspring-boot.run.arguments='--corpus-file=data/corpus/normal.json --import-timeout-ms=600000' \
  spring-boot:run
```

推荐使用第二条命令，它只把属性传给分叉出的应用 JVM。第一条会同时关闭 Maven JVM 的代理，可能影响尚未缓存的依赖下载。也可以核查并修正向 JVM 注入代理属性的启动器、IDE 或 shell 配置。不要把关闭代理写进应用默认配置，因为正式环境的外部服务仍可能需要遵循代理设置。

验证时先用 `psql` 连接同一地址；再在相同启动环境下执行 `java -XshowSettings:properties -version` 查看该 JVM 的代理属性。它不能直接显示 Maven 分叉应用 JVM 的属性，后者应通过实际启动参数或临时诊断输出核对。若 `psql` 成功而 Java 报 `UnknownHostException`，应检查 JVM 代理参数，而不是重复修改 JDBC URL。原因的注入来源仍为待验证项。
