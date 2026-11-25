package com.__found.smart_library.domain.evaluation.query;

import com.__found.smart_library.global.response.PageResponse;

/** 평가 결과 목록과 회차 전체 현황. summary 는 필터와 상관없이 회차 전체를 센다. */
public record EvaluationListResponse(Summary summary, PageResponse<EvaluationItem> page) {

    /**
     * @param total          종합 평가가 저장된 도서 수
     * @param disposalReview 폐기 검토 후보
     * @param keep           보존
     * @param notEvaluable   점수가 없는 평가축이 있어 판정하지 못함
     */
    public record Summary(long total, long disposalReview, long keep, long notEvaluable) {
    }
}
