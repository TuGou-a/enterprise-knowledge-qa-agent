# 真实 HTTP 问答证据

模式：`REAL_HTTP`。2026-11-01 14:30:50 +08:00 在源码基线 `3cddda8` 和最终证据工作树上启动完整
Spring Boot 应用，端口为 `18080`；14:31:02 +08:00 调用 `POST /api/qa`。

- 原始请求：[request.json](request.json)
- 业务响应正文：[response.json](response.json)
- 完整 HTTP 状态行、响应头和正文：[http-transcript.txt](http-transcript.txt)
- 同次服务端启动与请求初始化日志：[server.log](server.log)
- 运行命令与元数据：[run.json](run.json)

实际结果：`HTTP/1.1 200`、`ANSWERED/EVIDENCE_SUFFICIENT`、2 条已读原文引用，运行标识
`f18bc320-5fbd-4080-8cea-75b7e9008d21`，快照
`fd95b17c-eec9-4662-9637-bd3c53b34c6e`。

模型为 DeepSeek `deepseek-chat`，Embedding 为 `BAAI/bge-m3`，Rerank 为
`BAAI/bge-reranker-v2-m3`。当前 HTTP 契约不提供 Trace 查询接口；同题的完整服务 Trace
见 `examples/real/E01/`。
