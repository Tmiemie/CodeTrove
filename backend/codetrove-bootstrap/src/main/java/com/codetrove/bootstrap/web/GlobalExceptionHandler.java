package com.codetrove.bootstrap.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.codetrove.common.api.ApiResponse;
import com.codetrove.common.api.TraceContext;
import com.codetrove.common.exception.BusinessException;
import com.codetrove.common.exception.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse.ErrorResponse> handleBusinessException(BusinessException exception) {
        ErrorCode code = exception.errorCode();
        return ResponseEntity.status(code.status())
            .body(error(code.name(), exception.getMessage(), exception.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse.ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, Object> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(fieldError ->
            details.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage()));
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.status())
            .body(error(code.name(), code.defaultMessage(), details));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiResponse.ErrorResponse> handleConstraintViolation(
        ConstraintViolationException exception
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        exception.getConstraintViolations().forEach(violation ->
            details.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage()));
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.status())
            .body(error(code.name(), code.defaultMessage(), details));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResponse.ErrorResponse> handleTypeMismatch(
        MethodArgumentTypeMismatchException exception
    ) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.status())
            .body(error(
                code.name(),
                code.defaultMessage(),
                Map.of(exception.getName(), "invalid value")
            ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse.ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.status())
            .body(error(code.name(), "Malformed request body", Map.of()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiResponse.ErrorResponse> handleNotFound(NoResourceFoundException exception) {
        ErrorCode code = ErrorCode.RESOURCE_NOT_FOUND;
        return ResponseEntity.status(code.status())
            .body(error(code.name(), code.defaultMessage(), Map.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse.ErrorResponse> handleUnexpected(
        Exception exception,
        HttpServletRequest request
    ) {
        log.error("Unhandled request failure: method={}, path={}", request.getMethod(), request.getRequestURI(), exception);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.status())
            .body(error(code.name(), code.defaultMessage(), Map.of()));
    }

    private ApiResponse.ErrorResponse error(
        String code,
        String message,
        Map<String, Object> details
    ) {
        return new ApiResponse.ErrorResponse(
            new ApiResponse.ApiError(code, message, details, TraceContext.currentTraceId())
        );
    }
}
