package com.__found.smart_library.domain.condition.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 현장에서 기록한 도서 오손·파손. 증빙이므로 저장 후 수정하지 않는다(새 기록을 추가한다). */
@Getter
@Entity
@Table(name = "book_condition_report")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BookConditionReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "condition_report_id")
    private Long id;

    @Column(nullable = false)
    private Long bookId;

    private Long scanSessionId;

    @Column(nullable = false)
    private Long reporterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ConditionType conditionType;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ConditionSeverity severity;

    @Column(columnDefinition = "text")
    private String note;

    @Column(length = 500)
    private String imageUrl;

    @Column(nullable = false)
    private LocalDateTime reportedAt;

    private BookConditionReport(Long bookId, Long scanSessionId, Long reporterId, ConditionType conditionType,
                                ConditionSeverity severity, String note, String imageUrl, LocalDateTime reportedAt) {
        this.bookId = bookId;
        this.scanSessionId = scanSessionId;
        this.reporterId = reporterId;
        this.conditionType = conditionType;
        this.severity = severity;
        this.note = note;
        this.imageUrl = imageUrl;
        this.reportedAt = reportedAt;
    }

    public static BookConditionReport create(Long bookId, Long scanSessionId, Long reporterId,
                                             ConditionType conditionType, ConditionSeverity severity, String note,
                                             String imageUrl, LocalDateTime reportedAt) {
        return new BookConditionReport(bookId, scanSessionId, reporterId, conditionType, severity, note, imageUrl,
                reportedAt);
    }
}
