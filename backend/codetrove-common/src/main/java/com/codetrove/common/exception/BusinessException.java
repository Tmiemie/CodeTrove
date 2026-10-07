package com.codetrove.common.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), Map.of());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> details() {
        return details;
    }
}
