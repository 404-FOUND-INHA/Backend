package com.__found.smart_library.domain.review.dto;

import com.__found.smart_library.domain.review.entity.ReviewAction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 검토 요청. action 이 IDENTIFY_BOOK 이면 finalBookId 가 필요하고, 나머지 조치에서는 보내면 안 된다.
 * 최종 상태는 서버가 계산한다.
 */
public record ReviewRequest(
        @NotNull ReviewAction action,
        Long finalBookId,
        @Size(max = 1000) String comment
) {
}
