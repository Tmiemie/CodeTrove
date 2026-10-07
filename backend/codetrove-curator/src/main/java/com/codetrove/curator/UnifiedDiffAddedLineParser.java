package com.codetrove.curator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.codetrove.mergerequest.MergeRequestReviewAccessService;

import org.springframework.stereotype.Component;

@Component
class UnifiedDiffAddedLineParser {

    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$");

    ReviewInput parse(MergeRequestReviewAccessService.ReviewDiff diff) {
        List<ReviewInput.AddedLine> addedLines = new ArrayList<>();
        boolean truncated = diff.truncated();
        for (MergeRequestReviewAccessService.ReviewDiffFile file : diff.files()) {
            truncated = truncated || file.truncated();
            if (file.binary() || file.patch() == null || file.newPath() == null) {
                continue;
            }
            parseFile(file.newPath(), file.patch(), addedLines);
        }
        return new ReviewInput(addedLines, truncated);
    }

    private void parseFile(String path, String patch, List<ReviewInput.AddedLine> addedLines) {
        int newLine = -1;
        for (String line : patch.split("\\R", -1)) {
            Matcher matcher = HUNK.matcher(line);
            if (matcher.matches()) {
                newLine = Integer.parseInt(matcher.group(1));
                continue;
            }
            if (newLine < 0 || line.startsWith("+++")) {
                continue;
            }
            if (line.startsWith("+")) {
                addedLines.add(new ReviewInput.AddedLine(path, newLine, line.substring(1)));
                newLine++;
            } else if (!line.startsWith("-")) {
                newLine++;
            }
        }
    }
}
