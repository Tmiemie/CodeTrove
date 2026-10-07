package com.codetrove.bootstrap.web;

import java.util.Map;

import com.codetrove.common.api.ApiResponse;
import com.codetrove.common.api.TraceContext;
import com.codetrove.common.exception.ErrorCode;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("/error")
    ResponseEntity<ApiResponse.ErrorResponse> error(HttpServletRequest request) {
        int rawStatus = status(request);
        HttpStatus status = HttpStatus.resolve(rawStatus);
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        ErrorCode code = status == HttpStatus.NOT_FOUND
            ? ErrorCode.RESOURCE_NOT_FOUND
            : ErrorCode.INTERNAL_ERROR;
        ApiResponse.ApiError error = new ApiResponse.ApiError(
            code.name(),
            code.defaultMessage(),
            Map.of(),
            TraceContext.currentTraceId()
        );
        return ResponseEntity.status(status).body(new ApiResponse.ErrorResponse(error));
    }

    private int status(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (value instanceof Integer integer) {
            return integer;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR.value();
    }
}
