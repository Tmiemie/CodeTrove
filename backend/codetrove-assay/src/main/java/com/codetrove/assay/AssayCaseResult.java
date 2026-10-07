package com.codetrove.assay;

import java.util.List;

record AssayCaseResult(
    String caseKey,
    String sourcePath,
    String status,
    String failureCode,
    long durationMs,
    List<AssertionDiff> assertionDiffs
) {
    record AssertionDiff(
        int assertionIndex,
        String path,
        String operator,
        String expected,
        String actual,
        String message
    ) {
    }

    static AssayCaseResult skipped(String caseKey, String sourcePath, String reason) {
        return new AssayCaseResult(caseKey, sourcePath, "SKIPPED", reason, 0L, List.of());
    }
}
