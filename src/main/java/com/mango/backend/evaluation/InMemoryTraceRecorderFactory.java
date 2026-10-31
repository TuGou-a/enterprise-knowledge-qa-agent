package com.mango.backend.evaluation;

import com.mango.backend.contract.runtime.TraceRecorder;
import org.springframework.stereotype.Component;

@Component
public final class InMemoryTraceRecorderFactory implements TraceRecorder.Factory {
    @Override
    public TraceRecorder create(String runId) {
        return new InMemoryTraceRecorder(runId);
    }
}
