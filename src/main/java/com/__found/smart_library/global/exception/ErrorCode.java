package com.__found.smart_library.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 서비스 전역 에러 코드. 도메인별 코드는 접두어(USER_, BOOK_ ...)를 붙여 추가한다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Common
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_001", "잘못된 입력입니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON_002", "지원하지 않는 HTTP 메서드입니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_003", "요청한 리소스를 찾을 수 없습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON_004", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON_005", "접근 권한이 없습니다."),
    CONCURRENT_UPDATE(HttpStatus.CONFLICT, "COMMON_006", "다른 요청이 먼저 수정했습니다. 다시 조회한 뒤 시도해 주세요."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_999", "서버 내부 오류가 발생했습니다."),

    // Auth / User
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_001", "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_002", "유효하지 않거나 만료된 Refresh Token입니다."),
    EMAIL_DUPLICATED(HttpStatus.CONFLICT, "USER_001", "이미 가입된 이메일입니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_002", "사용자를 찾을 수 없습니다."),

    // Shelf
    SHELF_NOT_FOUND(HttpStatus.NOT_FOUND, "SHELF_001", "서가를 찾을 수 없습니다."),
    SHELF_MARKER_DUPLICATED(HttpStatus.CONFLICT, "SHELF_002", "이미 사용 중인 마커 ID입니다."),

    // Book
    BOOK_NOT_FOUND(HttpStatus.NOT_FOUND, "BOOK_001", "도서를 찾을 수 없습니다."),
    BOOK_REGISTRATION_NO_DUPLICATED(HttpStatus.CONFLICT, "BOOK_002", "이미 등록된 도서 등록번호입니다."),

    // Scan (장서점검)
    SCAN_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "SCAN_001", "장서점검 회차를 찾을 수 없습니다."),
    SCAN_SESSION_ALREADY_OPEN(HttpStatus.CONFLICT, "SCAN_002", "해당 자료실에 완료되지 않은 점검 회차가 있습니다."),
    SCAN_NO_ACTIVE_SHELF(HttpStatus.BAD_REQUEST, "SCAN_003", "점검할 수 있는 서가가 없는 자료실입니다."),
    SCAN_PARSER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "SCAN_004", "청구기호 파서를 사용할 수 없어 분석할 수 없습니다."),
    SCAN_RESULT_CONFLICT(HttpStatus.CONFLICT, "SCAN_005", "이미 수신된 결과와 다른 결과입니다. 수신된 결과는 교체할 수 없습니다."),
    SCAN_RESULT_NOT_RECEIVABLE(HttpStatus.CONFLICT, "SCAN_006", "결과를 수신할 수 없는 회차 상태입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
