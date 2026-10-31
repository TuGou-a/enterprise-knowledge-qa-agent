package com.mango.backend.contract.runtime;

public record BudgetSnapshot(int reactIterations, int toolCalls, int maxReactIterations,
                             int maxToolCalls, long elapsedMillis, long remainingMillis,
                             int readCharacters, int toolTextCharacters,
                             boolean deadlineExceeded, boolean toolLimitExceeded,
                             boolean iterationLimitExceeded, boolean textLimitExceeded) {}
