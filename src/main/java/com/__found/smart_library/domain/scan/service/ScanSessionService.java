package com.__found.smart_library.domain.scan.service;

import com.__found.smart_library.domain.scan.dto.ScanRoomResponse;
import com.__found.smart_library.domain.scan.dto.ScanSessionCreateRequest;
import com.__found.smart_library.domain.scan.dto.ScanSessionResponse;
import com.__found.smart_library.domain.scan.entity.ScanSession;
import com.__found.smart_library.domain.scan.entity.ScanSessionShelf;
import com.__found.smart_library.domain.scan.entity.ScanSessionStatus;
import com.__found.smart_library.domain.scan.repository.ScanSessionFloor;
import com.__found.smart_library.domain.scan.repository.ScanSessionRepository;
import com.__found.smart_library.domain.scan.repository.ScanSessionShelfRepository;
import com.__found.smart_library.domain.shelf.entity.Shelf;
import com.__found.smart_library.domain.shelf.repository.ShelfRepository;
import com.__found.smart_library.global.exception.BusinessException;
import com.__found.smart_library.global.exception.ErrorCode;
import com.__found.smart_library.global.lock.PostgresAdvisoryLock;
import com.__found.smart_library.global.response.PageResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScanSessionService {

    /** 장서점검 회차 생성용 Advisory Lock 네임스페이스. 다른 기능의 락 키와 겹치지 않게 고정값으로 쓴다. */
    static final int SCAN_SESSION_CREATE_LOCK_NAMESPACE = 40401;

    private static final int MAX_PAGE_SIZE = 100;

    private final ScanSessionRepository scanSessionRepository;
    private final ScanSessionShelfRepository scanSessionShelfRepository;
    private final ShelfRepository shelfRepository;
    private final PostgresAdvisoryLock advisoryLock;
    private final Clock clock;

    /** 활성 자료실 목록(쿼리 1) + 미완료 회차가 있는 자료실(쿼리 2)을 조합한다. 자료실 수와 무관하게 쿼리는 2개. */
    public List<ScanRoomResponse> getRooms() {
        Set<String> openZones = new HashSet<>(
                scanSessionRepository.findDistinctTargetAreasByStatusIn(ScanSessionStatus.OPEN_STATUSES));
        return shelfRepository.summarizeActiveZones().stream()
                .map(zone -> ScanRoomResponse.of(zone, openZones.contains(zone.getZone())))
                .toList();
    }

    /**
     * 회차 목록(최신순). 상태가 비어 있으면 전체. 쿼리는 항목 수와 무관하게 3개
     * (페이지 + count + 층 일괄 조회)이다.
     */
    public PageResponse<ScanSessionResponse> getSessions(Collection<ScanSessionStatus> statuses, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        // offset 은 JPA 에서 int 라 한도를 넘으면 DB 오류(500)가 되므로 입력 오류(400)로 막는다.
        if ((long) safePage * safeSize > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "page 값이 너무 큽니다.");
        }
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<ScanSession> sessions = (statuses == null || statuses.isEmpty())
                ? scanSessionRepository.findAll(pageable)
                : scanSessionRepository.findByStatusIn(statuses, pageable);
        Map<Long, Integer> floors = findFloors(sessions.getContent().stream().map(ScanSession::getId).toList());
        return PageResponse.from(sessions.map(s -> ScanSessionResponse.of(s, floors.get(s.getId()))));
    }

    public ScanSessionResponse getSession(Long id) {
        ScanSession session = scanSessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCAN_SESSION_NOT_FOUND));
        return ScanSessionResponse.of(session, findFloors(List.of(id)).get(id));
    }

    /** 회차 ID -> 층. 층 정보가 없으면 맵에 값이 없다(null). 빈 목록이면 쿼리를 실행하지 않는다. */
    private Map<Long, Integer> findFloors(List<Long> sessionIds) {
        Map<Long, Integer> floors = new HashMap<>();
        if (sessionIds.isEmpty()) {
            return floors;
        }
        for (ScanSessionFloor f : scanSessionShelfRepository.findMinFloorsBySessionIds(sessionIds)) {
            floors.put(f.getScanSessionId(), f.getFloorNo());
        }
        return floors;
    }

    /**
     * 회차 생성 + 대상 서가 등록을 하나의 트랜잭션으로 처리한다.
     * 락을 먼저 잡고 검사해야, 락을 기다린 요청이 앞 요청의 커밋 결과를 보고 중복을 거부할 수 있다.
     * 예외를 잡지 않으므로 어느 단계에서 실패해도 회차 생성까지 함께 롤백된다.
     */
    @Transactional
    public ScanSessionResponse create(ScanSessionCreateRequest request, Long userId) {
        validatePlannedStartDate(request.plannedStartDate());

        advisoryLock.lock(SCAN_SESSION_CREATE_LOCK_NAMESPACE, request.zone());

        if (scanSessionRepository.existsByTargetAreaAndStatusIn(request.zone(), ScanSessionStatus.OPEN_STATUSES)) {
            throw new BusinessException(ErrorCode.SCAN_SESSION_ALREADY_OPEN);
        }
        List<Shelf> shelves = shelfRepository.findByZoneAndActiveTrueOrderById(request.zone());
        if (shelves.isEmpty()) {
            throw new BusinessException(ErrorCode.SCAN_NO_ACTIVE_SHELF);
        }

        ScanSession session = scanSessionRepository.save(ScanSession.create(
                request.sessionName(), request.zone(), request.plannedStartDate(), userId));
        scanSessionShelfRepository.saveAll(shelves.stream()
                .map(shelf -> ScanSessionShelf.waiting(session.getId(), shelf.getId()))
                .toList());

        Integer floorNo = shelves.stream().map(Shelf::getFloorNo).filter(Objects::nonNull)
                .min(Integer::compare).orElse(null);
        return ScanSessionResponse.of(session, floorNo);
    }

    /** 예정일은 오늘(서울 기준) 이후만 허용한다. 시스템 기본 시간대가 아닌 주입된 Clock 의 시간대를 쓴다. */
    private void validatePlannedStartDate(LocalDate plannedStartDate) {
        if (plannedStartDate != null && plannedStartDate.isBefore(LocalDate.now(clock))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "점검 예정일은 오늘 이후여야 합니다.");
        }
    }
}
