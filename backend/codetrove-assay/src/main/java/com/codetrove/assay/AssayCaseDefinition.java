package com.codetrove.assay;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

record AssayCaseDefinition(
    String schemaVersion,
    String caseKey,
    String description,
    boolean enabled,
    int timeoutMs,
    List<PreStep> dataPre,
    HttpRequestDefinition request,
    List<HttpMockDefinition> mocks,
    List<ResponseAssertion> assertions,
    String sourcePath
) {
    record PreStep(
        String key,
        HttpRequestDefinition request,
        String saveAs,
        Map<String, String> extract
    ) {
    }

    record HttpRequestDefinition(
        String target,
        String method,
        String path,
        Map<String, String> headers,
        JsonNode body
    ) {
    }

    record HttpMockDefinition(
        String id,
        String method,
        String path,
        int status,
        Map<String, String> headers,
        JsonNode body,
        int minCalls,
        int maxCalls
    ) {
    }

    record ResponseAssertion(String operator, String path, JsonNode expected) {
    }
}
