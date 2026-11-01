# 检索评估运行

实际 commit：`9aaeaea71615664e512fecfbf1f4e169e8264ae0`。输入、快照、原始召回、排名、计量、失败和汇总见 [results.json](results.json)。

入口：`mvn -Preal-evaluation -Dtest=RealRetrievalEvaluationTest -Devaluation.snapshots='normal=3ddd118c-8c15-4b70-a2d2-ae500900f962,conflict=dd95ed52-0804-4027-b83a-1d212aa6b597,mutation=6660ee85-5943-481f-b84e-1631e2a7039f' test`；其他自定义 JVM／Maven 参数如有需另记。

本次实际命令、起止时间和退出码见 [外层运行记录](../../../tests/retrieval/20261101T065425Z-5382e95077284ac895cd11d7459df44c/run.json)。

标注状态：`REFERENCE_REVIEW_PENDING`。本次仅比较检索，不运行 DeepSeek 或问答 Agent；不证明动态动作调整或答案支持性。16 个问题来自 7／8 条款的小型合成资料，不能据此推断普遍收益。
