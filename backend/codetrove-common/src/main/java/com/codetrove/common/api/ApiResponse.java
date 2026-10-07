package com.codetrove.common.api;

import java.util.Map;

public record ApiResponse<T>(T data, ApiMeta meta) {

    public static <T> ApiResponse<T> success(T data, String traceId) {
        return new ApiResponse<>(data, new ApiMeta(traceId, null));
    }

    public static <T> ApiResponse<T> paged(T data, String traceId, String nextCursor) {
        return new ApiResponse<>(data, new ApiMeta(traceId, nextCursor));
    }

    public record ApiMeta(String traceId, String nextCursor) {
    }

    public record ErrorResponse(ApiError error) {
    }

    public record ApiError(String code, String message, Map<String, Object> details, String traceId) {
    }
}
