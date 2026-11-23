package com.__found.smart_library.domain.condition.service;

import com.__found.smart_library.domain.book.repository.BookRepository;
import com.__found.smart_library.domain.condition.dto.ConditionReportCreateRequest;
import com.__found.smart_library.domain.condition.dto.ConditionReportResponse;
import com.__found.smart_library.domain.condition.entity.BookConditionReport;
import com.__found.smart_library.domain.condition.repository.BookConditionReportRepository;
import com.__found.smart_library.domain.scan.repository.ScanSessionRepository;
import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import com.__found.smart_library.global.exception.BusinessException;
import com.__found.smart_library.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConditionReportService {

    private final BookConditionReportRepository reportRepository;
    private final BookRepository bookRepository;
    private final ScanSessionRepository scanSessionRepository;
    private final AppUserRepository userRepository;
    private final Clock clock;

    /** 도서의 오손 기록(최신순). 쿼리는 기록 수와 무관하게 3개(도서 확인 + 기록 + 기록자 일괄 조회)이다. */
    public List<ConditionReportResponse> list(Long bookId) {
        requireBook(bookId);
        List<BookConditionReport> reports = reportRepository.findByBookIdOrderByReportedAtDescIdDesc(bookId);
        Map<Long, String> names = findReporterNames(reports.stream().map(BookConditionReport::getReporterId).toList());
        return reports.stream()
                .map(r -> ConditionReportResponse.of(r, names.get(r.getReporterId())))
                .toList();
    }

    @Transactional
    public ConditionReportResponse create(Long bookId, ConditionReportCreateRequest request, Long reporterId) {
        requireBook(bookId);
        if (request.scanSessionId() != null && !scanSessionRepository.existsById(request.scanSessionId())) {
            throw new BusinessException(ErrorCode.SCAN_SESSION_NOT_FOUND);
        }
        BookConditionReport saved = reportRepository.save(BookConditionReport.create(
                bookId, request.scanSessionId(), reporterId, request.conditionType(), request.severity(),
                request.note(), request.imageUrl(), LocalDateTime.now(clock)));
        return ConditionReportResponse.of(saved, findReporterNames(List.of(reporterId)).get(reporterId));
    }

    private void requireBook(Long bookId) {
        if (!bookRepository.existsById(bookId)) {
            throw new BusinessException(ErrorCode.BOOK_NOT_FOUND);
        }
    }

    private Map<Long, String> findReporterNames(List<Long> userIds) {
        Map<Long, String> names = new HashMap<>();
        for (AppUser user : userRepository.findAllById(userIds.stream().distinct().toList())) {
            names.put(user.getId(), user.getName());
        }
        return names;
    }
}
