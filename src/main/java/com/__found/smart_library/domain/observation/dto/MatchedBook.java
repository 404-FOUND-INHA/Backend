package com.__found.smart_library.domain.observation.dto;

import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.observation.repository.CandidateRow;
import com.__found.smart_library.domain.observation.repository.ObservationRow;
import java.math.BigDecimal;

/**
 * 관측과 연결된 도서(자동 매칭 도서 또는 후보 도서)의 표시 정보. 도서 원장의 값을 그대로 보여 주며
 * 대출 상태는 신뢰할 수 있는 출처가 확정되지 않아 포함하지 않는다.
 * expectedShelfId·expectedShelfCode 는 도서의 예상 서가이고 지정되지 않았으면 null 이다.
 * bookWidthCm(책 두께)는 원장에 값이 없으면 null 이며 FE 가 기본 폭을 쓴다.
 */
public record MatchedBook(
        Long bookId,
        String registrationNo,
        String title,
        String callNumber,
        String copyNumber,
        LifecycleStatus lifecycleStatus,
        Long expectedShelfId,
        String expectedShelfCode,
        BigDecimal bookWidthCm
) {

    /** 관측에 매칭된 도서가 없으면(bookId null) null. */
    public static MatchedBook from(ObservationRow r) {
        if (r.bookId() == null) {
            return null;
        }
        return new MatchedBook(r.bookId(), r.registrationNo(), r.bookTitle(), r.callNumber(), r.copyNumber(),
                r.lifecycleStatus(), r.expectedShelfId(), r.expectedShelfCode(), r.bookWidthCm());
    }

    public static MatchedBook from(CandidateRow r) {
        return new MatchedBook(r.bookId(), r.registrationNo(), r.title(), r.callNumber(), r.copyNumber(),
                r.lifecycleStatus(), r.expectedShelfId(), r.expectedShelfCode(), r.bookWidthCm());
    }
}
