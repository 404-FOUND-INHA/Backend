package com.__found.smart_library.domain.evaluation.jev;

/** JEV 호출 실패. 메시지에는 API Key 와 도서 정보를 넣지 않는다. */
public class JevCallException extends RuntimeException {

    private final Integer httpStatus;
    private final boolean retryable;

    public JevCallException(String message, Integer httpStatus, boolean retryable, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    /** 응답을 받지 못한 실패(시간 초과 등)이면 null. */
    public Integer getHttpStatus() {
        return httpStatus;
    }

    /** 다시 호출하면 성공할 수 있는 실패인지(429·529·5xx·시간 초과). */
    public boolean isRetryable() {
        return retryable;
    }
}
