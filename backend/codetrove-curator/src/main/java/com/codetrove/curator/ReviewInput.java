package com.codetrove.curator;

import java.util.List;

public record ReviewInput(List<AddedLine> addedLines, boolean truncated) {

    public ReviewInput {
        addedLines = List.copyOf(addedLines);
    }

    public record AddedLine(String filePath, int lineNumber, String content) {
    }
}
