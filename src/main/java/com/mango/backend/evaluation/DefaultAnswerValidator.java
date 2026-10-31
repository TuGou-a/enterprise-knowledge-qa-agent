package com.mango.backend.evaluation;

import com.mango.backend.contract.knowledge.KnowledgeTypes.ChunkKey;
import com.mango.backend.contract.knowledge.KnowledgeTypes.DocumentKey;
import com.mango.backend.contract.qa.AnswerDraft;
import com.mango.backend.contract.qa.AnswerEnvelope.Citation;
import com.mango.backend.contract.qa.AnswerReason;
import com.mango.backend.contract.qa.AnswerValidator;
import com.mango.backend.contract.qa.TaskStatus;
import com.mango.backend.contract.runtime.RunContext;
import com.mango.backend.contract.search.SearchTypes.RouteStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

/** 只核对结构、已读引用及完成前提，不判断原文是否在语义上支持回答。 */
@Component
public final class DefaultAnswerValidator implements AnswerValidator {
    @Override
    public ValidatedAnswer validate(AnswerDraft draft, RunContext context) {
        var budget = context.budget().snapshot();
        var retrieval = context.retrieval().summary();
        boolean routeFailed = !retrieval.unresolvedErrors().isEmpty();
        boolean readFailed = !retrieval.unresolvedReadErrors().isEmpty();
        boolean topicTruncated = retrieval.truncated();

        // 仅模型草稿是不可信输入；运行上下文与已读证据沿用各自边界的契约。
        boolean structureValid = draft != null && draft.status() != null && draft.reason() != null
                && hasText(draft.answer()) && draft.citations() != null && draft.evidenceGaps() != null
                && draft.evidenceGaps().stream().allMatch(DefaultAnswerValidator::hasText)
                && draft.citations().stream().allMatch(citation -> citation != null
                && citation.documentId() != null && citation.version() != null
                && citation.chunkId() != null && citation.quote() != null);
        if (structureValid) {
            structureValid = switch (draft.status()) {
                case ANSWERED -> draft.reason() == AnswerReason.EVIDENCE_SUFFICIENT;
                case NEEDS_CLARIFICATION -> draft.reason() == AnswerReason.CONTEXT_REQUIRED
                        && !draft.evidenceGaps().isEmpty() && hasText(draft.clarifyingQuestion());
                case CONFLICT -> draft.reason() == AnswerReason.APPLICABILITY_UNKNOWN
                        && !draft.evidenceGaps().isEmpty() && hasText(draft.clarifyingQuestion());
                case NO_EVIDENCE -> draft.reason() == AnswerReason.OUT_OF_CORPUS
                        && !draft.evidenceGaps().isEmpty();
                case INCOMPLETE -> switch (draft.reason()) {
                    case ROUTE_FAILED, READ_FAILED, TOPIC_TRUNCATED, BUDGET_EXHAUSTED,
                         MAX_ITERATIONS, INPUT_LIMIT_EXCEEDED, DEADLINE_EXCEEDED, MODEL_FAILED,
                         OUTPUT_INVALID, CITATION_INVALID -> true;
                    default -> false;
                };
            };
        }

        boolean hasRuntimeFailure = budget.deadlineExceeded() || budget.toolLimitExceeded()
                || budget.iterationLimitExceeded() || budget.textLimitExceeded()
                || routeFailed || readFailed || topicTruncated;
        if (structureValid && draft.status() == TaskStatus.INCOMPLETE && !hasRuntimeFailure) {
            // 模型不能自报运行故障；模型请求失败与调用前输入超限由 T07 处理。
            structureValid = false;
        }
        if (structureValid && draft.status() == TaskStatus.NO_EVIDENCE
                && !routeFailed && !readFailed && !topicTruncated
                && (retrieval.routes().vector() != RouteStatus.OK
                || retrieval.routes().keyword() != RouteStatus.OK)) {
            structureValid = false;
        }

        var citations = new ArrayList<Citation>();
        var citedDocuments = new HashSet<DocumentKey>();
        boolean citationsValid = true;
        if (structureValid) {
            for (var citation : draft.citations()) {
                if (!hasText(citation.documentId()) || !hasText(citation.version())
                        || !hasText(citation.chunkId()) || !hasText(citation.quote())) {
                    citationsValid = false;
                    break;
                }
                var document = new DocumentKey(context.snapshot().snapshotId(),
                        citation.documentId(), citation.version());
                var key = new ChunkKey(document, citation.chunkId());
                var registered = context.evidence().find(key);
                if (registered.isEmpty()) {
                    citationsValid = false;
                    break;
                }
                var chunk = registered.get().chunk();
                int localStart = chunk.content().indexOf(citation.quote());
                if (localStart < 0) {
                    citationsValid = false;
                    break;
                }
                int startOffset = chunk.startOffset() + localStart;
                citations.add(new Citation(document.documentId(), document.version(), key.chunkId(),
                        citation.quote(), startOffset, startOffset + citation.quote().length(),
                        chunk.contentHash()));
                citedDocuments.add(document);
            }
            if ((draft.status() == TaskStatus.ANSWERED && citations.isEmpty())
                    || (draft.status() == TaskStatus.CONFLICT && citedDocuments.size() < 2)) {
                citationsValid = false;
            }
        }

        // 插入顺序就是 T01 的失败优先级，同时保留所有真实限制的公开说明。
        var failures = new LinkedHashSet<AnswerReason>();
        if (budget.deadlineExceeded()) {
            failures.add(AnswerReason.DEADLINE_EXCEEDED);
        }
        if (budget.toolLimitExceeded()) {
            failures.add(AnswerReason.BUDGET_EXHAUSTED);
        }
        if (budget.iterationLimitExceeded()) {
            failures.add(AnswerReason.MAX_ITERATIONS);
        }
        if (budget.textLimitExceeded()) {
            failures.add(AnswerReason.INPUT_LIMIT_EXCEEDED);
        }
        if (!structureValid) {
            failures.add(AnswerReason.OUTPUT_INVALID);
        } else if (!citationsValid) {
            failures.add(AnswerReason.CITATION_INVALID);
        }
        if (routeFailed) {
            failures.add(AnswerReason.ROUTE_FAILED);
        }
        if (readFailed) {
            failures.add(AnswerReason.READ_FAILED);
        }
        if (topicTruncated) {
            failures.add(AnswerReason.TOPIC_TRUNCATED);
        }
        if (failures.isEmpty()) {
            return new ValidatedAnswer(draft.status(), draft.reason(), draft.answer(), citations,
                    draft.evidenceGaps(), draft.clarifyingQuestion());
        }

        boolean draftValid = structureValid && citationsValid;
        var gaps = new LinkedHashSet<String>();
        if (draftValid) {
            gaps.addAll(draft.evidenceGaps());
        } else {
            citations.clear();
        }
        for (var failure : failures) {
            gaps.add(failureMessage(failure));
        }
        for (var problem : retrieval.unresolvedErrors()) {
            gaps.add("必要召回路由 " + problem.route() + " 尚未恢复（"
                    + problem.failure().code() + "）。");
        }
        for (var problem : retrieval.unresolvedReadErrors()) {
            var key = problem.key();
            gaps.add("原文读取尚未完成：" + key.document().documentId() + "/"
                    + key.document().version() + "/" + key.chunkId()
                    + "（" + problem.failure().code() + "）。");
        }

        var reason = failures.iterator().next();
        if (draftValid && draft.status() == TaskStatus.CONFLICT
                && (reason == AnswerReason.ROUTE_FAILED || reason == AnswerReason.READ_FAILED
                || reason == AnswerReason.TOPIC_TRUNCATED)) {
            return new ValidatedAnswer(draft.status(), draft.reason(), draft.answer(), citations,
                    List.copyOf(gaps), draft.clarifyingQuestion());
        }
        return new ValidatedAnswer(TaskStatus.INCOMPLETE, reason, failureMessage(reason), citations,
                List.copyOf(gaps), null);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String failureMessage(AnswerReason reason) {
        return switch (reason) {
            case DEADLINE_EXCEEDED -> "本次问答已达到总时限，尚未完成查证。";
            case BUDGET_EXHAUSTED -> "业务工具调用预算已耗尽，尚未完成查证。";
            case MAX_ITERATIONS -> "已达到模型推理轮次上限，尚未完成查证。";
            case INPUT_LIMIT_EXCEEDED -> "查证所需文本超过执行预算，尚未完成查证。";
            case OUTPUT_INVALID -> "模型输出不符合回答结构或状态要求，无法提供可靠回答。";
            case CITATION_INVALID -> "模型引用未通过本次已读原文核验，无法提供可靠回答。";
            case ROUTE_FAILED -> "必要检索尚未恢复，尚未完成查证。";
            case READ_FAILED -> "必要原文尚未读取成功，尚未完成查证。";
            case TOPIC_TRUNCATED -> "同主题资料尚未完整查证，无法确认最终结论。";
            default -> throw new IllegalArgumentException("不属于输出校验器处理的失败原因：" + reason);
        };
    }
}
