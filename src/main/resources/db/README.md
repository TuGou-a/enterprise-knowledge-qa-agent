# 知识库数据库初始化

## 前置条件

准备 PostgreSQL 数据库，使用 UTF-8 编码，并在数据库服务器安装 pgvector 扩展文件。数据库管理员负责创建数据库及应用角色；初始化角色须有目标 schema 的建表权限和创建 `vector` 扩展的权限。应用用户名、密码及 JDBC 地址继续通过 T01 定义的环境变量提供。

本目录不创建角色或保存密码。脚本为一次性初始版本，尚未在真实 PostgreSQL 执行；不要将文件存在视为初始化已经成功。

## 执行

1. 在项目根目录为 `psql` 设置 `PGHOST`、`PGPORT`、`PGDATABASE`、`PGUSER`。使用本机凭据文件或交互式提示提供密码。
2. 运行以下命令。当前开发阶段不执行，等待用户后续验证指令。

```sh
psql -X --set ON_ERROR_STOP=on --single-transaction \
  --file src/main/resources/db/V001__knowledge.sql
```

脚本预期创建 `vector` 扩展、三张 `kb_*` 表及其约束和索引。`ON_ERROR_STOP` 与单事务保证脚本失败时停止并回滚；已存在表时明确报错，不使用 `IF NOT EXISTS` 掩盖结构差异。若扩展创建权限不足，先由数据库管理员创建扩展，再以表所有者执行同一脚本。

应用保留 `spring.sql.init.mode=never`，启动时不自动执行本目录文件。资料导入由 T04 实现；本脚本不写入合成资料。

## 数据约定

- `kb_corpus` 保存完整 `IndexProfile`、快照状态、集合哈希及创建和发布时间。
- `kb_document` 保存版本原文，`supersedes` 使用 JSON 数组，只表达原文明确的替代关系。
- `kb_chunk` 保存原文子串、UTF-16 偏移、片段哈希、`tsvector` 和 `vector(1024)`。
- 初始版本采用向量精确扫描、关键词 GIN 索引和主题索引；没有 HNSW 索引。
- SQL 约束不使用 PostgreSQL 字符数复验 Java UTF-16 偏移；偏移、原文与哈希的一致性由 T04 导入边界一次性校验。
- 已发布快照不通过持久化接口写入、覆盖或删除。批次和发布操作先锁定所属快照，再检查 `BUILDING`，防止同一快照的写入与发布交错。

关键词先使用相同分词配置产生词元，入库时以空格连接词元并调用 `to_tsvector('simple', ...)`；查询对每个绑定词元调用 `plainto_tsquery('simple', ...)` 后用 `||` 合成 OR 条件。词元作为数据传递，不作为 `tsquery` 语法拼接。原文不做上述归一化。

依据：[PostgreSQL 全文检索](https://www.postgresql.org/docs/17/textsearch-controls.html)、[查询组合运算](https://www.postgresql.org/docs/17/textsearch-features.html)、[pgvector](https://github.com/pgvector/pgvector)。这些来源只用于核对设计，不构成本项目运行验证。
