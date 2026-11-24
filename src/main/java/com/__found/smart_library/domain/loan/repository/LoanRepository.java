package com.__found.smart_library.domain.loan.repository;

import com.__found.smart_library.domain.loan.entity.Loan;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanRepository extends JpaRepository<Loan, Long> {

    /** 도서별로 기준 시각 이후의 대출 건수를 센다. 대출 이력이 없는 도서는 결과에 나오지 않는다. */
    @Query("""
            select new com.__found.smart_library.domain.loan.repository.LoanCount(l.bookId, count(l))
            from Loan l
            where l.bookId in :bookIds and l.loanedAt >= :since
            group by l.bookId
            """)
    List<LoanCount> countSince(@Param("bookIds") Collection<Long> bookIds, @Param("since") LocalDateTime since);
}
