package com.__found.smart_library.domain.evaluation.metric;

import com.__found.smart_library.domain.condition.entity.ConditionSeverity;
import com.__found.smart_library.domain.condition.entity.ConditionType;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Backend 평가 지표(수요성·물리적 상태·중복성)의 점수 규칙. application.yml 의 app.evaluation.metrics.* 로 바꿀 수 있다.
 * 값은 평가표가 확정되기 전의 임시 기준이다. 모든 점수는 0~100 이며 <b>높을수록 폐기 검토 필요도가 높다</b>.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.evaluation.metrics")
public class BackendMetricProperties {

    private Demand demand = new Demand();
    private Condition condition = new Condition();
    private Duplication duplication = new Duplication();

    /** 잘못된 설정은 기동 시점에 실패시킨다(점수가 조용히 어긋나는 것을 막는다). */
    @PostConstruct
    void validate() {
        demand.validate();
        condition.validate();
        duplication.validate();
    }

    /** 수요성: 최근 대출이 적고 마지막 대출이 오래될수록 높다. */
    @Getter
    @Setter
    public static class Demand {
        /** 최근 대출 건수를 세는 기간(년). */
        private int windowYears = 3;
        /** 기간 내 대출이 이 건수 이상이면 대출 건수 점수가 0 이다. */
        private int loanSaturation = 5;
        /** 대출 건수 점수와 마지막 대출 경과 점수의 비중(합 1). */
        private BigDecimal recentLoanWeight = new BigDecimal("0.6");
        private BigDecimal recencyWeight = new BigDecimal("0.4");

        void validate() {
            require(windowYears >= 1, "demand.window-years 는 1 이상이어야 합니다.");
            require(loanSaturation >= 1, "demand.loan-saturation 은 1 이상이어야 합니다.");
            require(recentLoanWeight.signum() >= 0 && recencyWeight.signum() >= 0
                            && recentLoanWeight.add(recencyWeight).compareTo(BigDecimal.ONE) == 0,
                    "demand 비중은 0 이상이며 합이 1 이어야 합니다.");
        }
    }

    /** 물리적 상태: 가장 나쁜 오손 기록(유형 × 심각도)이 기준이고 기록이 늘수록 가산한다. */
    @Getter
    @Setter
    public static class Condition {
        private Map<ConditionSeverity, Integer> severityScore = defaultSeverityScores();
        private Map<ConditionType, BigDecimal> typeFactor = defaultTypeFactors();
        /** 심각도가 비어 있는 기록에 적용할 심각도. */
        private ConditionSeverity defaultSeverity = ConditionSeverity.MEDIUM;
        /** 가장 나쁜 기록 외에 기록이 하나 더 있을 때마다 더하는 점수. */
        private int extraReportBonus = 5;

        void validate() {
            for (ConditionSeverity s : ConditionSeverity.values()) {
                Integer score = severityScore.get(s);
                require(score != null && score >= 0 && score <= 100,
                        "condition.severity-score." + s + " 는 0~100 이어야 합니다.");
            }
            for (ConditionType t : ConditionType.values()) {
                BigDecimal f = typeFactor.get(t);
                require(f != null && f.signum() >= 0 && f.compareTo(BigDecimal.ONE) <= 0,
                        "condition.type-factor." + t + " 는 0~1 이어야 합니다.");
            }
            require(defaultSeverity != null, "condition.default-severity 가 필요합니다.");
            require(extraReportBonus >= 0, "condition.extra-report-bonus 는 0 이상이어야 합니다.");
        }

        private static Map<ConditionSeverity, Integer> defaultSeverityScores() {
            Map<ConditionSeverity, Integer> m = new EnumMap<>(ConditionSeverity.class);
            m.put(ConditionSeverity.LOW, 30);
            m.put(ConditionSeverity.MEDIUM, 60);
            m.put(ConditionSeverity.HIGH, 90);
            return m;
        }

        private static Map<ConditionType, BigDecimal> defaultTypeFactors() {
            Map<ConditionType, BigDecimal> m = new EnumMap<>(ConditionType.class);
            m.put(ConditionType.STAIN, new BigDecimal("0.6"));
            m.put(ConditionType.TORN, new BigDecimal("0.8"));
            m.put(ConditionType.WATER_DAMAGE, BigDecimal.ONE);
            m.put(ConditionType.BINDING_DAMAGE, BigDecimal.ONE);
            return m;
        }
    }

    /** 중복성: 같은 청구기호(분류기호 + 도서기호)의 도서가 많을수록 높다. */
    @Getter
    @Setter
    public static class Duplication {
        /** 복본(자기 자신 포함)이 이 권수 이상이면 100 점이다. 1권이면 0 점이다. */
        private int saturationCopies = 4;

        void validate() {
            require(saturationCopies >= 2, "duplication.saturation-copies 는 2 이상이어야 합니다.");
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException("app.evaluation.metrics 설정 오류: " + message);
        }
    }
}
