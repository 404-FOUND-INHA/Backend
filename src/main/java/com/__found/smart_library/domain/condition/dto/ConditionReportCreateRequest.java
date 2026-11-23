package com.__found.smart_library.domain.condition.dto;

import com.__found.smart_library.domain.condition.entity.ConditionSeverity;
import com.__found.smart_library.domain.condition.entity.ConditionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ConditionReportCreateRequest(
        @NotNull ConditionType conditionType,
        @NotNull ConditionSeverity severity,
        /** 관련 점검 회차(선택). */
        Long scanSessionId,
        @Size(max = 2000) String note,
        /** 증빙 이미지 주소(선택). 화면에서 그대로 열리므로 http(s) 만 허용한다. */
        @Size(max = 500) @Pattern(regexp = "^https?://\\S+$", message = "http 또는 https 주소여야 합니다.") String imageUrl
) {
}
