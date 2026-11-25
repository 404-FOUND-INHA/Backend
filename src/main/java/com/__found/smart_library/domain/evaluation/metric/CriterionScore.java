package com.__found.smart_library.domain.evaluation.metric;

import com.__found.smart_library.domain.evaluation.entity.CriterionType;
import java.math.BigDecimal;

/**
 * 평가축 하나의 Backend 계산 결과. evaluation_criterion_result(evaluator_type = BACKEND)의
 * raw_score / normalized_score 로 그대로 저장할 수 있는 형태이다.
 *
 * @param rawScore        측정값(수요성: 기간 내 대출 건수, 물리적 상태: 가장 나쁜 기록의 점수, 중복성: 복본 수)
 * @param normalizedScore 0~100, 소수 둘째 자리. 높을수록 폐기 검토 필요도가 높다.
 * @param basis           점수의 근거를 설명하는 짧은 문장(선정 근거 생성에 쓴다)
 */
public record CriterionScore(CriterionType criterionType, BigDecimal rawScore, BigDecimal normalizedScore,
                             String basis) {
}
