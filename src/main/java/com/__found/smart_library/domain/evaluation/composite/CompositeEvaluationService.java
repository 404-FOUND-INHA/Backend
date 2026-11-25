package com.__found.smart_library.domain.evaluation.composite;

import com.__found.smart_library.domain.audit.entity.AuditLog;
import com.__found.smart_library.domain.audit.repository.AuditLogRepository;
import com.__found.smart_library.domain.evaluation.composite.CompositeScoreCalculator.AxisScore;
import com.__found.smart_library.domain.evaluation.composite.CompositeScoreCalculator.Outcome;
import com.__found.smart_library.domain.evaluation.dto.CriterionWeight;
import com.__found.smart_library.domain.evaluation.dto.EvaluationSettingsResponse;
import com.__found.smart_library.domain.evaluation.entity.BookEvaluation;
import com.__found.smart_library.domain.evaluation.entity.CandidateStatus;
import com.__found.smart_library.domain.evaluation.entity.CriterionType;
import com.__found.smart_library.domain.evaluation.entity.EvaluationCriterionResult;
import com.__found.smart_library.domain.evaluation.entity.EvaluationType;
import com.__found.smart_library.domain.evaluation.metric.BackendMetricService;
import com.__found.smart_library.domain.evaluation.metric.CriterionScore;
import com.__found.smart_library.domain.evaluation.repository.BookEvaluationRepository;
import com.__found.smart_library.domain.evaluation.repository.EvaluationCriterionResultRepository;
import com.__found.smart_library.domain.evaluation.service.EvaluationSettingsService;
import com.__found.smart_library.domain.evaluation.service.SessionBookResolver;
import com.__found.smart_library.domain.scan.entity.ScanSession;
import com.__found.smart_library.domain.scan.entity.ScanSessionStatus;
import com.__found.smart_library.domain.scan.repository.ScanSessionRepository;
import com.__found.smart_library.global.exception.BusinessException;
import com.__found.smart_library.global.exception.ErrorCode;
import com.__found.smart_library.global.lock.PostgresAdvisoryLock;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 점검이 확정된 회차의 도서마다 JEV 결과(희소성·내용 유효성)와 Backend 지표(수요성·중복성·물리적 상태)를 지금 활성인
 * 가중치로 합쳐 종합 평가(evaluation_type = BACKEND)를 저장한다. JEV 원본은 읽기만 하고 수정하지 않는다.
 *
 * <p>같은 회차·도서의 종합 평가는 현재 결과 1건만 두고 다시 실행하면 그 행을 갱신한다(가중치를 바꾸거나 JEV 결과가 뒤늦게
 * 들어온 경우). 결과가 이전과 같으면 아무것도 바꾸지 않고, 바뀌면 이전·이후 값이 감사 로그에 남는다. 계산에 쓴 평가 설정 버전은
 * 평가의 model_version 에 기록한다. 같은 회차의 실행은 Advisory Lock 으로 직렬화한다.
 *
 * <p>시스템은 후보를 제안만 하며 최종 결정은 사서가 한다.
 */
@Service
@RequiredArgsConstructor
public class CompositeEvaluationService {

    /** 회차별 종합 평가 실행 직렬화용 Advisory Lock 네임스페이스. 다른 기능의 락 키와 겹치지 않게 고정값으로 쓴다. */
    public static final int COMPOSITE_LOCK_NAMESPACE = 40404;

    private static final List<CriterionType> JEV_AXES = List.of(CriterionType.RARITY, CriterionType.CONTENT_VALIDITY);

    private final ScanSessionRepository scanSessionRepository;
    private final SessionBookResolver sessionBookResolver;
    private final EvaluationSettingsService settingsService;
    private final BackendMetricService metricService;
    private final BookEvaluationRepository evaluationRepository;
    private final EvaluationCriterionResultRepository criterionResultRepository;
    private final AuditLogRepository auditLogRepository;
    private final PostgresAdvisoryLock advisoryLock;
    private final CompositeScoreCalculator calculator;
    private final Clock clock;

