package com.__found.smart_library.domain.observation.dto;

import com.__found.smart_library.domain.scan.entity.ScanSessionStatus;

/**
 * 결과 수신 응답(최초 201, 동일 재전송 200 공통). 관측 목록은 조회 API 로 가져온다.
 * status 는 응답 시점의 회차 상태이며 재전송 시 이미 COMPLETED 일 수 있다.
 * 건수는 자동 분석 결과 기준이고 사서 검토로 바뀌지 않는다.
 */
public record VisionResultResponse(
        Long scanSessionId,
        ScanSessionStatus status,
        int shelfCount,
        long observationCount,
        long normalCount,
        long misplacedCount,
        long reviewRequiredCount
) {
}
