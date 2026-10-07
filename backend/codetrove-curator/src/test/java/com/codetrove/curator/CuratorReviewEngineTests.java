package com.codetrove.curator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.codetrove.eventing.OutboxService;
import com.codetrove.mergerequest.MergeRequestReviewAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class CuratorReviewEngineTests {

    @Test
    void parsesAddedLinesWithRealNewFileLineNumbers() {
        var diff = new MergeRequestReviewAccessService.ReviewDiff(
            1L,
            1,
            "a".repeat(40),
            "b".repeat(40),
            List.of(new MergeRequestReviewAccessService.ReviewDiffFile(
                "MODIFY",
                "src/App.java",
                "src/App.java",
                false,
                """
                diff --git a/src/App.java b/src/App.java
                --- a/src/App.java
                +++ b/src/App.java
                @@ -8,2 +8,4 @@
                 class App {
                +  if (ready);
                +  run();
                 }
                """,
                false
            )),
            false
        );

        ReviewInput input = new UnifiedDiffAddedLineParser().parse(diff);

        assertThat(input.addedLines()).containsExactly(
            new ReviewInput.AddedLine("src/App.java", 9, "  if (ready);"),
            new ReviewInput.AddedLine("src/App.java", 10, "  run();")
        );
    }

    @Test
    void securitySkillRedactsCredentialEvidence() {
        ReviewInput input = new ReviewInput(List.of(
            new ReviewInput.AddedLine("src/Secrets.java", 12, "String apiKey = \"secret-value-123\";")
        ), false);

        List<ReviewFindingCandidate> findings = new SecurityReviewSkill().review(input);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).ruleId()).isEqualTo("SEC001_HARDCODED_CREDENTIAL");
        assertThat(findings.get(0).evidence()).doesNotContain("secret-value-123");
    }

    @Test
    void judgeDeduplicatesAndRejectsInventedLocations() {
        ReviewInput input = new ReviewInput(List.of(
            new ReviewInput.AddedLine("src/App.java", 9, "if (ready);")
        ), false);
        ReviewFindingCandidate candidate = new ReviewFindingCandidate(
            "LOGIC",
            "ERROR",
            "LOGIC001_DANGLING_IF",
            "src/App.java",
            9,
            "Dangling if",
            "Conditional body is empty.",
            "Trailing semicolon.",
            "Use braces."
        );
        ReviewJudge judge = new ReviewJudge();

        assertThat(judge.judge(input, List.of(candidate, candidate))).hasSize(1);
        ReviewFindingCandidate invented = new ReviewFindingCandidate(
            "LOGIC",
            "ERROR",
            "LOGIC001_DANGLING_IF",
            "src/App.java",
            99,
            "Dangling if",
            "Conditional body is empty.",
            "Trailing semicolon.",
            "Use braces."
        );
        assertThatThrownBy(() -> judge.judge(input, List.of(invented)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orchestratorRetriesTransientFailureAndReturnsJudgedFinding() {
        AtomicInteger calls = new AtomicInteger();
        ReviewSkill flaky = new ReviewSkill() {
            @Override
            public String name() {
                return "LOGIC";
            }

            @Override
            public List<ReviewFindingCandidate> review(ReviewInput input) {
                if (calls.incrementAndGet() == 1) {
                    throw new IllegalStateException("temporary");
                }
                return List.of(new ReviewFindingCandidate(
                    "LOGIC",
                    "WARNING",
                    "LOGIC002_EMPTY_CATCH",
                    "src/App.java",
                    4,
                    "Empty catch",
                    "Exception is ignored.",
                    "Empty catch block.",
                    "Handle it."
                ));
            }
        };
        CuratorReviewOrchestrator orchestrator = new CuratorReviewOrchestrator(
            List.of(flaky),
            new ReviewJudge(),
            1,
            Duration.ofSeconds(1),
            2
        );
        try {
            List<ReviewFinding> result = orchestrator.review(new ReviewInput(List.of(
                new ReviewInput.AddedLine("src/App.java", 4, "catch (Exception e) {}")
            ), false));
            assertThat(result).hasSize(1);
            assertThat(calls).hasValue(2);
        } finally {
            orchestrator.close();
        }
    }

    @Test
    void orchestratorStartsSkillsInParallel() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        ReviewSkill logic = barrierSkill("LOGIC", bothStarted);
        ReviewSkill security = barrierSkill("SECURITY", bothStarted);
        CuratorReviewOrchestrator orchestrator = new CuratorReviewOrchestrator(
            List.of(logic, security),
            new ReviewJudge(),
            2,
            Duration.ofSeconds(1),
            1
        );
        try {
            assertThat(orchestrator.review(new ReviewInput(List.of(
                new ReviewInput.AddedLine("src/App.java", 1, "class App {}")
            ), false))).isEmpty();
        } finally {
            orchestrator.close();
        }
    }

    private ReviewSkill barrierSkill(String name, CountDownLatch bothStarted) {
        return new ReviewSkill() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<ReviewFindingCandidate> review(ReviewInput input) {
                bothStarted.countDown();
                try {
                    if (!bothStarted.await(500, TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("Skills did not run in parallel");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Parallel test interrupted", exception);
                }
                return List.of();
            }
        };
    }

    @Test
    void eventProcessorMapsSkillFailureToSkippedResult() {
        CuratorRepository repository = mock(CuratorRepository.class);
        MergeRequestReviewAccessService reviewAccess = mock(MergeRequestReviewAccessService.class);
        CuratorReviewOrchestrator orchestrator = mock(CuratorReviewOrchestrator.class);
        OutboxService outbox = mock(OutboxService.class);
        when(repository.markConsumed(anyString(), anyString())).thenReturn(true);
        when(repository.findTask(anyLong(), anyString())).thenReturn(Optional.empty());
        when(repository.createRunningTask(anyLong(), anyLong(), anyString(), anyLong(), anyInt()))
            .thenReturn(42L);
        when(reviewAccess.requireCurrentDiff(anyLong(), anyLong(), anyString())).thenReturn(
            new MergeRequestReviewAccessService.ReviewDiff(
                2L,
                1,
                "a".repeat(40),
                "b".repeat(40),
                List.of(new MergeRequestReviewAccessService.ReviewDiffFile(
                    "ADD",
                    null,
                    "src/App.java",
                    false,
                    "@@ -0,0 +1 @@\n+class App {}\n",
                    false
                )),
                false
            )
        );
        when(orchestrator.review(any())).thenThrow(
            new CuratorSkillUnavailableException("timeout", new java.util.concurrent.TimeoutException())
        );
        when(outbox.append(
            anyString(), anyString(), anyLong(), anyLong(), anyString(), anyString(), anyString(), anyMap()
        )).thenReturn("event-id");
        CuratorEventProcessor processor = new CuratorEventProcessor(
            new ObjectMapper().findAndRegisterModules(),
            repository,
            reviewAccess,
            new UnifiedDiffAddedLineParser(),
            orchestrator,
            outbox
        );
        String payload = """
            {
              "event_id":"00000000-0000-0000-0000-000000000001",
              "event_type":"curator.review-requested",
              "schema_version":1,
              "occurred_at":"2026-10-05T00:00:00Z",
              "producer":"test",
              "trace_id":"trace",
              "aggregate":{"type":"MERGE_REQUEST","id":"2","version":1},
              "data":{"repository_id":"1","mr_id":"2","mr_iid":1,
                "head_commit":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "check_run_id":"3","attempt":1}
            }
            """;

        processor.process(payload);

        verify(repository).completeTask(42L, "SKIPPED", "SKILL_UNAVAILABLE", 0);
        verify(outbox).append(
            org.mockito.ArgumentMatchers.eq("curator.review-skipped"),
            org.mockito.ArgumentMatchers.eq("REVIEW_TASK"),
            org.mockito.ArgumentMatchers.eq(42L),
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.eq(OutboxService.CURATOR_RESULTS_TOPIC),
            org.mockito.ArgumentMatchers.eq("1:1"),
            org.mockito.ArgumentMatchers.eq("codetrove-curator"),
            anyMap()
        );
    }

    @Test
    void orchestratorTimesOutInsteadOfReportingSuccess() {
        ReviewSkill slow = new ReviewSkill() {
            @Override
            public String name() {
                return "SECURITY";
            }

            @Override
            public List<ReviewFindingCandidate> review(ReviewInput input) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return List.of();
            }
        };
        CuratorReviewOrchestrator orchestrator = new CuratorReviewOrchestrator(
            List.of(slow),
            new ReviewJudge(),
            1,
            Duration.ofMillis(30),
            1
        );
        try {
            assertThatThrownBy(() -> orchestrator.review(new ReviewInput(List.of(
                new ReviewInput.AddedLine("src/App.java", 1, "class App {}")
            ), false))).isInstanceOf(CuratorSkillUnavailableException.class);
        } finally {
            orchestrator.close();
        }
    }
}
