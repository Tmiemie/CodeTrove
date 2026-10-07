package com.codetrove.auth;

import java.io.IOException;
import java.util.Map;

import com.codetrove.common.api.ApiResponse;
import com.codetrove.common.api.TraceContext;
import com.codetrove.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
class SecurityErrorWriter {

    private final ObjectMapper objectMapper;

    SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(errorCode.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiResponse.ErrorResponse body = new ApiResponse.ErrorResponse(
            new ApiResponse.ApiError(
                errorCode.name(),
                errorCode.defaultMessage(),
                Map.of(),
                TraceContext.currentTraceId()
            )
        );
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
