package com.__found.smart_library.domain.condition.repository;

import com.__found.smart_library.domain.condition.entity.BookConditionReport;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookConditionReportRepository extends JpaRepository<BookConditionReport, Long> {

    /** 최신 기록이 먼저. 같은 시각이면 나중에 저장된 것이 먼저. */
    List<BookConditionReport> findByBookIdOrderByReportedAtDescIdDesc(Long bookId);
}
