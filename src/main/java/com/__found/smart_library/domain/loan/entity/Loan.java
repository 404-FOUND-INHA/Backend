package com.__found.smart_library.domain.loan.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 대출 이력(읽기 전용). 대출 데이터는 외부 시스템에서 적재되므로 이 서비스에서는 수정하지 않고
 * 평가 지표(수요성)를 계산할 때만 조회한다.
 */
@Getter
@Entity
@Immutable
@Table(name = "loan")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Loan {

    @Id
    @Column(name = "loan_id")
    private Long id;

    @Column(name = "book_id", nullable = false)
    private Long bookId;

    @Column(name = "loaned_at", nullable = false)
    private LocalDateTime loanedAt;
}
