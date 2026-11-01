# T07：工具结果内部字段放大模型上下文

## 现象

真实资料检索时，模型针对资料外问题连续发起 2 至 3 次检索。单次内部检索结果约 5,500 个 Java 字符，
但真正交给模型判断的候选预览合计只有约 300 个字符。检索结果叠加后，实际模型输入超过默认
24,000 字符预算，运行返回 `INCOMPLETE/INPUT_LIMIT_EXCEEDED`。

一次临时诊断的模型输入长度依次为：

```text
首次调用：3,354
第 1 次检索后：17,575
第 2 次检索后：25,095（超过 24,000）
```

## 环境

- macOS、Java 21.0.12、Maven 3.9.9
- Spring Boot 3.5.10、AgentScope Java 2.0.4
- DeepSeek 官方 API、PostgreSQL 17、pgvector 0.8.6
- 硅基流动 `BAAI/bge-m3` 与 `BAAI/bge-reranker-v2-m3`
- `QA_MODEL_INPUT_CHARACTERS=24000`

## 原因

`KnowledgeTools` 原先把内部 `SearchResult` 和 `ReadResult` 整体序列化后交给模型。检索结果包含
`topicGroups`、候选向量/关键词排名、RRF/重排序分数、`rerankInputKeys`、计量信息、响应 ID 和
快照 UUID；读取结果包含文本哈希、字符偏移等内部字段。

业务代码按预览和原文统计的 `toolTextCharacters` 只计约 900 个字符，但 AgentScope 下一轮请求会把
完整结构一起序列化。模型输入预算检查的是约 15,000 至 23,000 个消息字符，两套计量口径不同，
多次检索叠加后触发上限。

## 解决方案

在工具边界改用只面向模型的精简结构：

- 检索候选保留 `documentId`、`version`、`chunkId`、标题、版本日期、适用范围、主题键和预览。
- 检索结果保留路由状态、排序模式、排序警告、未恢复召回错误、降级和截断标志。
- 读取结果保留文档标识、标题、版本日期、适用范围、替代关系和原文正文。
- 排名、模型计量、内部哈希、偏移和快照 UUID 继续保留在 Trace 和应用内部。

修复后使用默认 24,000 字符预算完成最终 E03、E04 和 E09 真实运行，没有再次因工具结果结构字段
触发 `INPUT_LIMIT_EXCEEDED`。回归测试从实际模型输入中收集全部工具文本，禁止上述内部字段重新进入
模型上下文，并覆盖失败检索的 `unresolvedErrors`。
