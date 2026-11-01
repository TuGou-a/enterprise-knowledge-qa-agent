package com.mango.backend.qa.application;

import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.runtime.BudgetSnapshot;
import com.mango.backend.contract.runtime.ExecutionBudget;

import java.time.Duration;

/** 一个订阅独占；同步预占兼顾工具取消与阻塞 I/O 完成时的交接。 */
public final class RequestExecutionBudget implements ExecutionBudget {
    private final long started = System.nanoTime();
    private final Duration duration;
    private final AgentProperties.Limits limits;
    private int iterations;
    private int tools;
    private int readCharacters;
    private int toolCharacters;
    private boolean deadlineExceeded;
    private boolean toolExceeded;
    private boolean iterationExceeded;
    private boolean textExceeded;

    public RequestExecutionBudget(AgentProperties properties) {
        duration = Duration.ofMillis(properties.timeouts().requestMs());
        limits = properties.limits();
    }

    @Override
    public synchronized Duration remaining() {
        var remaining = duration.minusNanos(System.nanoTime() - started);
        if (deadlineExceeded || remaining.isNegative() || remaining.isZero()) {
            deadlineExceeded = true;
            return Duration.ZERO;
        }
        return remaining;
    }

    @Override
    public synchronized void recordModelIteration() {
        iterations++;
    }

    @Override
    public synchronized void markIterationLimitExceeded() {
        iterationExceeded = true;
    }

    @Override
    public synchronized boolean tryAcquireToolCall() {
        if (remaining().isZero()) {
            return false;
        }
        if (tools >= limits.toolCalls()) {
            toolExceeded = true;
            return false;
        }
        tools++;
        return true;
    }

    @Override
    public synchronized boolean tryAcquireText(int read, int tool) {
        if (read > limits.readCharacters() - readCharacters
                || tool > limits.toolTextCharacters() - toolCharacters) {
            textExceeded = true;
            return false;
        }
        readCharacters += read;
        toolCharacters += tool;
        return true;
    }

    public synchronized void markInputLimitExceeded() {
        textExceeded = true;
    }

    public synchronized void markDeadlineExceeded() {
        deadlineExceeded = true;
    }

    @Override
    public synchronized BudgetSnapshot snapshot() {
        long remainingMillis = remaining().toMillis();
        return new BudgetSnapshot(iterations, tools, limits.reactIterations(), limits.toolCalls(),
                Duration.ofNanos(System.nanoTime() - started).toMillis(), remainingMillis,
                readCharacters, toolCharacters, deadlineExceeded, toolExceeded,
                iterationExceeded, textExceeded);
    }
}
