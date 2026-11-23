package com.__found.smart_library.domain.observation.service;

import com.__found.smart_library.domain.observation.dto.VisionResultRequest;
import com.__found.smart_library.domain.observation.dto.VisionResultRequest.Observation;
import com.__found.smart_library.domain.observation.dto.VisionResultRequest.ShelfResult;
import com.__found.smart_library.domain.observation.dto.VisionResultResponse;
import com.__found.smart_library.domain.observation.entity.BookObservation;
import com.__found.smart_library.domain.observation.entity.ObservationAnalysisStatus;
import com.__found.smart_library.domain.observation.entity.ObservationMatchCandidate;
import com.__found.smart_library.domain.observation.repository.BookObservationRepository;
import com.__found.smart_library.domain.observation.repository.BookObservationRepository.StatusCount;
import com.__found.smart_library.domain.observation.repository.ObservationMatchCandidateRepository;
import com.__found.smart_library.domain.observation.repository.StoredObservationInput;
import com.__found.smart_library.domain.scan.entity.ScanSession;
import com.__found.smart_library.domain.scan.entity.ScanSessionShelf;
import com.__found.smart_library.domain.scan.entity.ScanSessionStatus;
import com.__found.smart_library.domain.scan.entity.ScanShelfStatus;
import com.__found.smart_library.domain.scan.repository.ScanSessionRepository;
import com.__found.smart_library.domain.scan.repository.ScanSessionShelfRepository;
import com.__found.smart_library.domain.shelf.entity.Shelf;
import com.__found.smart_library.global.exception.BusinessException;
import com.__found.smart_library.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vision 의 회차 전체 OCR 결과를 한 번에 받아 분석하고 저장한다. 전체가 하나의 트랜잭션이며 중간에 실패하면 모두 롤백된다.
 *
 * <ol>
 *   <li>요청 구조를 검증·정규화한다.</li>
 *   <li>회차 행을 FOR UPDATE 로 잠가 같은 회차의 동시 수신을 직렬화한다.</li>
 *   <li>회차가 IN_PROGRESS 이면 신규 수신: 모든 서가의 관측을 한 목록으로 모아 분석을 <b>한 번만</b> 호출하고
 *       (같은 도서의 중복 관측 검출은 호출 1회 범위에서 동작한다), 관측·후보·서가 집계를 저장한 뒤 REVIEW 로 전이한다.</li>
 *   <li>REVIEW·COMPLETED 이면 재전송: 저장된 입력값과 같으면 아무것도 바꾸지 않고 현재 상태를 돌려주고, 다르면 409 이다.
 *       수신한 결과는 교체하지 않는다.</li>
 * </ol>
 * 서가 판단 기준은 두 경우 모두 회차의 점검 대상 서가(scan_session_shelf)이다. 최초 수신 후 서가의 사용 여부나
 * 자료실이 바뀌어도 이미 수신한 결과의 동일 재전송이 거부되지 않게 shelf 의 현재 속성은 보지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VisionObservationService {

    /** 한 요청이 담을 수 있는 관측 수 상한. */
    static final int MAX_OBSERVATIONS = 5000;

    /** x_position_cm 은 DECIMAL(8,2) 라 정수부는 6자리까지이다. */
    private static final int MAX_X_INTEGER_DIGITS = 6;
    private static final int X_SCALE = 2;
    private static final int MAX_CODES_IN_MESSAGE = 10;

    private final ScanSessionRepository scanSessionRepository;
    private final ScanSessionShelfRepository scanSessionShelfRepository;
    private final BookObservationRepository bookObservationRepository;
    private final ObservationMatchCandidateRepository candidateRepository;
    private final ObservationAnalyzer analyzer;
    private final Clock clock;

    /** created=true 이면 이번 호출에서 새로 저장한 것(201), false 이면 동일 결과의 재전송(200). */
    public record ReceiveResult(VisionResultResponse response, boolean created) {
    }

    /** 정규화된 관측 1건(반올림·절삭 적용 후, 저장과 비교에 같은 값을 쓴다). */
    private record Item(int detectedOrder, String label, String title, BigDecimal xPositionCm,
                        LocalDateTime detectedAt) {
    }

    /** 서가 코드가 회차 대상 서가의 ID 로 확정된 결과. */
    private record ShelfItems(String shelfCode, Long shelfId, List<Item> items) {
    }

    @Transactional
    public ReceiveResult receive(VisionResultRequest request) {
        List<ShelfItems> shelves = normalize(request);

        ScanSession session = scanSessionRepository.findByIdForUpdate(request.scanSessionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SCAN_SESSION_NOT_FOUND));
        List<ScanSessionShelf> targets = scanSessionShelfRepository.findByIdScanSessionId(session.getId());
        List<ShelfItems> resolved = resolveTargetShelves(session.getId(), shelves);

        return session.getStatus() == ScanSessionStatus.IN_PROGRESS
                ? receiveNew(session, targets, resolved)
                : replay(session, targets, resolved);
    }

    // ---- 신규 수신 ----

    private ReceiveResult receiveNew(ScanSession session, List<ScanSessionShelf> targets, List<ShelfItems> shelves) {
        // IN_PROGRESS 인데 이미 수신 흔적이 남아 있으면 비정상이다. 덮어쓰지 않고 거부한다.
        if (targets.stream().anyMatch(t -> t.getScanStatus() != ScanShelfStatus.WAITING)
                || bookObservationRepository.existsByScanSessionId(session.getId())) {
            throw new BusinessException(ErrorCode.SCAN_RESULT_NOT_RECEIVABLE,
                    "진행 중인 회차에 이미 수신된 결과 데이터가 남아 있어 수신할 수 없습니다.");
        }
        List<Long> missing = missingTargetShelfIds(targets, shelves);
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "회차의 점검 대상 서가가 요청에 모두 포함되어야 합니다. 누락된 서가: " + shelfCodes(session.getId(), missing));
        }

        // 회차 전체 관측을 한 목록으로 모아 분석을 1회만 호출한다.
        List<Long> shelfIds = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        List<AnalysisInput> inputs = new ArrayList<>();
        for (ShelfItems shelf : shelves) {
            for (Item item : shelf.items()) {
                shelfIds.add(shelf.shelfId());
                items.add(item);
                inputs.add(new AnalysisInput(item.label(), shelf.shelfId()));
            }
        }
        List<ObservationAnalysis> analyses = analyzer.analyze(inputs);

        LocalDateTime receivedAt = LocalDateTime.now(clock);
        List<BookObservation> observations = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            observations.add(toObservation(session.getId(), shelfIds.get(i), items.get(i), analyses.get(i),
                    receivedAt));
        }
        bookObservationRepository.saveAll(observations);

        List<ObservationMatchCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < observations.size(); i++) {
            Long observationId = observations.get(i).getId();
            for (MatchResult.Candidate c : analyses.get(i).candidates()) {
                candidates.add(ObservationMatchCandidate.of(observationId, c.rank(), c.book().bookId(), c.reason()));
            }
        }
        candidateRepository.saveAll(candidates);

        Map<Long, List<Item>> itemsByShelf = shelves.stream()
                .collect(Collectors.toMap(ShelfItems::shelfId, ShelfItems::items));
        for (ScanSessionShelf target : targets) {
            List<Item> shelfItems = itemsByShelf.get(target.getId().getShelfId());
            LocalDateTime scannedAt = shelfItems.stream().map(Item::detectedAt).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
            target.receiveResult(shelfItems.size(), scannedAt);
        }
        session.markReviewReady();

        return new ReceiveResult(response(session, targets.size(), countOf(analyses)), true);
    }

    private BookObservation toObservation(Long sessionId, Long shelfId, Item item, ObservationAnalysis analysis,
                                          LocalDateTime receivedAt) {
        ParsedCallNumber parsed = analysis.parsed();
        return BookObservation.builder()
                .scanSessionId(sessionId)
                .detectedShelfId(shelfId)
                .detectedOrder(item.detectedOrder())
                .detectedLabelRaw(item.label())
                .detectedTitle(item.title())
                .xPositionCm(item.xPositionCm())
                .detectedAt(item.detectedAt())
                .detectedClassificationNo(parsed == null ? null : parsed.classificationNo())
                .detectedBookSymbol(parsed == null ? null : parsed.bookSymbol())
                .detectedCopyNumber(parsed == null ? null : parsed.copyNumber())
                .bookId(analysis.bookId())
                .matchConfidence(analysis.confidence())
                .analysisStatus(analysis.status())
                .statusReason(analysis.reason())
                .receivedAt(receivedAt)
                .build();
    }

    // ---- 재전송 ----

    private ReceiveResult replay(ScanSession session, List<ScanSessionShelf> targets, List<ShelfItems> shelves) {
        // 상태는 넘어갔는데 서가 수신 기록이 없으면 이 API 로 받은 결과가 아니므로 비교할 수 없다.
        if (targets.stream().anyMatch(t -> t.getScanStatus() != ScanShelfStatus.RESULT_RECEIVED)) {
            throw new BusinessException(ErrorCode.SCAN_RESULT_NOT_RECEIVABLE,
                    "수신된 결과가 없는 회차입니다. 현재 상태=" + session.getStatus());
        }
        // 대상 서가 누락은 입력 오류가 아니라 이미 수신한 결과와 다른 요청이다.
        if (!missingTargetShelfIds(targets, shelves).isEmpty()) {
            throw new BusinessException(ErrorCode.SCAN_RESULT_CONFLICT);
        }

        Set<StoredObservationInput> requested = new HashSet<>();
        int requestedCount = 0;
        for (ShelfItems shelf : shelves) {
            for (Item item : shelf.items()) {
                requested.add(new StoredObservationInput(shelf.shelfId(), item.detectedOrder(), item.label(),
                        item.title(), item.xPositionCm(), item.detectedAt()));
                requestedCount++;
            }
        }
        List<StoredObservationInput> stored = bookObservationRepository.findInputsByScanSessionId(session.getId());
        if (stored.size() != requestedCount || !new HashSet<>(stored).equals(requested)) {
            throw new BusinessException(ErrorCode.SCAN_RESULT_CONFLICT);
        }

        Map<ObservationAnalysisStatus, Long> counts = new HashMap<>();
        for (StatusCount c : bookObservationRepository.countByStatus(session.getId())) {
            counts.put(c.getStatus(), c.getTotal());
        }
        return new ReceiveResult(response(session, targets.size(), counts), false);
    }

    // ---- 검증·정규화 ----

    /**
     * 요청 구조를 검증하고 값을 저장 형식으로 정규화한다. 서가는 ID 가 확정되기 전이라 shelfId 는 null 이다.
     * x 좌표는 소수 둘째 자리로 반올림하고 촬영 시각은 마이크로초로 절삭한다(DB 정밀도와 같게 해 재전송이 달라 보이지 않게 함).
     */
    private List<ShelfItems> normalize(VisionResultRequest request) {
        long total = request.shelves().stream().mapToLong(s -> s.observations().size()).sum();
        if (total > MAX_OBSERVATIONS) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "한 번에 보낼 수 있는 관측은 최대 " + MAX_OBSERVATIONS + "건입니다. 받은 건수=" + total);
        }
        Set<String> codes = new HashSet<>();
        List<ShelfItems> result = new ArrayList<>();
        for (ShelfResult shelf : request.shelves()) {
            if (!codes.add(shelf.shelfCode())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "서가 코드가 중복되었습니다: " + shelf.shelfCode());
            }
            Set<Integer> orders = new HashSet<>();
            List<Item> items = new ArrayList<>();
            for (Observation o : shelf.observations()) {
                if (!orders.add(o.detectedOrder())) {
                    throw new BusinessException(ErrorCode.INVALID_INPUT,
                            "서가 " + shelf.shelfCode() + " 에 detectedOrder 가 중복되었습니다: " + o.detectedOrder());
                }
                items.add(new Item(o.detectedOrder(), o.detectedLabelRaw(), o.detectedTitle(),
                        normalizeX(o.xPositionCm(), shelf.shelfCode()),
                        o.detectedAt() == null ? null : o.detectedAt().truncatedTo(ChronoUnit.MICROS)));
            }
            items.sort(Comparator.comparingInt(Item::detectedOrder));
            result.add(new ShelfItems(shelf.shelfCode(), null, items));
        }
        return result;
    }

    private BigDecimal normalizeX(BigDecimal x, String shelfCode) {
        if (x == null) {
            return null;
        }
        // 지수가 큰 값의 반올림으로 시간을 쓰지 않도록 정수부 자릿수를 먼저 확인한다.
        if (x.precision() - x.scale() > MAX_X_INTEGER_DIGITS) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "서가 " + shelfCode + " 의 xPositionCm 이 허용 범위를 벗어났습니다.");
        }
        BigDecimal rounded = x.setScale(X_SCALE, RoundingMode.HALF_UP);
        if (rounded.precision() - rounded.scale() > MAX_X_INTEGER_DIGITS) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "서가 " + shelfCode + " 의 xPositionCm 이 허용 범위를 벗어났습니다.");
        }
        return rounded;
    }

    /**
     * 서가 코드를 회차의 점검 대상 서가로 확정한다. 판단 기준은 scan_session_shelf 이며 shelf 의 사용 여부·자료실은 보지 않는다.
     * 회차의 대상 서가를 한 번에 읽어 메모리에서 코드를 대조하므로 요청의 서가 수와 무관하게 쿼리는 1개이다.
     * 대상 서가에 없는 코드(등록되지 않은 코드 포함)는 신규 수신·재전송 모두 400 이다.
     */
    private List<ShelfItems> resolveTargetShelves(Long sessionId, List<ShelfItems> shelves) {
        Map<String, Shelf> targetByCode = scanSessionShelfRepository.findShelvesByScanSessionId(sessionId).stream()
                .collect(Collectors.toMap(Shelf::getShelfCode, s -> s));

        List<String> notTarget = shelves.stream().map(ShelfItems::shelfCode)
                .filter(code -> !targetByCode.containsKey(code)).toList();
        if (!notTarget.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "이 회차의 점검 대상이 아니거나 등록되지 않은 서가 코드입니다: " + limited(notTarget));
        }
        return shelves.stream()
                .map(s -> new ShelfItems(s.shelfCode(), targetByCode.get(s.shelfCode()).getId(), s.items()))
                .sorted(Comparator.comparing(ShelfItems::shelfId))
                .toList();
    }

    private List<Long> missingTargetShelfIds(List<ScanSessionShelf> targets, List<ShelfItems> shelves) {
        Set<Long> covered = shelves.stream().map(ShelfItems::shelfId).collect(Collectors.toSet());
        return targets.stream().map(t -> t.getId().getShelfId()).filter(id -> !covered.contains(id)).toList();
    }

    private String shelfCodes(Long sessionId, List<Long> shelfIds) {
        Set<Long> wanted = new HashSet<>(shelfIds);
        return limited(scanSessionShelfRepository.findShelvesByScanSessionId(sessionId).stream()
                .filter(s -> wanted.contains(s.getId())).map(Shelf::getShelfCode).sorted().toList());
    }

    private static String limited(List<String> codes) {
        if (codes.size() <= MAX_CODES_IN_MESSAGE) {
            return codes.toString();
        }
        return codes.subList(0, MAX_CODES_IN_MESSAGE) + " 외 " + (codes.size() - MAX_CODES_IN_MESSAGE) + "개";
    }

    // ---- 응답 ----

    private Map<ObservationAnalysisStatus, Long> countOf(List<ObservationAnalysis> analyses) {
        return analyses.stream().collect(Collectors.groupingBy(ObservationAnalysis::status, Collectors.counting()));
    }

    private VisionResultResponse response(ScanSession session, int shelfCount,
                                          Map<ObservationAnalysisStatus, Long> counts) {
        long normal = counts.getOrDefault(ObservationAnalysisStatus.NORMAL, 0L);
        long misplaced = counts.getOrDefault(ObservationAnalysisStatus.MISPLACED, 0L);
        long reviewRequired = counts.getOrDefault(ObservationAnalysisStatus.REVIEW_REQUIRED, 0L);
        return new VisionResultResponse(session.getId(), session.getStatus(), shelfCount,
                normal + misplaced + reviewRequired, normal, misplaced, reviewRequired);
    }
}
