package com.codetrove.assay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.codetrove.mergerequest.MergeRequestReviewAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AssayEngineTests {

    private ObjectMapper objectMapper;
    private AssayCaseParser parser;
    private AssayHttpExecutor executor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        ExpressionRenderer renderer = new ExpressionRenderer(objectMapper);
        parser = new AssayCaseParser(objectMapper);
        executor = new AssayHttpExecutor(
            objectMapper,
            renderer,
            "http://127.0.0.1:18080"
        );
    }

    @Test
    void validCaseRunsDataPreExpressionWireMockAndAssertions() {
        List<AssayCaseDefinition> definitions = parser.parseAll(List.of(source("""
            {
              "schema_version":"1.0",
              "case_key":"assay.mock.success",
              "description":"execute mock flow",
              "data_pre":[{
                "key":"prepare",
                "type":"http",
                "request":{"target":"mock","method":"GET","path":"/prepare"},
                "save_as":"prepared",
                "extract":{"value":"$.body.value"}
              }],
              "mocks":[
                {
                  "id":"prepare-mock",
                  "type":"http",
                  "match":{"method":"GET","path":"/prepare"},
                  "respond":{"status":200,"body":{"value":"ready"}},
                  "expect_calls":{"min":1,"max":1}
                },
                {
                  "id":"verify-mock",
                  "type":"http",
                  "match":{"method":"POST","path":"/verify"},
                  "respond":{"status":201,"body":{"success":true}},
                  "expect_calls":{"min":1,"max":1}
                }
              ],
              "request":{
                "target":"mock",
                "method":"POST",
                "path":"/verify",
                "headers":{"X-CodeTrove-Test-Case":"${context.prepared.value}"},
                "body":{"prepared":"${context.prepared.value}","requestId":"${#uuid()}"}
              },
              "assertions":[
                {"type":"response","operator":"equals","path":"$.status","expected":201},
                {"type":"response","operator":"equals","path":"$.body.success","expected":true}
              ]
            }
            """)));

        List<AssayCaseResult> results = executor.execute(definitions);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("PASSED");
            assertThat(result.failureCode()).isNull();
            assertThat(result.assertionDiffs()).isEmpty();
        });
    }

    @Test
    void assertionMismatchContainsPathExpectedAndActual() {
        List<AssayCaseDefinition> definitions = parser.parseAll(List.of(source("""
            {
              "schema_version":"1.0",
              "case_key":"assay.mock.failure",
              "description":"report exact mismatch",
              "mocks":[{
                "id":"failure-mock",
                "type":"http",
                "match":{"method":"GET","path":"/result"},
                "respond":{"status":200,"body":{"status":"FAILED"}},
                "expect_calls":{"min":1,"max":1}
              }],
              "request":{"target":"mock","method":"GET","path":"/result"},
              "assertions":[{
                "type":"response",
                "operator":"equals",
                "path":"$.body.status",
                "expected":"SUCCESS"
              }]
            }
            """)));

        AssayCaseResult result = executor.execute(definitions).get(0);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failureCode()).isEqualTo("ASSERTION_MISMATCH");
        assertThat(result.assertionDiffs()).singleElement().satisfies(diff -> {
            assertThat(diff.path()).isEqualTo("$.body.status");
            assertThat(diff.expected()).isEqualTo("\"SUCCESS\"");
            assertThat(diff.actual()).isEqualTo("\"FAILED\"");
        });
    }

    @Test
    void unknownFieldIsRejectedBySchema() {
        assertThatThrownBy(() -> parser.parseAll(List.of(source("""
            {
              "schema_version":"1.0",
              "case_key":"assay.invalid.unknown",
              "description":"unknown field",
              "request":{"target":"mock","method":"GET","path":"/result"},
              "assertions":[{"type":"response","operator":"exists","path":"$.status"}],
              "unexpected":true
            }
            """))))
            .isInstanceOf(AssayDefinitionException.class)
            .extracting(exception -> ((AssayDefinitionException) exception).failureCode())
            .isEqualTo("SCHEMA_INVALID");
    }

    @Test
    void credentialHeaderAndAbsoluteTargetAreRejectedBeforeExecution() {
        assertThatThrownBy(() -> parser.parseAll(List.of(source("""
            {
              "schema_version":"1.0",
              "case_key":"assay.invalid.header",
              "description":"unsafe header",
              "request":{
                "target":"application",
                "method":"GET",
                "path":"https://example.com/private",
                "headers":{"Authorization":"secret"}
              },
              "assertions":[{"type":"response","operator":"exists","path":"$.status"}]
            }
            """))))
            .isInstanceOf(AssayDefinitionException.class)
            .extracting(exception -> ((AssayDefinitionException) exception).failureCode())
            .isEqualTo("SECURITY_POLICY_VIOLATION");
    }

    @Test
    void duplicateCaseKeyIsRejected() {
        String content = """
            {
              "schema_version":"1.0",
              "case_key":"assay.duplicate.key",
              "description":"duplicate",
              "request":{"target":"mock","method":"GET","path":"/result"},
              "assertions":[{"type":"response","operator":"exists","path":"$.status"}]
            }
            """;
        assertThatThrownBy(() -> parser.parseAll(List.of(
            new MergeRequestReviewAccessService.TestCaseSource("testcases/one.json", content),
            new MergeRequestReviewAccessService.TestCaseSource("testcases/two.json", content)
        )))
            .isInstanceOf(AssayDefinitionException.class)
            .hasMessageContaining("Duplicate case_key");
    }

    private MergeRequestReviewAccessService.TestCaseSource source(String content) {
        return new MergeRequestReviewAccessService.TestCaseSource("testcases/case.json", content);
    }
}
