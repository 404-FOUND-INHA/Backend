package com.__found.smart_library.domain.observation.service;

import com.__found.smart_library.domain.observation.dto.ObservationDetailResponse;
import com.__found.smart_library.domain.observation.dto.ScanAnalysisResponse;
import com.__found.smart_library.domain.observation.dto.ScanAnalysisResponse.Counts;
import com.__found.smart_library.domain.observation.dto.ShelfObservationsResponse;
import com.__found.smart_library.domain.observation.entity.ObservationAnalysisStatus;
import com.__found.smart_library.domain.observation.entity.ObservationStatusReason;
import com.__found.smart_library.domain.observation.repository.CandidateRow;
import com.__found.smart_library.domain.observation.repository.DuplicateRow;
import com.__found.smart_library.domain.observation.repository.ObservationQueryRepository;
import com.__found.smart_library.domain.observation.repository.ObservationRow;
import com.__found.smart_library.domain.observation.repository.ShelfRow;
import com.__found.smart_library.domain.observation.repository.StatusCountRow;
import com.__found.smart_library.domain.scan.entity.ProcessingStage;
import com.__found.smart_library.domain.scan.entity.ScanSession;
import com.__found.smart_library.domain.scan.repository.ScanSessionRepository;
import com.__found.smart_library.global.exception.BusinessException;
import com.__found.smart_library.global.exception.ErrorCode;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자동 분석 결과 조회(읽기 전용). 이 서비스의 쿼리 수는 관측·서가 수와 무관하게 API 당 고정이다
 * (요약 3, 서가 목록 3, 상세 3~4; JWT 인증 필터의 사용자 조회 SQL 은 제외). 회차 상태와 무관하게 조회할 수 있으며 수신 전에는 빈 결과를 돌려준다.
 *
 * <p>한 API 응답은 여러 SELECT 로 만들어지므로 REPEATABLE_READ 로 하나의 스냅샷을 보게 한다. 기본 격리 수준
 * (READ_COMMITTED)에서는 SELECT 사이에 Vision 수신이 커밋되면 "IN_PROGRESS·WAITING 서가·관측 100건"처럼 서로 다른
 * 시점의 데이터가 한 응답에 섞일 수 있다. PostgreSQL 의 REPEATABLE_READ 는 첫 SELECT 시점의 스냅샷을 유지하며
 * 읽기 전용이라 직렬화 실패나 잠금 대기가 없다(수신 쪽 PESSIMISTIC_WRITE 와도 충돌하지 않는다).
 * 클래스 단위로 적용해 public 서비스 메서드 3개 모두에 같은 정책이 걸린다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ObservationQueryService {

    private final ScanSessionRepository scanSessionRepository;
    private final ObservationQueryRepository queryRepository;

    /** 쿼리: 회차 1 + 대상 서가 1 + 서가·상태·사유별 집계 1. */
    public ScanAnalysisResponse getAnalysis(Long sessionId) {
        ScanSession session = scanSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCAN_SESSION_NOT_FOUND));
        List<ShelfRow> shelves = queryRepository.findShelves(sessionId);
        List<StatusCountRow> rows = queryRepository.countByShelfStatusReason(sessionId);

        Tally total = new Tally();
        Map<Long, Tally> perShelf = new HashMap<>();
        Map<ObservationStatusReason, Long> reasonCounts = new EnumMap<>(ObservationStatusReason.class);
        for (StatusCountRow row : rows) {
            long n = row.total();
            total.add(row.status(), n);
            if (row.shelfId() != null) {
                perShelf.computeIfAbsent(row.shelfId(), k -> new Tally()).add(row.status(), n);
            }
            if (row.reason() != null) {
                reasonCounts.merge(row.reason(), n, Long::sum);
            }
        }

        List<ScanAnalysisResponse.ShelfStatus> shelfStatuses = shelves.stream()
                .map(s -> new ScanAnalysisResponse.ShelfStatus(s.shelfId(), s.shelfCode(), s.widthCm(),
                        s.scanStatus(), s.detectedBookCount(), s.scannedAt(),
                        perShelf.getOrDefault(s.shelfId(), new Tally()).toCounts()))
                .toList();
        return new ScanAnalysisResponse(
                new ScanAnalysisResponse.SessionInfo(session.getId(), session.getStatus(),
                        ProcessingStage.from(session.getStatus())),
                total.toCounts(), reasonCounts, shelfStatuses);
    }

    /** 쿼리: 회차 존재 1 + 서가(회차 대상 여부 포함) 1 + 관측 목록 1. */
    public ShelfObservationsResponse getShelfObservations(Long sessionId, Long shelfId) {
        requireSession(sessionId);
        ShelfRow shelf = queryRepository.findShelf(sessionId, shelfId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCAN_SHELF_NOT_IN_SESSION));
        List<ShelfObservationsResponse.ObservationItem> items = queryRepository.findByShelf(sessionId, shelfId)
                .stream().map(ShelfObservationsResponse.ObservationItem::from).toList();
        return new ShelfObservationsResponse(ShelfObservationsResponse.ShelfInfo.from(shelf), items);
    }

    /**
     * 쿼리: 회차 존재 1 + 관측 1 + 후보 1 + (중복 관측일 때만) 같은 도서의 다른 관측 전부 1.
     * 중복 조회는 쿼리 1회로 모든 건을 가져오며 결과 건수는 제한하지 않는다.
     */
    public ObservationDetailResponse getObservation(Long sessionId, Long observationId) {
        requireSession(sessionId);
        ObservationRow row = queryRepository.findInSession(sessionId, observationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCAN_OBSERVATION_NOT_FOUND));
        List<CandidateRow> candidates = queryRepository.findCandidates(observationId);
        List<DuplicateRow> duplicates = List.of();
        if (row.statusReason() == ObservationStatusReason.DUPLICATE_OBSERVATION && row.bookId() != null) {
            duplicates = queryRepository.findOtherObservationsOfBook(sessionId, row.bookId(), observationId);
        }
        return ObservationDetailResponse.of(row, candidates, duplicates);
    }

    private void requireSession(Long sessionId) {
        if (!scanSessionRepository.existsById(sessionId)) {
            throw new BusinessException(ErrorCode.SCAN_SESSION_NOT_FOUND);
        }
    }

    /** 분석 상태별 누적 건수. */
    private static final class Tally {
        private long normal;
        private long misplaced;
        private long reviewRequired;

        void add(ObservationAnalysisStatus status, long n) {
            switch (status) {
                case NORMAL -> normal += n;
                case MISPLACED -> misplaced += n;
                case REVIEW_REQUIRED -> reviewRequired += n;
            }
        }

        Counts toCounts() {
            return new Counts(normal + misplaced + reviewRequired, normal, misplaced, reviewRequired);
        }
    }
}
