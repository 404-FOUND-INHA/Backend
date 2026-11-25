package com.__found.smart_library.domain.evaluation.jev;

/** 호출은 성공했지만 응답이 약속한 형식·범위를 벗어났다. 이 응답은 저장하지 않는다. */
public class JevInvalidResponseException extends RuntimeException {

    public JevInvalidResponseException(String message) {
        super(message);
    }
}
