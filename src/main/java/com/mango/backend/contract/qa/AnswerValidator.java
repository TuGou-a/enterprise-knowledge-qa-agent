package com.mango.backend.contract.qa;

import com.mango.backend.contract.runtime.RunContext;

import java.util.List;

/** T06 实现；校验结构、已读引用与状态最低前提，不能证明语义支持。 */
public interface AnswerValidator {
    ValidatedAnswer validate(AnswerDraft draft, RunContext context);

    record ValidatedAnswer(TaskStatus status, AnswerReason reason, String answer,
                           List<AnswerEnvelope.Citation> citations, List<String> evidenceGaps,
                           String clarifyingQuestion) {
        public ValidatedAnswer {
            citations = List.copyOf(citations);
            evidenceGaps = List.copyOf(evidenceGaps);
        }
    }
}
