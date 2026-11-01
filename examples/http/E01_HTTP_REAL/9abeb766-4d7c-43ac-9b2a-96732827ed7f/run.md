# 真实 HTTP 问答证据

模式：`REAL_HTTP`。2026-11-01 14:55:42 +08:00 在源码基线
`9aaeaea71615664e512fecfbf1f4e169e8264ae0` 和证据工作树上启动完整 Spring Boot 应用，端口为
`18080`；14:55:56 +08:00 调用 `POST /api/qa`。

- 原始请求：[request.json](request.json)
- 业务响应正文：[response.json](response.json)
- 完整 HTTP 状态行、响应头和正文：[http-transcript.txt](http-transcript.txt)
- 同次服务端启动与请求日志：[server.log](server.log)
- 运行命令与元数据：[run.json](run.json)

实际结果：`HTTP/1.1 200`、`ANSWERED/EVIDENCE_SUFFICIENT`、1 条已读原文引用，运行标识
`9abeb766-4d7c-43ac-9b2a-96732827ed7f`，快照
`3e4a6305-22c4-4dee-b8b2-d78e40e00125`。

模型为 DeepSeek `deepseek-chat`，Embedding 为 `BAAI/bge-m3`，Rerank 为
`BAAI/bge-reranker-v2-m3`。回答给出 30 个自然日的明确依据，同时披露费用制度未标注
`applicableScope`、差旅制度已读条款未另列期限这两项适用边界。当前 HTTP 契约不提供 Trace
查询接口。
