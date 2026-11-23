package com.__found.smart_library.domain.condition.dto;

import com.__found.smart_library.domain.condition.entity.BookConditionReport;
import com.__found.smart_library.domain.condition.entity.ConditionSeverity;
import com.__found.smart_library.domain.condition.entity.ConditionType;
import java.time.LocalDateTime;

public record ConditionReportResponse(
        Long id,
        Long bookId,
        Long scanSessionId,
        Long reporterId,
        String reporterName,
        ConditionType conditionType,
        ConditionSeverity severity,
        String note,
        String imageUrl,
        LocalDateTime reportedAt
) {

    /** reporterName 은 탈퇴 등으로 사용자를 찾지 못하면 null. */
    public static ConditionReportResponse of(BookConditionReport r, String reporterName) {
        return new ConditionReportResponse(r.getId(), r.getBookId(), r.getScanSessionId(), r.getReporterId(),
                reporterName, r.getConditionType(), r.getSeverity(), r.getNote(), r.getImageUrl(), r.getReportedAt());
    }
}
