package com.__found.smart_library.domain.scan.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 회차의 점검 대상 서가. created_at/updated_at 컬럼이 없어 BaseEntity 를 상속하지 않는다.
 * PK 를 직접 지정하므로 Persistable 로 신규 여부를 알려 준다. 그렇지 않으면 save() 가 매번
 * SELECT 후 INSERT(merge)를 수행해, 서가 수만큼 불필요한 조회가 추가된다.
 */
@Getter
@Entity
@Table(name = "scan_session_shelf")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScanSessionShelf implements Persistable<ScanSessionShelfId> {

    @EmbeddedId
    private ScanSessionShelfId id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false, length = 30)
    private ScanShelfStatus scanStatus;

    @Getter(AccessLevel.NONE)
    @Transient
    private boolean newEntity = true;

    private ScanSessionShelf(Long scanSessionId, Long shelfId) {
        this.id = new ScanSessionShelfId(scanSessionId, shelfId);
        this.scanStatus = ScanShelfStatus.WAITING;
    }

    public static ScanSessionShelf waiting(Long scanSessionId, Long shelfId) {
        return new ScanSessionShelf(scanSessionId, shelfId);
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.newEntity = false;
    }
}
