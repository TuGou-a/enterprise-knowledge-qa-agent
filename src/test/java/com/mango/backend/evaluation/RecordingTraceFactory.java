package com.mango.backend.evaluation;

import com.mango.backend.contract.runtime.TraceEvent;
import com.mango.backend.contract.runtime.TraceRecorder;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** 仅用于测试和评估，捕获实际问答服务创建的记录器，不预写任何事件。 */
public final class RecordingTraceFactory implements TraceRecorder.Factory {
    private final TraceRecorder.Factory delegate = new InMemoryTraceRecorderFactory();
    private final ConcurrentHashMap<String, TraceRecorder> recordings = new ConcurrentHashMap<>();

    @Override
    public TraceRecorder create(String runId) {
        var recorder = delegate.create(runId);
        recordings.put(runId, recorder);
        return recorder;
    }

    public List<TraceEvent> events(String runId) {
        return recordings.get(runId).events();
    }
}
