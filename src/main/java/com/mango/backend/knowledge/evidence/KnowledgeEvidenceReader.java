package com.mango.backend.knowledge.evidence;

import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.knowledge.KnowledgeStore;
import com.mango.backend.contract.runtime.RunContext;
import com.mango.backend.contract.search.KnowledgeReader;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;
import static com.mango.backend.contract.search.SearchTypes.*;

/** 只有完整读取且可向模型交付的原文才能进入本次证据集合。 */
@Component
public final class KnowledgeEvidenceReader implements KnowledgeReader {
    private final KnowledgeStore store;
    private final AgentProperties properties;

    public KnowledgeEvidenceReader(KnowledgeStore store, AgentProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public ReadResult read(ReadRequest request, RunContext context) {
        var key = new ChunkKey(new DocumentKey(context.snapshot().snapshotId(),
                request.documentId(), request.version()), request.chunkId());
        long started = System.nanoTime();
        var toolTimeout = Duration.ofMillis(properties.timeouts().toolMs());
        try {
            var timeout = Duration.ofMillis(Math.min(properties.timeouts().databaseMs(),
                    properties.timeouts().toolMs()));
            var remaining = context.budget().remaining();
            if (remaining.compareTo(timeout) < 0) {
                timeout = remaining;
            }
            if (timeout.isZero() || timeout.isNegative()) {
                throw new DependencyException(new Failure(Failure.Code.BUDGET_EXHAUSTED,
                        "原文读取剩余时间不足", false));
            }
            var evidence = store.read(key, timeout).orElseThrow(() -> new DependencyException(
                    new Failure(Failure.Code.NOT_FOUND, "指定文档版本或原文片段不存在", false)));
            if (context.budget().remaining().isZero()
                    || Duration.ofNanos(System.nanoTime() - started).compareTo(toolTimeout) >= 0) {
                throw new DependencyException(new Failure(Failure.Code.TIMEOUT,
                        "原文未在剩余时限内读取完成", true));
            }
            int characters = evidence.chunk().content().length();
            if (!context.budget().tryAcquireText(characters, characters)) {
                throw new DependencyException(new Failure(Failure.Code.BUDGET_EXHAUSTED,
                        "完整原文超过本次原文或工具文本预算", false));
            }
            context.evidence().register(evidence);
            context.retrieval().resolveRead(key);
            return new ReadResult(ReadStatus.OK, evidence, null);
        } catch (DependencyException exception) {
            context.retrieval().recordRead(new ReadProblem(key, exception.failure()));
            return new ReadResult(ReadStatus.ERROR, null, exception.failure());
        }
    }
}
