package com.codetrove.assay;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.codetrove.mergerequest.MergeRequestReviewAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import org.springframework.stereotype.Component;

@Component
class AssayCaseParser {

    private static final Set<String> ALLOWED_HEADERS = Set.of(
        "accept",
        "content-type",
        "x-codetrove-test-case"
    );
    private final ObjectMapper objectMapper;
    private final JsonSchema schema;

    AssayCaseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        try (InputStream input = AssayCaseParser.class.getResourceAsStream("/assay-case-v1.schema.json")) {
            if (input == null) {
                throw new IllegalStateException("Assay schema resource is missing");
            }
            this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Assay schema cannot be loaded", exception);
        }
    }

    List<AssayCaseDefinition> parseAll(
        List<MergeRequestReviewAccessService.TestCaseSource> sources
    ) {
        List<ParsedSource> parsed = new ArrayList<>();
        Set<String> caseKeys = new HashSet<>();
        for (MergeRequestReviewAccessService.TestCaseSource source : sources) {
            JsonNode root = parseJson(source);
            Set<ValidationMessage> errors = schema.validate(root);
            if (!errors.isEmpty()) {
                String message = errors.stream().map(ValidationMessage::getMessage).sorted().findFirst()
                    .orElse("Schema validation failed");
                throw new AssayDefinitionException("SCHEMA_INVALID", source.path() + ": " + message);
            }
            String caseKey = root.path("case_key").asText();
            if (!caseKeys.add(caseKey)) {
                throw new AssayDefinitionException(
                    "SCHEMA_INVALID",
                    "Duplicate case_key: " + caseKey
                );
            }
            validateSecurity(root, source.path());
            parsed.add(new ParsedSource(source.path(), root));
        }
        return parsed.stream().map(this::map).toList();
    }

    private JsonNode parseJson(MergeRequestReviewAccessService.TestCaseSource source) {
        try {
            return objectMapper.readTree(source.content());
        } catch (JsonProcessingException exception) {
            throw new AssayDefinitionException(
                "SCHEMA_INVALID",
                source.path() + ": invalid JSON"
            );
        }
    }

    private void validateSecurity(JsonNode root, String sourcePath) {
        validateRequest(root.path("request"), sourcePath);
        root.path("data_pre").forEach(step -> validateRequest(step.path("request"), sourcePath));
        root.path("mocks").forEach(mock -> {
            validatePath(mock.path("match").path("path").asText(), sourcePath);
            int min = mock.path("expect_calls").path("min").asInt();
            int max = mock.path("expect_calls").path("max").asInt();
            if (min > max) {
                throw new AssayDefinitionException(
                    "SECURITY_POLICY_VIOLATION",
                    sourcePath + ": expect_calls.min must not exceed max"
                );
            }
        });
        validateExpressionNodes(root, sourcePath);
    }

    private void validateRequest(JsonNode request, String sourcePath) {
        validatePath(request.path("path").asText(), sourcePath);
        Iterator<String> names = request.path("headers").fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!ALLOWED_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                throw new AssayDefinitionException(
                    "SECURITY_POLICY_VIOLATION",
                    sourcePath + ": header is not allowed: " + name
                );
            }
        }
    }

    private void validatePath(String path, String sourcePath) {
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("://")
            || path.contains("\\") || path.indexOf('\0') >= 0) {
            throw new AssayDefinitionException(
                "SECURITY_POLICY_VIOLATION",
                sourcePath + ": request path must be relative"
            );
        }
        for (String segment : path.split("/", -1)) {
            if ("..".equals(segment) || ".".equals(segment)) {
                throw new AssayDefinitionException(
                    "SECURITY_POLICY_VIOLATION",
                    sourcePath + ": request path contains traversal"
                );
            }
        }
    }

    private void validateExpressionNodes(JsonNode node, String sourcePath) {
        if (node.isTextual()) {
            String value = node.asText();
            if (value.length() > 10_000 || value.contains("${") && !ExpressionRenderer.isSupported(value)) {
                throw new AssayDefinitionException(
                    "EXPRESSION_ERROR",
                    sourcePath + ": unsupported expression"
                );
            }
        } else if (node.isContainerNode()) {
            node.forEach(child -> validateExpressionNodes(child, sourcePath));
        }
    }

    private AssayCaseDefinition map(ParsedSource source) {
        JsonNode root = source.root();
        List<AssayCaseDefinition.PreStep> preSteps = new ArrayList<>();
        root.path("data_pre").forEach(step -> preSteps.add(new AssayCaseDefinition.PreStep(
            step.path("key").asText(),
            request(step.path("request")),
            step.path("save_as").asText(),
            objectMapper.convertValue(step.path("extract"), objectMapper.getTypeFactory()
                .constructMapType(Map.class, String.class, String.class))
        )));
        List<AssayCaseDefinition.HttpMockDefinition> mocks = new ArrayList<>();
        root.path("mocks").forEach(mock -> mocks.add(new AssayCaseDefinition.HttpMockDefinition(
            mock.path("id").asText(),
            mock.path("match").path("method").asText(),
            mock.path("match").path("path").asText(),
            mock.path("respond").path("status").asInt(),
            stringMap(mock.path("respond").path("headers")),
            nullable(mock.path("respond").get("body")),
            mock.path("expect_calls").path("min").asInt(),
            mock.path("expect_calls").path("max").asInt()
        )));
        List<AssayCaseDefinition.ResponseAssertion> assertions = new ArrayList<>();
        root.path("assertions").forEach(assertion -> assertions.add(
            new AssayCaseDefinition.ResponseAssertion(
                assertion.path("operator").asText(),
                assertion.path("path").asText(),
                nullable(assertion.get("expected"))
            )
        ));
        return new AssayCaseDefinition(
            root.path("schema_version").asText(),
            root.path("case_key").asText(),
            root.path("description").asText(),
            !root.has("enabled") || root.path("enabled").asBoolean(),
            root.has("timeout_ms") ? root.path("timeout_ms").asInt() : 5_000,
            List.copyOf(preSteps),
            request(root.path("request")),
            List.copyOf(mocks),
            List.copyOf(assertions),
            source.path()
        );
    }

    private AssayCaseDefinition.HttpRequestDefinition request(JsonNode request) {
        return new AssayCaseDefinition.HttpRequestDefinition(
            request.path("target").asText(),
            request.path("method").asText(),
            request.path("path").asText(),
            stringMap(request.path("headers")),
            nullable(request.get("body"))
        );
    }

    private Map<String, String> stringMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        return objectMapper.convertValue(
            node,
            objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class)
        );
    }

    private JsonNode nullable(JsonNode node) {
        return node == null || node.isNull() ? null : node.deepCopy();
    }

    private record ParsedSource(String path, JsonNode root) {
    }
}
