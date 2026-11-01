# 检索评估运行

实际 commit：`3cddda899ab6f7a11ae9409374c1a83317de28d3`。输入、快照、原始召回、排名、计量、失败和汇总见 [results.json](results.json)。

入口：`mvn -Preal-evaluation -Dtest=RealRetrievalEvaluationTest -Devaluation.snapshots='normal=fee7a379-f551-4232-bcea-d50be84de4f1,conflict=cc9a31be-a954-48b7-b0a6-61bd2acc44c8,mutation=3b93e9a0-e1f3-45d8-ac2d-429e0342d52f' test`；其他自定义 JVM／Maven 参数如有需另记。

本次实际命令、起止时间和退出码见 [外层运行记录](../../../tests/retrieval/20261101T062006Z-817e59b1fb974b18b704dd4abe3d9469/run.json)。

标注状态：`REFERENCE_REVIEW_PENDING`。本次仅比较检索，不运行 DeepSeek 或问答 Agent；不证明动态动作调整或答案支持性。16 个问题来自 7／8 条款的小型合成资料，不能据此推断普遍收益。
