package com.codetrove.common.api;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;

public final class TraceContext {

    public static final String HEADER_NAME = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    private static final int MAX_LENGTH = 128;
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._:-]+");

    private TraceContext() {
    }

    public static String currentTraceId() {
        return MDC.get(MDC_KEY);
    }

    public static String normalizeOrCreate(String candidate) {
        if (candidate != null) {
            String normalized = candidate.trim();
            if (!normalized.isEmpty()
                && normalized.length() <= MAX_LENGTH
                && SAFE_TRACE_ID.matcher(normalized).matches()) {
                return normalized;
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
