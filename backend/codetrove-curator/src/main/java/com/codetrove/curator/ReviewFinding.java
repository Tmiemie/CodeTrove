package com.codetrove.curator;

public record ReviewFinding(
    String skill,
    String severity,
    String ruleId,
    String filePath,
    int lineNumber,
    String title,
    String message,
    String evidence,
    String suggestion,
    String fingerprint
) {
}
