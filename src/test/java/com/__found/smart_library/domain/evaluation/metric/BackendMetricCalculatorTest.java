package com.__found.smart_library.domain.evaluation.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.__found.smart_library.domain.condition.entity.ConditionSeverity;
import com.__found.smart_library.domain.condition.entity.ConditionType;
import com.__found.smart_library.domain.evaluation.entity.CriterionType;
import com.__found.smart_library.domain.evaluation.metric.BackendMetricCalculator.ConditionInput;
import com.__found.smart_library.domain.evaluation.metric.BackendMetricCalculator.DemandInput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 데이터베이스 없이 점수 규칙(경계값, 결측, 복수 기록, 설정 변경)만 검증한다. */
class BackendMetricCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 1);

    private final BackendMetricProperties properties = new BackendMetricProperties();
    private final BackendMetricCalculator calculator = new BackendMetricCalculator(properties);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Nested
    @DisplayName("수요성")
    class Demand {

        @Test
        @DisplayName("대출 이력이 전혀 없으면 100 이다")
        void neverBorrowed_is100() {
            CriterionScore s = calculator.demand(new DemandInput(0, null, 0), TODAY);

            assertThat(s.criterionType()).isEqualTo(CriterionType.DEMAND);
            assertThat(s.normalizedScore()).isEqualByComparingTo("100.00");
            assertThat(s.rawScore()).isEqualByComparingTo("0.00");
            assertThat(s.basis()).contains("대출 이력이 없음");
        }

        @Test
        @DisplayName("raw 는 최근 대출 건수이고 DECIMAL(5,2) 를 넘지 않는다(근거 문장에는 실제 건수가 남는다)")
        void rawIsCappedAtColumnMax() {
            assertThat(calculator.demand(new DemandInput(999, TODAY, 999), TODAY).rawScore())
                    .isEqualByComparingTo("999.00");
            CriterionScore thousand = calculator.demand(new DemandInput(1000, TODAY, 1000), TODAY);
            assertThat(thousand.rawScore()).isEqualByComparingTo("999.99");
            assertThat(thousand.normalizedScore()).isEqualByComparingTo("0.00");
            assertThat(thousand.basis()).contains("대출 1000건");
            assertThat(calculator.demand(new DemandInput(Integer.MAX_VALUE, TODAY, Integer.MAX_VALUE), TODAY)
                    .rawScore()).isEqualByComparingTo("999.99");
        }

        @Test
        @DisplayName("최근 대출이 포화 건수 이상이고 오늘 빌려갔다면 0 이다")
        void saturatedAndRecent_is0() {
            assertThat(calculator.demand(new DemandInput(5, TODAY, 50), TODAY).normalizedScore())
                    .isEqualByComparingTo("0.00");
            assertThat(calculator.demand(new DemandInput(99, TODAY, 99), TODAY).normalizedScore())
                    .isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("포화 건수 바로 아래(4건)는 대출 건수 점수가 20 이다")
        void justBelowSaturation() {
            // 20 * 0.6 + 0 * 0.4
            assertThat(calculator.demand(new DemandInput(4, TODAY, 4), TODAY).normalizedScore())
                    .isEqualByComparingTo("12.00");
        }

        @Test
        @DisplayName("마지막 대출이 기간(365일 × 3년 = 1095일) 이상 지났으면 경과 점수가 100 이고, 하루 모자라면 100 미만이다")
        void recencyBoundary() {
            // 대출 건수 점수는 최근 5건으로 0 이므로 경과 점수 * 0.4 만 남는다
            assertThat(calculator.demand(new DemandInput(5, TODAY.minusDays(1095), 5), TODAY).normalizedScore())
                    .isEqualByComparingTo("40.00");
            assertThat(calculator.demand(new DemandInput(5, TODAY.minusYears(10), 5), TODAY).normalizedScore())
                    .isEqualByComparingTo("40.00");
            assertThat(calculator.demand(new DemandInput(5, TODAY.minusDays(1094), 5), TODAY)
                    .normalizedScore()).isEqualByComparingTo("39.96");   // 1094/1095*100*0.4
        }

        @Test
        @DisplayName("대출 2건 + 마지막 대출 365일 전: 60*0.6 + 33.33*0.4")
        void blended() {
            CriterionScore s = calculator.demand(new DemandInput(2, TODAY.minusDays(365), 8), TODAY);

            assertThat(s.normalizedScore()).isEqualByComparingTo("49.33");
            assertThat(s.rawScore()).isEqualByComparingTo("2.00");
            assertThat(s.basis()).contains("최근 3년 대출 2건").contains("누적 8건");
        }

        @Test
        @DisplayName("기간 내 대출이 없고 마지막 대출이 오래됐으면 100 이다")
        void noRecentOldLast_is100() {
            assertThat(calculator.demand(new DemandInput(0, TODAY.minusYears(5), 12), TODAY).normalizedScore())
                    .isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("마지막 대출일이 없는데 누적 대출이 있으면(데이터 불일치) 경과 점수를 100 으로 본다")
        void missingLastDate_butHasTotal() {
            assertThat(calculator.demand(new DemandInput(0, null, 3), TODAY).normalizedScore())
                    .isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("마지막 대출일이 미래이면 경과 0 으로 처리한다")
        void futureLastDate_treatedAsToday() {
            assertThat(calculator.demand(new DemandInput(5, TODAY.plusDays(10), 5), TODAY).normalizedScore())
                    .isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("설정을 바꾸면 계산에 반영된다 (기간 1년, 포화 2건, 비중 0.5/0.5)")
        void customRules() {
            properties.getDemand().setWindowYears(1);
            properties.getDemand().setLoanSaturation(2);
            properties.getDemand().setRecentLoanWeight(d("0.5"));
            properties.getDemand().setRecencyWeight(d("0.5"));

            // 대출 1건 -> 50, 마지막 대출 반년(182.5일 -> 182일)이면 182/365*100 = 49.86
            CriterionScore s = calculator.demand(new DemandInput(1, TODAY.minusDays(182), 1), TODAY);

            assertThat(s.normalizedScore()).isEqualByComparingTo("49.93");   // 50*0.5 + 49.863*0.5
        }
    }

    @Nested
    @DisplayName("물리적 상태")
    class Physical {

        @Test
        @DisplayName("오손 기록이 없으면 양호(0)이다")
        void noReports_is0() {
            CriterionScore s = calculator.physicalCondition(List.of());

            assertThat(s.criterionType()).isEqualTo(CriterionType.PHYSICAL_CONDITION);
            assertThat(s.normalizedScore()).isEqualByComparingTo("0.00");
            assertThat(s.basis()).contains("오손 기록 없음");
        }

        @Test
        @DisplayName("기록 1건은 심각도 점수 × 유형 계수이다")
        void singleReport() {
            assertThat(score(List.of(new ConditionInput(ConditionType.STAIN, ConditionSeverity.LOW))))
                    .isEqualByComparingTo("18.00");
            assertThat(score(List.of(new ConditionInput(ConditionType.TORN, ConditionSeverity.MEDIUM))))
                    .isEqualByComparingTo("48.00");
            assertThat(score(List.of(new ConditionInput(ConditionType.WATER_DAMAGE, ConditionSeverity.HIGH))))
                    .isEqualByComparingTo("90.00");
            assertThat(score(List.of(new ConditionInput(ConditionType.BINDING_DAMAGE, ConditionSeverity.HIGH))))
                    .isEqualByComparingTo("90.00");
        }

        @Test
        @DisplayName("심각도가 비어 있으면 기본 심각도(MEDIUM)로 계산한다")
        void nullSeverity_usesDefault() {
            assertThat(score(List.of(new ConditionInput(ConditionType.TORN, null)))).isEqualByComparingTo("48.00");

            properties.getCondition().setDefaultSeverity(ConditionSeverity.HIGH);
            assertThat(score(List.of(new ConditionInput(ConditionType.TORN, null)))).isEqualByComparingTo("72.00");
        }

        @Test
        @DisplayName("여러 기록은 가장 나쁜 기록 + 추가 기록당 가산점이고 순서와 무관하다")
        void multipleReports_worstPlusBonus() {
            var low = new ConditionInput(ConditionType.STAIN, ConditionSeverity.LOW);
            var medium = new ConditionInput(ConditionType.TORN, ConditionSeverity.MEDIUM);

            CriterionScore a = calculator.physicalCondition(List.of(low, medium));
            CriterionScore b = calculator.physicalCondition(List.of(medium, low));

            assertThat(a.normalizedScore()).isEqualByComparingTo("53.00");   // 48 + 5
            assertThat(b.normalizedScore()).isEqualByComparingTo("53.00");
            assertThat(a.rawScore()).isEqualByComparingTo("48.00");
            assertThat(a.basis()).contains("2건").contains("TORN/MEDIUM");
        }

        @Test
        @DisplayName("점수는 100 을 넘지 않는다")
        void cappedAt100() {
            var worst = new ConditionInput(ConditionType.WATER_DAMAGE, ConditionSeverity.HIGH);

            assertThat(score(List.of(worst, worst, worst, worst))).isEqualByComparingTo("100.00");   // 90 + 15
            assertThat(score(List.of(worst, worst))).isEqualByComparingTo("95.00");
        }

        private BigDecimal score(List<ConditionInput> reports) {
            return calculator.physicalCondition(reports).normalizedScore();
        }
    }

    @Nested
    @DisplayName("중복성")
    class Duplication {

        @Test
        @DisplayName("1권(복본 없음)은 0, 포화 권수(4권) 이상은 100 이고 사이는 선형이다")
        void linear() {
            assertThat(score(1)).isEqualByComparingTo("0.00");
            assertThat(score(2)).isEqualByComparingTo("33.33");
            assertThat(score(3)).isEqualByComparingTo("66.67");
            assertThat(score(4)).isEqualByComparingTo("100.00");
            assertThat(score(10)).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("복본 수가 1 미만으로 들어와도 1권으로 본다")
        void belowOne_treatedAsOne() {
            assertThat(score(0)).isEqualByComparingTo("0.00");
            assertThat(score(-3)).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("raw 는 복본 수이고 DECIMAL(5,2) 를 넘지 않는다")
        void rawIsCopyCount() {
            assertThat(calculator.duplication(3).rawScore()).isEqualByComparingTo("3.00");
            assertThat(calculator.duplication(3).basis()).contains("3권");
            assertThat(calculator.duplication(Integer.MAX_VALUE).rawScore()).isEqualByComparingTo("999.99");
        }

        @Test
        @DisplayName("포화 권수 설정을 바꾸면 반영된다")
        void customSaturation() {
            properties.getDuplication().setSaturationCopies(2);

            assertThat(score(2)).isEqualByComparingTo("100.00");
        }

        private BigDecimal score(int copies) {
            return calculator.duplication(copies).normalizedScore();
        }
    }

    @Nested
    @DisplayName("설정 검증")
    class Validation {

        @Test
        @DisplayName("기본 설정은 유효하다")
        void defaultsAreValid() {
            new BackendMetricProperties().validate();
        }

        @Test
        @DisplayName("잘못된 설정은 기동 시점에 실패한다")
        void invalidSettings() {
            assertInvalid(p -> p.getDemand().setWindowYears(0), "window-years");
            assertInvalid(p -> p.getDemand().setLoanSaturation(0), "loan-saturation");
            assertInvalid(p -> p.getDemand().setRecencyWeight(d("0.5")), "합이 1");
            assertInvalid(p -> p.getDemand().setRecentLoanWeight(d("-0.1")), "0 이상");
            assertInvalid(p -> p.getCondition().getSeverityScore().remove(ConditionSeverity.HIGH), "HIGH");
            assertInvalid(p -> p.getCondition().getSeverityScore().put(ConditionSeverity.LOW, 101), "LOW");
            assertInvalid(p -> p.getCondition().getTypeFactor().put(ConditionType.STAIN, d("1.5")), "STAIN");
            assertInvalid(p -> p.getCondition().setExtraReportBonus(-1), "extra-report-bonus");
            assertInvalid(p -> p.getDuplication().setSaturationCopies(1), "saturation-copies");
        }

        private void assertInvalid(java.util.function.Consumer<BackendMetricProperties> change, String messagePart) {
            BackendMetricProperties p = new BackendMetricProperties();
            change.accept(p);
            assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining(messagePart);
        }
    }
}
