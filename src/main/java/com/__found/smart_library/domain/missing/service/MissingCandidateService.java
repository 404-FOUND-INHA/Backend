package com.__found.smart_library.domain.missing.service;

import com.__found.smart_library.domain.audit.entity.AuditLog;
import com.__found.smart_library.domain.audit.repository.AuditLogRepository;
import com.__found.smart_library.domain.missing.dto.MissingCandidateResponse;
import com.__found.smart_library.domain.missing.dto.MissingCandidateReviewRequest;
import com.__found.smart_library.domain.missing.entity.MissingCandidate;
import com.__found.smart_library.domain.missing.entity.MissingCandidateStatus;
import com.__found.smart_library.domain.missing.repository.CandidateItemRow;
import com.__found.smart_library.domain.missing.repository.MissingCandidateRepository;
import com.__found.smart_library.domain.scan.repository.ScanSessionRepository;
import com.__found.smart_library.global.exception.BusinessException;
import com.__found.smart_library.global.exception.ErrorCode;
import com.__found.smart_library.global.response.PageResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 소재불명 후보 조회와 현장 확인 결과 기록. 후보 생성은 회차 확정 시 {@link MissingCandidateGenerator} 가 한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MissingCandidateService {

    private static final int MAX_PAGE_SIZE = 100;

    private final MissingCandidateRepository candidateRepository;
    private final ScanSessionRepository scanSessionRepository;
    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    /** 회차의 소재불명 후보 목록. 연속 미발견 횟수가 많은 순. status 가 null 이면 전체. */
    public PageResponse<MissingCandidateResponse> list(Long sessionId, MissingCandidateStatus status,
                                                       int page, int size) {
        if (!scanSessionRepository.existsById(sessionId)) {
            throw new BusinessException(ErrorCode.SCAN_SESSION_NOT_FOUND);
        }
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        if ((long) safePage * safeSize > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "page 값이 너무 큽니다.");
        }
        Page<CandidateItemRow> rows = candidateRepository.findItems(
                sessionId, status, PageRequest.of(safePage, safeSize));
        return PageResponse.from(rows.map(MissingCandidateResponse::from));
    }

    /** 현장 확인 결과를 기록한다. 다시 기록하면 덮어쓰며, 상태나 메모가 바뀌었을 때만 이력을 남긴다. */
    @Transactional
    public MissingCandidateResponse record(Long candidateId, MissingCandidateReviewRequest request, Long userId) {
        if (request.status() == MissingCandidateStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "확인 결과로 PENDING 을 기록할 수 없습니다.");
        }
        MissingCandidate candidate = candidateRepository.findForUpdate(candidateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MISSING_CANDIDATE_NOT_FOUND));
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();

        MissingCandidateStatus beforeStatus = candidate.getStatus();
        String beforeNote = candidate.getNote();
        candidate.record(request.status(), note, userId, LocalDateTime.now(clock));

        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        if (beforeStatus != request.status()) {
            before.put("status", beforeStatus.name());
            after.put("status", request.status().name());
        }
        if (!Objects.equals(beforeNote, note)) {
            before.put("note", beforeNote);
            after.put("note", note);
        }
        if (!after.isEmpty()) {
            auditLogRepository.save(AuditLog.builder()
                    .actorId(userId)
                    .entityType(AuditLog.ENTITY_MISSING_CANDIDATE)
                    .entityId(candidate.getId())
                    .action(beforeStatus != request.status()
                            ? AuditLog.ACTION_STATUS_CHANGE : AuditLog.ACTION_UPDATE)
                    .beforeData(before)
                    .afterData(after)
                    .build());
        }
        return candidateRepository.findItem(candidate.getId())
                .map(MissingCandidateResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.MISSING_CANDIDATE_NOT_FOUND));
    }
}
