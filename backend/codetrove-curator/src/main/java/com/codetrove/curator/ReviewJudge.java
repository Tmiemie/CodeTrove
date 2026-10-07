package com.codetrove.curator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

@Component
class ReviewJudge {

    List<ReviewFinding> judge(ReviewInput input, List<ReviewFindingCandidate> candidates) {
        Set<String> validPositions = input.addedLines().stream()
            .map(line -> line.filePath() + "\n" + line.lineNumber())
            .collect(Collectors.toSet());
        Map<String, ReviewFinding> findings = new LinkedHashMap<>();
        for (ReviewFindingCandidate candidate : candidates) {
            validate(candidate, validPositions);
            String fingerprint = fingerprint(candidate);
            findings.putIfAbsent(fingerprint, new ReviewFinding(
                candidate.skill(),
                candidate.severity(),
                candidate.ruleId(),
                candidate.filePath(),
                candidate.lineNumber(),
                candidate.title(),
                candidate.message(),
                candidate.evidence(),
                candidate.suggestion(),
                fingerprint
            ));
        }
        return findings.values().stream()
            .sorted(Comparator.comparing(ReviewFinding::filePath)
                .thenComparingInt(ReviewFinding::lineNumber)
                .thenComparing(ReviewFinding::ruleId))
            .toList();
    }

    private void validate(ReviewFindingCandidate candidate, Set<String> validPositions) {
        if (!Set.of("LOGIC", "SECURITY").contains(candidate.skill())
            || !Set.of("INFO", "WARNING", "ERROR", "CRITICAL").contains(candidate.severity())
            || candidate.ruleId() == null
            || candidate.ruleId().isBlank()
            || !validPositions.contains(candidate.filePath() + "\n" + candidate.lineNumber())) {
            throw new IllegalArgumentException("Review finding failed Judge validation");
        }
    }

    private String fingerprint(ReviewFindingCandidate candidate) {
        String normalized = String.join(
            "\n",
            candidate.ruleId(),
            candidate.filePath(),
            Integer.toString(candidate.lineNumber()),
            candidate.message().trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT)
        );
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
