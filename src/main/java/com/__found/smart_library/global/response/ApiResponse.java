package com.__found.smart_library.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 성공 응답 공통 포맷. 실패는 {@link ErrorResponse} 를 사용한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null);
    }
}
