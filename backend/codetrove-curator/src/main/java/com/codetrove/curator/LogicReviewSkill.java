package com.codetrove.curator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
class LogicReviewSkill implements ReviewSkill {

    private static final Pattern DANGLING_IF = Pattern.compile("^\\s*if\\s*\\(.+\\)\\s*;\\s*$");
    private static final Pattern EMPTY_CATCH = Pattern.compile(".*catch\\s*\\([^)]*\\)\\s*\\{\\s*}.*");

    @Override
    public String name() {
        return "LOGIC";
    }

    @Override
    public List<ReviewFindingCandidate> review(ReviewInput input) {
        List<ReviewFindingCandidate> findings = new ArrayList<>();
        for (ReviewInput.AddedLine line : input.addedLines()) {
            if (DANGLING_IF.matcher(line.content()).matches()) {
                findings.add(new ReviewFindingCandidate(
                    name(),
                    "ERROR",
                    "LOGIC001_DANGLING_IF",
                    line.filePath(),
                    line.lineNumber(),
                    "If statement ends with a semicolon",
                    "The conditional body is empty, so the following statement is always executed.",
                    "A newly added if statement has a trailing semicolon.",
                    "Remove the semicolon and use an explicit braced body."
                ));
            }
            if (EMPTY_CATCH.matcher(line.content()).matches()) {
                findings.add(new ReviewFindingCandidate(
                    name(),
                    "WARNING",
                    "LOGIC002_EMPTY_CATCH",
                    line.filePath(),
                    line.lineNumber(),
                    "Exception is silently ignored",
                    "An empty catch block can hide a failed operation and leave state inconsistent.",
                    "A newly added catch block has no handling body.",
                    "Handle the exception, return an explicit result, or document and log a sanitized reason."
                ));
            }
        }
        return findings;
    }
}
