package com.__found.smart_library.domain.scan.dto;

import com.__found.smart_library.domain.scan.entity.ScanSession;
import com.__found.smart_library.domain.scan.entity.ScanSessionStatus;
import java.time.LocalDateTime;

/**
 * 회차 확정 결과. summary 의 값은 모두 건수이다.
 *
 * <ul>
 *   <li>confirmedBooks: 점검에서 위치가 확인되어 BOOK 에 반영한 도서 수(자동 정상·오배치 + 사서가 도서를 확정한 것)</li>
 *   <li>locationChanged: 그중 마지막 확인 서가가 실제로 바뀐 도서 수</li>
 *   <li>restoredFromMissing: 소재불명이었다가 찾아서 정상으로 되돌린 도서 수</li>
 *   <li>unidentified / excluded: 사서가 식별 불가·제외로 처리해 BOOK 에 반영하지 않은 관측 수</li>
 * </ul>
 */
public record FinalizeResponse(
        Long sessionId,
        ScanSessionStatus status,
        LocalDateTime finalizedAt,
        Long finalizedBy,
        Summary summary
) {

    public record Summary(long confirmedBooks, long locationChanged, long restoredFromMissing,
                          long unidentified, long excluded) {
    }

    public static FinalizeResponse of(ScanSession session, Summary summary) {
        return new FinalizeResponse(session.getId(), session.getStatus(), session.getFinalizedAt(),
                session.getFinalizedBy(), summary);
    }
}
