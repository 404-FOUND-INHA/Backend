package com.__found.smart_library.domain.review.dto;

import com.__found.smart_library.domain.observation.entity.ObservationStatusReason;
import com.__found.smart_library.domain.review.repository.ReviewItemRow;
import com.__found.smart_library.global.response.PageResponse;

/**
 * 사서 검토 대상 목록. summary 는 서가·검토 여부 필터와 무관한 회차 전체 집계이고, items 는 필터가 적용된 페이지다.
 * 후보 도서의 상세는 관측 상세 API(GET /api/scan-sessions/{sessionId}/observations/{observationId})에서 본다.
 */
public record ReviewListResponse(Summary summary, PageResponse<Item> items) {

    public record Summary(long totalRequired, long reviewed, long remaining) {
        public static Summary of(long totalRequired, long reviewed) {
            return new Summary(totalRequired, reviewed, totalRequired - reviewed);
        }
    }

    /** review 가 null 이면 아직 검토하지 않은 관측이다. autoMatchedBook 은 자동 매칭된 도서가 없으면 null. */
    public record Item(
            Long observationId,
            Long shelfId,
            String shelfCode,
            Integer detectedOrder,
            String detectedLabelRaw,
            String detectedTitle,
            ObservationStatusReason statusReason,
            BookBrief autoMatchedBook,
            long candidateCount,
            ReviewResult review
    ) {
        public static Item from(ReviewItemRow r) {
            BookBrief auto = r.autoBookId() == null ? null
                    : new BookBrief(r.autoBookId(), r.autoRegistrationNo(), r.autoTitle(), r.autoCallNumber());
            return new Item(r.observationId(), r.shelfId(), r.shelfCode(), r.detectedOrder(), r.detectedLabelRaw(),
                    r.detectedTitle(), r.statusReason(), auto, r.candidateCount(), ReviewResult.from(r));
        }
    }
}
