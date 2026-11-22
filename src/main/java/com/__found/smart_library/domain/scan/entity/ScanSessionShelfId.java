package com.__found.smart_library.domain.scan.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** scan_session_shelf 의 복합 PK (회차 + 서가). equals/hashCode 는 두 필드 기준. */
@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScanSessionShelfId implements Serializable {

    @Column(name = "scan_session_id", nullable = false)
    private Long scanSessionId;

    @Column(name = "shelf_id", nullable = false)
    private Long shelfId;

    public ScanSessionShelfId(Long scanSessionId, Long shelfId) {
        this.scanSessionId = scanSessionId;
        this.shelfId = shelfId;
    }
}
