package com.codetrove.curator;

public record ReviewFindingCandidate(
    String skill,
    String severity,
    String ruleId,
    String filePath,
    int lineNumber,
    String title,
    String message,
    String evidence,
    String suggestion
) {
}
