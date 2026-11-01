package com.mango.backend.qa.agent;

import com.mango.backend.contract.qa.AnswerReason;

/** 应用已掌握的终止原因；跨框架传播时只携带固定公开信息。 */
public final class AgentExecutionStopped extends RuntimeException {
    private final AnswerReason reason;

    public AgentExecutionStopped(AnswerReason reason) {
        super("问答执行已停止：" + reason);
        this.reason = reason;
    }

    public AnswerReason reason() { return reason; }
}
