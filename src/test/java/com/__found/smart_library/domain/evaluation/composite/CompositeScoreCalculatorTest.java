package com.__found.smart_library.domain.evaluation.composite;

import static org.assertj.core.api.Assertions.assertThat;

import com.__found.smart_library.domain.evaluation.composite.CompositeScoreCalculator.AxisScore;
import com.__found.smart_library.domain.evaluation.composite.CompositeScoreCalculator.Outcome;
import com.__found.smart_library.domain.evaluation.entity.CandidateStatus;
import com.__found.smart_library.domain.evaluation.entity.CriterionType;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CompositeScoreCalculatorTest {

    private static final BigDecimal FIFTY = new BigDecimal("50.00");

    private final CompositeScoreCalculator calculator = new CompositeScoreCalculator();

    private static Map<CriterionType, BigDecimal> equalWeights() {
        Map<CriterionType, BigDecimal> weights = new EnumMap<>(CriterionType.class);
        for (CriterionType type : CriterionType.values()) {
            weights.put(type, new BigDecimal("0.2000"));
        }
        return weights;
    }

    private static Map<CriterionType, AxisScore> scores(String demand, String rarity, String duplication,
                                                        String content, String physical) {
        Map<CriterionType, AxisScore> map = new EnumMap<>(CriterionType.class);
        put(map, CriterionType.DEMAND, demand);
        put(map, CriterionType.RARITY, rarity);
        put(map, CriterionType.DUPLICATION, duplication);
        put(map, CriterionType.CONTENT_VALIDITY, content);
        put(map, CriterionType.PHYSICAL_CONDITION, physical);
        return map;
    }

    private static void put(Map<CriterionType, AxisScore> map, CriterionType type, String score) {
        if (score != null) {
            map.put(type, new AxisScore(new BigDecimal(score), type.name() + " 근거"));
        }
    }

    private Outcome run(Map<CriterionType, AxisScore> scores, Map<CriterionType, BigDecimal> weights) {
        return calculator.calculate(scores, weights, FIFTY, "v3");
    }

    @Test
    @DisplayName("종합 점수는 점수 × 가중치의 합이다")
    void weightedSum() {
        Map<CriterionType, BigDecimal> weights = new EnumMap<>(CriterionType.class);
        weights.put(CriterionType.DEMAND, new BigDecimal("0.4000"));
        weights.put(CriterionType.RARITY, new BigDecimal("0.3000"));
        weights.put(CriterionType.DUPLICATION, new BigDecimal("0.1000"));
        weights.put(CriterionType.CONTENT_VALIDITY, new BigDecimal("0.1000"));
        weights.put(CriterionType.PHYSICAL_CONDITION, new BigDecimal("0.1000"));

        Outcome outcome = run(scores("80", "60", "20", "40", "10"), weights);

        // 32 + 18 + 2 + 4 + 1
        assertThat(outcome.finalScore()).isEqualByComparingTo("57.00");
        assertThat(outcome.status()).isEqualTo(CandidateStatus.DISPOSAL_REVIEW);
    }

    @Test
    @DisplayName("기준 점수와 같으면 후보이고, 0.01 이라도 낮으면 보존이다")
    void thresholdBoundary() {
        assertThat(run(scores("100", "100", "0", "50", "0"), equalWeights()).status())
                .isEqualTo(CandidateStatus.DISPOSAL_REVIEW);
        Outcome below = run(scores("100", "100", "0", "49.95", "0"), equalWeights());
        assertThat(below.finalScore()).isEqualByComparingTo("49.99");
        assertThat(below.status()).isEqualTo(CandidateStatus.KEEP);
    }

    @Test
    @DisplayName("반올림한 점수로 판정한다: 49.995 는 50.00 이 되어 후보이다")
    void decidesOnRoundedScore() {
        Map<CriterionType, BigDecimal> weights = new EnumMap<>(CriterionType.class);
        for (CriterionType type : CriterionType.values()) {
            weights.put(type, BigDecimal.ZERO);
        }
        weights.put(CriterionType.DEMAND, new BigDecimal("0.5000"));
        weights.put(CriterionType.RARITY, new BigDecimal("0.5000"));

        Outcome outcome = run(scores("99.99", "0", "0", "0", "0"), weights);

        assertThat(outcome.finalScore()).isEqualByComparingTo("50.00");
        assertThat(outcome.status()).isEqualTo(CandidateStatus.DISPOSAL_REVIEW);
    }

    @Test
    @DisplayName("가중치가 있는 평가축의 점수가 없으면 점수를 채우지 않고 평가 불가로 두며 종합 점수는 비운다")
    void missingWeightedAxisIsNotEvaluable() {
        Outcome outcome = run(scores("100", null, "100", null, "100"), equalWeights());

        assertThat(outcome.status()).isEqualTo(CandidateStatus.NOT_EVALUABLE);
        assertThat(outcome.finalScore()).isNull();
        assertThat(outcome.reason()).contains("없는 평가축: 희소성, 내용 유효성").contains("평가 설정 v3")
                .contains("- 희소성 점수 없음").contains("- 수요성 100.00점 × 0.2000 = 20.00점");
    }

    @Test
    @DisplayName("가중치가 0 인 평가축은 점수가 없어도 평가할 수 있고 합계에도 반영하지 않는다")
    void zeroWeightAxisIsOptional() {
        Map<CriterionType, BigDecimal> weights = new EnumMap<>(CriterionType.class);
        weights.put(CriterionType.DEMAND, new BigDecimal("0.5000"));
        weights.put(CriterionType.DUPLICATION, new BigDecimal("0.2500"));
        weights.put(CriterionType.PHYSICAL_CONDITION, new BigDecimal("0.2500"));
        weights.put(CriterionType.RARITY, BigDecimal.ZERO);
        weights.put(CriterionType.CONTENT_VALIDITY, BigDecimal.ZERO);

        Outcome withoutJev = run(scores("100", null, "0", null, "0"), weights);
        Outcome withJev = run(scores("100", "100", "0", "100", "0"), weights);

        assertThat(withoutJev.status()).isEqualTo(CandidateStatus.DISPOSAL_REVIEW);
        assertThat(withoutJev.finalScore()).isEqualByComparingTo("50.00");
        assertThat(withoutJev.reason()).contains("- 희소성 점수 없음, 가중치 0이라 제외");
        assertThat(withJev.finalScore()).isEqualByComparingTo("50.00");
        assertThat(withJev.reason()).contains("- 희소성 100.00점 × 0.0000 = 0.00점 (가중치 0이라 반영하지 않음)");
    }

    @Test
    @DisplayName("선정 근거에는 판정, 설정 버전, 기여도가 가장 큰 평가축, 축별 점수·가중치·기여도·근거가 평가축 순서로 들어간다")
    void reasonContent() {
        Outcome candidate = run(scores("100", "80", "0", "50", "0"), equalWeights());
        Outcome keep = run(scores("10", "10", "0", "10", "0"), equalWeights());

        assertThat(candidate.reason()).startsWith(
                        "종합 점수 46.00점으로 기준 점수 50.00점 미만이라 보존합니다. (평가 설정 v3)")
                .contains("기여도가 가장 큰 평가축: 수요성 20.00점")
                .contains("- 희소성 80.00점 × 0.2000 = 16.00점 · RARITY 근거");
        assertThat(candidate.reason().indexOf("- 수요성")).isLessThan(candidate.reason().indexOf("- 희소성"));
        assertThat(candidate.reason().indexOf("- 희소성")).isLessThan(candidate.reason().indexOf("- 중복성"));
        assertThat(candidate.reason().indexOf("- 중복성")).isLessThan(candidate.reason().indexOf("- 내용 유효성"));
        assertThat(candidate.reason().indexOf("- 내용 유효성")).isLessThan(candidate.reason().indexOf("- 물리적 상태"));
        assertThat(keep.status()).isEqualTo(CandidateStatus.KEEP);

        Outcome review = calculator.calculate(scores("100", "100", "100", "100", "100"), equalWeights(),
                new BigDecimal("90.00"), "default");
        assertThat(review.reason()).startsWith(
                "종합 점수 100.00점으로 기준 점수 90.00점 이상이라 폐기 검토 후보입니다. (평가 설정 default)");
    }

    @Test
    @DisplayName("기여도가 같으면 평가축 순서가 앞선 축을 가장 큰 축으로 본다")
    void topContributorTieBreak() {
        Outcome outcome = run(scores("50", "50", "50", "50", "50"), equalWeights());

        assertThat(outcome.reason()).contains("기여도가 가장 큰 평가축: 수요성 10.00점");
    }

    @Test
    @DisplayName("모든 점수가 0 이면 기여도가 있는 축이 없어 해당 줄을 쓰지 않는다")
    void allZero() {
        Outcome outcome = run(scores("0", "0", "0", "0", "0"), equalWeights());

        assertThat(outcome.finalScore()).isEqualByComparingTo("0.00");
        assertThat(outcome.status()).isEqualTo(CandidateStatus.KEEP);
        assertThat(outcome.reason()).doesNotContain("기여도가 가장 큰 평가축");
    }
}
