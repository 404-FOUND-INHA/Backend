package com.__found.smart_library.domain.evaluation.composite;

import java.math.BigDecimal;
import java.util.List;

/**
 * 회차 종합 평가 실행 결과.
 *
 * @param total               평가 대상(점검에서 확정된) 도서 수
 * @param disposalReview      폐기 검토 후보로 판정된 도서 수
 * @param keep                보존으로 판정된 도서 수
 * @param notEvaluable        점수가 없는 평가축이 있어 판정하지 못한 도서 수
 * @param created             이번 실행으로 새로 저장한 평가 수
 * @param updated             이전 결과와 달라져 갱신한 평가 수
 * @param unchanged           이전 결과와 같아 그대로 둔 평가 수
 * @param profileVersion      계산에 쓴 평가 설정 버전(저장된 설정이 없으면 "default")
 * @param disposalThreshold   계산에 쓴 기준 점수
 * @param notEvaluableBookIds 판정하지 못한 도서(JEV 평가 등을 먼저 실행해야 한다)
 */
public record CompositeEvaluationResponse(int total, int disposalReview, int keep, int notEvaluable, int created,
                                          int updated, int unchanged, String profileVersion,
                                          BigDecimal disposalThreshold, List<Long> notEvaluableBookIds) {
}
