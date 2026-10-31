# HTTP 输入错误与问答服务失败按来源区分

T08 只将 HTTP 接收边界检测出的请求错误返回为 400 或 413。`DependencyException` 中的 `INVALID_ARGUMENT`、`INPUT_LIMIT_EXCEEDED`、`NOT_FOUND` 或 `BUDGET_EXHAUSTED` 若未被 T07 转换为可解释的任务结果而直接逃逸到接口层，统一返回 500；上游认证失败或限流按依赖异常返回 502。不能仅因底层错误名称相似，就要求调用方修改本来合法的请求、重新登录或承担上游限流。代价是 HTTP 状态不逐字对应内部错误码；T07 已形成的真实任务结果仍按既有契约返回 HTTP 200，由任务状态表达完成情况。