    @Transactional
    public CompositeEvaluationResponse evaluateSession(Long sessionId, Long actorId) {
        ScanSession session = scanSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCAN_SESSION_NOT_FOUND));
        if (session.getStatus() != ScanSessionStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.EVALUATION_SESSION_NOT_COMPLETED);
        }
        advisoryLock.lock(COMPOSITE_LOCK_NAMESPACE, String.valueOf(sessionId));

        EvaluationSettingsResponse settings = settingsService.getActive();
        String profileVersion = settings.profileVersion() == null
                ? BookEvaluation.DEFAULT_PROFILE_VERSION : settings.profileVersion();
        Map<CriterionType, BigDecimal> weights = new EnumMap<>(CriterionType.class);
        for (CriterionWeight w : settings.weights()) {
            weights.put(w.criterion(), w.weight());
        }

        Set<Long> bookIds = sessionBookResolver.findConfirmedBookIds(sessionId);
        if (bookIds.isEmpty()) {
            return new CompositeEvaluationResponse(0, 0, 0, 0, 0, 0, 0, profileVersion,
                    settings.disposalThreshold(), List.of());
        }

        Map<Long, List<CriterionScore>> backendScores = metricService.calculate(bookIds);
        Map<Long, Map<CriterionType, AxisScore>> jevScores = loadJevScores(sessionId, bookIds);
        Map<Long, BookEvaluation> existing = evaluationRepository
                .findByScanSessionIdAndEvaluationTypeAndBookIdIn(sessionId, EvaluationType.BACKEND, bookIds).stream()
                .collect(Collectors.toMap(BookEvaluation::getBookId, Function.identity()));

        LocalDateTime now = LocalDateTime.now(clock);
        Tally tally = new Tally();
        List<BookEvaluation> toReplaceRows = new ArrayList<>();
        Map<BookEvaluation, List<EvaluationCriterionResult>> newRows = new LinkedHashMap<>();
        for (Long bookId : bookIds) {
            List<CriterionScore> backend = backendScores.getOrDefault(bookId, List.of());
            Map<CriterionType, AxisScore> scores = new EnumMap<>(CriterionType.class);
            backend.forEach(s -> scores.put(s.criterionType(), new AxisScore(s.normalizedScore(), s.basis())));
            scores.putAll(jevScores.getOrDefault(bookId, Map.of()));

            Outcome outcome = calculator.calculate(scores, weights, settings.disposalThreshold(), profileVersion);
            tally.count(bookId, outcome.status());

            BookEvaluation evaluation = existing.get(bookId);
            if (evaluation == null) {
                evaluation = evaluationRepository.save(BookEvaluation.forBackend(bookId, sessionId, profileVersion,
                        outcome.finalScore(), outcome.status(), outcome.reason(), now));
                newRows.put(evaluation, backendRows(evaluation, profileVersion, backend));
                tally.created++;
                record(actorId, evaluation.getId(), AuditLog.ACTION_CREATE, null,
                        snapshot(profileVersion, outcome.finalScore(), outcome.status()));
            } else if (isSame(evaluation, profileVersion, outcome)) {
                tally.unchanged++;
            } else {
                Map<String, Object> before = snapshot(evaluation.getModelVersion(), evaluation.getFinalScore(),
                        evaluation.getCandidateStatus());
                evaluation.recalculate(profileVersion, outcome.finalScore(), outcome.status(), outcome.reason(), now);
                toReplaceRows.add(evaluation);
                newRows.put(evaluation, backendRows(evaluation, profileVersion, backend));
                tally.updated++;
                recordChanges(actorId, evaluation.getId(), before,
                        snapshot(profileVersion, outcome.finalScore(), outcome.status()));
            }
        }

        if (!toReplaceRows.isEmpty()) {
            criterionResultRepository.deleteByEvaluationIdIn(toReplaceRows.stream().map(BookEvaluation::getId).toList());
        }
        newRows.values().forEach(criterionResultRepository::saveAll);

        return new CompositeEvaluationResponse(bookIds.size(), tally.disposalReview, tally.keep,
                tally.notEvaluable.size(), tally.created, tally.updated, tally.unchanged, profileVersion,
                settings.disposalThreshold(), tally.notEvaluable);
    }

    /** 도서별 JEV 평가축 점수. 이 회차에 JEV 결과가 없거나 한 축이 비어 있으면 그 축은 점수 없음이다. */
    private Map<Long, Map<CriterionType, AxisScore>> loadJevScores(Long sessionId, Set<Long> bookIds) {
        List<BookEvaluation> jevEvaluations = evaluationRepository
                .findByScanSessionIdAndEvaluationTypeAndBookIdIn(sessionId, EvaluationType.JEV, bookIds);
        if (jevEvaluations.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> bookByEvaluation = jevEvaluations.stream()
                .collect(Collectors.toMap(BookEvaluation::getId, BookEvaluation::getBookId));
        Map<Long, Map<CriterionType, AxisScore>> result = new HashMap<>();
        for (EvaluationCriterionResult row : criterionResultRepository.findByEvaluationIdIn(bookByEvaluation.keySet())) {
            if (!JEV_AXES.contains(row.getCriterionType()) || row.getNormalizedScore() == null) {
                continue;
            }
            result.computeIfAbsent(bookByEvaluation.get(row.getEvaluationId()),
                    k -> new EnumMap<>(CriterionType.class))
                    .put(row.getCriterionType(), new AxisScore(row.getNormalizedScore(), jevBasis(row)));
        }
        return result;
    }

    private static String jevBasis(EvaluationCriterionResult row) {
        StringBuilder sb = new StringBuilder("JEV");
        if (row.getSelectedGrade() != null) {
            sb.append(" 단계 ").append(row.getSelectedGrade());
        }
        if (row.getConfidence() != null) {
            sb.append(", 신뢰도 ").append(row.getConfidence().toPlainString());
        }
        return sb.toString();
    }

    private static List<EvaluationCriterionResult> backendRows(BookEvaluation evaluation, String profileVersion,
                                                              List<CriterionScore> backend) {
        return backend.stream()
                .map(s -> EvaluationCriterionResult.ofBackend(evaluation.getId(), s.criterionType(), profileVersion,
                        s.rawScore(), s.normalizedScore()))
                .toList();
    }

    private static boolean isSame(BookEvaluation evaluation, String profileVersion, Outcome outcome) {
        return Objects.equals(evaluation.getModelVersion(), profileVersion)
                && evaluation.getCandidateStatus() == outcome.status()
                && Objects.equals(evaluation.getEvaluationReason(), outcome.reason())
                && sameScore(evaluation.getFinalScore(), outcome.finalScore());
    }

    private static boolean sameScore(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static Map<String, Object> snapshot(String profileVersion, BigDecimal finalScore, CandidateStatus status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("profileVersion", profileVersion);
        map.put("finalScore", finalScore);
        map.put("candidateStatus", status.name());
        return map;
    }

    /** 바뀐 필드만 이전·이후 값으로 남긴다. */
    private void recordChanges(Long actorId, Long evaluationId, Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changedBefore = new LinkedHashMap<>();
        Map<String, Object> changedAfter = new LinkedHashMap<>();
        for (String key : after.keySet()) {
            Object b = before.get(key);
            Object a = after.get(key);
            boolean same = b instanceof BigDecimal bb && a instanceof BigDecimal aa ? bb.compareTo(aa) == 0
                    : Objects.equals(b, a);
            if (!same) {
                changedBefore.put(key, b);
                changedAfter.put(key, a);
            }
        }
        if (!changedAfter.isEmpty()) {
            record(actorId, evaluationId, AuditLog.ACTION_UPDATE, changedBefore, changedAfter);
        }
    }

    private void record(Long actorId, Long evaluationId, String action, Map<String, Object> before,
                        Map<String, Object> after) {
        auditLogRepository.save(AuditLog.builder()
                .actorId(actorId)
                .entityType(AuditLog.ENTITY_BOOK_EVALUATION)
                .entityId(evaluationId)
                .action(action)
                .beforeData(before)
                .afterData(after)
                .build());
    }

    private static final class Tally {
        int disposalReview;
        int keep;
        int created;
        int updated;
        int unchanged;
        final List<Long> notEvaluable = new ArrayList<>();

        void count(Long bookId, CandidateStatus status) {
            switch (status) {
                case DISPOSAL_REVIEW -> disposalReview++;
                case KEEP -> keep++;
                default -> notEvaluable.add(bookId);
            }
        }
    }
}
