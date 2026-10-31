package com.mango.backend.contract.runtime;

import java.util.List;

/** T06 实现，由 T07 为每次运行创建独立记录器；events 返回不可变快照。 */
public interface TraceRecorder {
    void record(TraceEvent.Detail detail);
    List<TraceEvent> events();

    /** T06 提供工厂 Bean；每次 create 返回绑定 runId 的空独立记录器。 */
    interface Factory {
        TraceRecorder create(String runId);
    }
}
