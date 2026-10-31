package com.mango.backend.contract.runtime;

import java.time.Duration;

/** T07 实现，使用单调时钟。模型轮次、业务工具次数、总截止时间独立计量。 */
public interface ExecutionBudget {
    Duration remaining();
    void recordModelIteration();
    void markIterationLimitExceeded();
    /** 原子预占；被拒绝的调用不执行、不增加已执行次数，并标记 toolLimitExceeded。 */
    boolean tryAcquireToolCall();
    /** 原子预占本次返回的原文字符与工具文本字符；拒绝时两项均不扣减。 */
    boolean tryAcquireText(int readCharacters, int toolTextCharacters);
    BudgetSnapshot snapshot();
}
