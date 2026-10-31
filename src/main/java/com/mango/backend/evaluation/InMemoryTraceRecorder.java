package com.mango.backend.evaluation;

import com.mango.backend.contract.runtime.TraceEvent;
import com.mango.backend.contract.runtime.TraceRecorder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 每次运行独立；同步追加和快照，保持序号与事件列表顺序一致。 */
public final class InMemoryTraceRecorder implements TraceRecorder {
    private final String runId;
    private final List<TraceEvent> events = new ArrayList<>();

    public InMemoryTraceRecorder(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空白");
        }
        this.runId = runId;
    }

    @Override
    public synchronized void record(TraceEvent.Detail detail) {
        Objects.requireNonNull(detail, "Trace 事件详情不能为空");
        events.add(new TraceEvent(runId, events.size() + 1L, Instant.now(), detail));
    }

    @Override
    public synchronized List<TraceEvent> events() {
        return List.copyOf(events);
    }
}
