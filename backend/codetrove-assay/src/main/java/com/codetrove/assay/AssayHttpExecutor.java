package com.codetrove.assay;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.request;
import static com.github.tomakehurst.wiremock.client.WireMock.requestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.github.tomakehurst.wiremock.WireMockServer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class AssayHttpExecutor {

    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private final ObjectMapper objectMapper;
    private final ExpressionRenderer renderer;
    private final HttpClient httpClient;
    private final URI applicationBaseUri;

    AssayHttpExecutor(
        ObjectMapper objectMapper,
        ExpressionRenderer renderer,
        @Value("${codetrove.assay.target-base-url:http://127.0.0.1:18080}") String targetBaseUrl
    ) {
        this.objectMapper = objectMapper;
        this.renderer = renderer;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        this.applicationBaseUri = validateBaseUri(targetBaseUrl);
    }

    List<AssayCaseResult> execute(List<AssayCaseDefinition> definitions) {
        return definitions.stream().map(this::executeOne).toList();
    }

    private AssayCaseResult executeOne(AssayCaseDefinition definition) {
        if (!definition.enabled()) {
            return AssayCaseResult.skipped(definition.caseKey(), definition.sourcePath(), "CASE_DISABLED");
        }
        long started = System.nanoTime();
        WireMockServer mockServer = new WireMockServer(
            options().bindAddress("127.0.0.1").dynamicPort()
        );
        try {
            mockServer.start();
            Map<String, JsonNode> context = new LinkedHashMap<>();
            registerMocks(mockServer, definition.mocks(), context);
            for (AssayCaseDefinition.PreStep step : definition.dataPre()) {
                HttpResult response = send(step.request(), definition.timeoutMs(), context, mockServer);
                context.put(step.saveAs(), extract(response, step.extract()));
            }
            HttpResult response = send(definition.request(), definition.timeoutMs(), context, mockServer);
            List<AssayCaseResult.AssertionDiff> diffs = assertResponse(
                response,
                definition.assertions()
            );
            diffs.addAll(assertMockCalls(mockServer, definition.mocks()));
            long duration = elapsedMillis(started);
            if (diffs.isEmpty()) {
                return new AssayCaseResult(
                    definition.caseKey(),
                    definition.sourcePath(),
                    "PASSED",
                    null,
                    duration,
                    List.of()
                );
            }
            return new AssayCaseResult(
                definition.caseKey(),
                definition.sourcePath(),
                "FAILED",
                "ASSERTION_MISMATCH",
                duration,
                List.copyOf(diffs)
            );
        } catch (AssayDefinitionException exception) {
            return error(definition, started, exception.failureCode(), exception.getMessage());
        } catch (IOException exception) {
            return error(definition, started, "REQUEST_FAILED", "HTTP request failed");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(definition, started, "REQUEST_FAILED", "HTTP request interrupted");
        } catch (RuntimeException exception) {
            return error(definition, started, "INFRASTRUCTURE_ERROR", "Assay infrastructure error");
        } finally {
            if (mockServer.isRunning()) {
                mockServer.stop();
            }
        }
    }

    private void registerMocks(
        WireMockServer server,
        List<AssayCaseDefinition.HttpMockDefinition> mocks,
        Map<String, JsonNode> context
    ) {
        for (AssayCaseDefinition.HttpMockDefinition mock : mocks) {
            var response = aResponse().withStatus(mock.status());
            for (Map.Entry<String, String> header : mock.headers().entrySet()) {
                response.withHeader(header.getKey(), renderer.renderString(header.getValue(), context));
            }
            if (mock.body() != null) {
                try {
                    response.withBody(objectMapper.writeValueAsString(renderer.render(mock.body(), context)));
                } catch (JsonProcessingException exception) {
                    throw new AssayDefinitionException("MOCK_SETUP_FAILED", "Mock body cannot be serialized");
                }
            }
            server.stubFor(request(mock.method(), urlEqualTo(mock.path())).willReturn(response));
        }
    }

    private HttpResult send(
        AssayCaseDefinition.HttpRequestDefinition definition,
        int timeoutMs,
        Map<String, JsonNode> context,
        WireMockServer mockServer
    ) throws IOException, InterruptedException {
        URI baseUri = "mock".equals(definition.target())
            ? URI.create(mockServer.baseUrl())
            : applicationBaseUri;
        URI uri = baseUri.resolve(definition.path());
        if (!sameOrigin(baseUri, uri)) {
            throw new AssayDefinitionException(
                "SECURITY_POLICY_VIOLATION",
                "Resolved request escaped the configured target"
            );
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(timeoutMs));
        for (Map.Entry<String, String> header : definition.headers().entrySet()) {
            builder.header(header.getKey(), renderer.renderString(header.getValue(), context));
        }
        String body = definition.body() == null
            ? null
            : objectMapper.writeValueAsString(renderer.render(definition.body(), context));
        builder.method(
            definition.method(),
            body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)
        );
        HttpResponse<InputStream> response = httpClient.send(
            builder.build(),
            HttpResponse.BodyHandlers.ofInputStream()
        );
        byte[] responseBytes;
        try (InputStream bodyStream = response.body()) {
            responseBytes = bodyStream.readNBytes(MAX_RESPONSE_BYTES + 1);
        }
        if (responseBytes.length > MAX_RESPONSE_BYTES) {
            throw new AssayDefinitionException(
                "REQUEST_FAILED",
                "HTTP response body exceeds the configured limit"
            );
        }
        return new HttpResult(
            response.statusCode(),
            parseBody(new String(responseBytes, StandardCharsets.UTF_8))
        );
    }

    private ObjectNode extract(HttpResult response, Map<String, String> extractors) {
        ObjectNode result = objectMapper.createObjectNode();
        extractors.forEach((name, path) -> result.set(name, valueAt(response, path)));
        return result;
    }

    private List<AssayCaseResult.AssertionDiff> assertResponse(
        HttpResult response,
        List<AssayCaseDefinition.ResponseAssertion> assertions
    ) {
        List<AssayCaseResult.AssertionDiff> diffs = new ArrayList<>();
        for (int index = 0; index < assertions.size(); index++) {
            AssayCaseDefinition.ResponseAssertion assertion = assertions.get(index);
            JsonNode actual = valueAt(response, assertion.path());
            boolean passed = matches(assertion.operator(), actual, assertion.expected());
            if (!passed) {
                diffs.add(new AssayCaseResult.AssertionDiff(
                    index,
                    assertion.path(),
                    assertion.operator(),
                    display(assertion.path(), assertion.expected()),
                    display(assertion.path(), actual),
                    "Value mismatch"
                ));
            }
        }
        return diffs;
    }

    private List<AssayCaseResult.AssertionDiff> assertMockCalls(
        WireMockServer server,
        List<AssayCaseDefinition.HttpMockDefinition> mocks
    ) {
        List<AssayCaseResult.AssertionDiff> diffs = new ArrayList<>();
        for (int index = 0; index < mocks.size(); index++) {
            AssayCaseDefinition.HttpMockDefinition mock = mocks.get(index);
            int count = server.findAll(requestedFor(mock.method(), urlEqualTo(mock.path()))).size();
            if (count < mock.minCalls() || count > mock.maxCalls()) {
                diffs.add(new AssayCaseResult.AssertionDiff(
                    index,
                    "$.mocks." + mock.id() + ".calls",
                    "number_between",
                    mock.minCalls() + ".." + mock.maxCalls(),
                    Integer.toString(count),
                    "Mock call count mismatch"
                ));
            }
        }
        return diffs;
    }

    private boolean matches(String operator, JsonNode actual, JsonNode expected) {
        boolean exists = actual != null && !actual.isMissingNode();
        return switch (operator) {
            case "exists" -> exists;
            case "not_exists" -> !exists;
            case "equals" -> exists && actual.equals(expected);
            case "not_equals" -> !exists || !actual.equals(expected);
            case "contains" -> exists && contains(actual, expected);
            case "number_between" -> exists && numberBetween(actual, expected);
            case "array_size" -> exists && actual.isArray() && expected != null
                && expected.isIntegralNumber() && actual.size() == expected.asInt();
            default -> false;
        };
    }

    private boolean contains(JsonNode actual, JsonNode expected) {
        if (actual.isTextual() && expected != null && expected.isTextual()) {
            return actual.asText().contains(expected.asText());
        }
        if (actual.isArray() && expected != null) {
            for (JsonNode item : actual) {
                if (item.equals(expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean numberBetween(JsonNode actual, JsonNode expected) {
        return actual.isNumber() && expected != null && expected.isArray() && expected.size() == 2
            && expected.get(0).isNumber() && expected.get(1).isNumber()
            && actual.decimalValue().compareTo(expected.get(0).decimalValue()) >= 0
            && actual.decimalValue().compareTo(expected.get(1).decimalValue()) <= 0;
    }

    private JsonNode valueAt(HttpResult response, String path) {
        if ("$.status".equals(path)) {
            return objectMapper.getNodeFactory().numberNode(response.status());
        }
        JsonNode value = response.body();
        String suffix = path.substring("$.body".length());
        if (suffix.isEmpty()) {
            return value;
        }
        for (String segment : suffix.substring(1).split("\\.")) {
            value = value.path(segment);
        }
        return value;
    }

    private JsonNode parseBody(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            return TextNode.valueOf(body);
        }
    }

    private AssayCaseResult error(
        AssayCaseDefinition definition,
        long started,
        String failureCode,
        String message
    ) {
        return new AssayCaseResult(
            definition.caseKey(),
            definition.sourcePath(),
            "ERROR",
            failureCode,
            elapsedMillis(started),
            List.of(new AssayCaseResult.AssertionDiff(
                0,
                "$",
                "execution",
                null,
                null,
                message
            ))
        );
    }

    private URI validateBaseUri(String value) {
        URI uri = URI.create(value);
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
            throw new IllegalArgumentException("Assay target base URL must use HTTP or HTTPS");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
            || uri.getFragment() != null) {
            throw new IllegalArgumentException("Assay target base URL is invalid");
        }
        String normalized = value.endsWith("/") ? value : value + "/";
        return URI.create(normalized);
    }

    private boolean sameOrigin(URI expected, URI actual) {
        return expected.getScheme().equalsIgnoreCase(actual.getScheme())
            && expected.getHost().equalsIgnoreCase(actual.getHost())
            && effectivePort(expected) == effectivePort(actual);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private String display(String path, JsonNode value) {
        String normalized = path.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("password") || normalized.contains("passwd")
            || normalized.contains("secret") || normalized.contains("token")
            || normalized.contains("apikey") || normalized.contains("api_key")) {
            return "[REDACTED]";
        }
        if (value == null || value.isMissingNode()) {
            return null;
        }
        String text = value.toString();
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private record HttpResult(int status, JsonNode body) {
    }
}
