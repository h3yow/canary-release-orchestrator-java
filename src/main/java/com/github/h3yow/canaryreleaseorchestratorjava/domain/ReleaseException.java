package com.github.h3yow.canaryreleaseorchestratorjava.domain;

/**
 * 携带可区分错误码的领域异常。
 */
public class ReleaseException extends RuntimeException {
    private final ErrorCode errorCode;

    public ReleaseException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
