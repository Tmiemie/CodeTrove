package com.codetrove.assay;

final class AssayDefinitionException extends RuntimeException {

    private final String failureCode;

    AssayDefinitionException(String failureCode, String message) {
        super(message);
        this.failureCode = failureCode;
    }

    String failureCode() {
        return failureCode;
    }
}
