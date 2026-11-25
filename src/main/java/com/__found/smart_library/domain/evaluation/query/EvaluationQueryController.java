package com.__found.smart_library.domain.evaluation.query;

import com.__found.smart_library.domain.evaluation.entity.CandidateStatus;
import com.__found.smart_library.global.response.ApiResponse;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 도서 평가 결과·폐기 검토 후보 조회. 로그인한 사용자라면 누구나 볼 수 있다. 시스템은 후보를 제안만 한다. */
@RestController
@RequestMapping("/api/scan-sessions/{sessionId}")
@RequiredArgsConstructor
public class EvaluationQueryController {

    private final EvaluationQueryService evaluationQueryService;

    /**
     * 회차의 종합 평가 목록과 현황. status(KEEP·DISPOSAL_REVIEW·NOT_EVALUABLE), minScore·maxScore(0~100)로 거르고
     * 점수가 높은 순이다. page 는 0부터이고 size 는 최대 100 이다.
     */
    @GetMapping("/evaluations")
    public ApiResponse<EvaluationListResponse> list(
            @PathVariable Long sessionId,
            @RequestParam(required = false) CandidateStatus status,
            @RequestParam(required = false) BigDecimal minScore,
            @RequestParam(required = false) BigDecimal maxScore,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(evaluationQueryService.list(sessionId, status, minScore, maxScore, page, size));
    }

    /** 회차의 폐기 검토 후보 목록(점수 높은 순, 서가 위치 포함). */
    @GetMapping("/disposal-candidates")
    public ApiResponse<EvaluationListResponse> disposalCandidates(
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(evaluationQueryService.disposalCandidates(sessionId, page, size));
    }

    /** 서가별 폐기 검토 후보(서가·3D 시각화용). shelfId 를 주면 그 서가만. */
    @GetMapping("/disposal-candidates/by-shelf")
    public ApiResponse<ShelfCandidatesResponse> candidatesByShelf(
            @PathVariable Long sessionId, @RequestParam(required = false) Long shelfId) {
        return ApiResponse.ok(evaluationQueryService.candidatesByShelf(sessionId, shelfId));
    }

    /** 도서 평가 상세: 종합 결과·선정 근거와 평가축별 결과(JEV 원본과 Backend 계산 구분). */
    @GetMapping("/books/{bookId}/evaluation")
    public ApiResponse<EvaluationDetailResponse> detail(@PathVariable Long sessionId, @PathVariable Long bookId) {
        return ApiResponse.ok(evaluationQueryService.detail(sessionId, bookId));
    }
}
