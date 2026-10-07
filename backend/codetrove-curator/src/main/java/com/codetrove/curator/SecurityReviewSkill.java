package com.codetrove.curator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
class SecurityReviewSkill implements ReviewSkill {

    private static final Pattern SECRET = Pattern.compile(
        "(?i).*(password|passwd|api[_-]?key|secret|access[_-]?token)\\s*=\\s*[\"'][^\"']{8,}[\"'].*"
    );
    private static final Pattern SQL_CONCAT = Pattern.compile(
        ".*(executeQuery|queryForObject|queryForList|update)\\s*\\(.*\\+.*"
    );

    @Override
    public String name() {
        return "SECURITY";
    }

    @Override
    public List<ReviewFindingCandidate> review(ReviewInput input) {
        List<ReviewFindingCandidate> findings = new ArrayList<>();
        for (ReviewInput.AddedLine line : input.addedLines()) {
            if (SECRET.matcher(line.content()).matches()) {
                findings.add(secretFinding(line));
            }
            if (SQL_CONCAT.matcher(line.content()).matches()) {
                findings.add(sqlFinding(line));
            }
        }
        return findings;
    }

    private ReviewFindingCandidate secretFinding(ReviewInput.AddedLine line) {
        return new ReviewFindingCandidate(
            name(),
            "CRITICAL",
            "SEC001_HARDCODED_CREDENTIAL",
            line.filePath(),
            line.lineNumber(),
            "Possible hard-coded credential",
            "A newly added assignment resembles a hard-coded credential.",
            "The value is intentionally redacted from review evidence.",
            "Load the value from a secret store or environment variable and rotate any exposed credential."
        );
    }

    private ReviewFindingCandidate sqlFinding(ReviewInput.AddedLine line) {
        return new ReviewFindingCandidate(
            name(),
            "ERROR",
            "SEC002_SQL_CONCATENATION",
            line.filePath(),
            line.lineNumber(),
            "SQL is built by string concatenation",
            "A newly added database call appears to concatenate values into SQL text.",
            "The review stores no query value or user-controlled fragment.",
            "Use a parameterized query and pass values through placeholders."
        );
    }
}
