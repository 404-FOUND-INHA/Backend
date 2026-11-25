package com.__found.smart_library.domain.evaluation.metric;

import com.__found.smart_library.domain.condition.entity.ConditionSeverity;
import com.__found.smart_library.domain.condition.entity.ConditionType;
import com.__found.smart_library.domain.evaluation.entity.CriterionType;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Backend 가 직접 계산하는 평가 지표. 데이터베이스를 읽지 않는 순수 계산이라 단위 테스트가 쉽다.
 * 모든 점수는 0~100 이며 높을수록 폐기 검토 필요도가 높다. 규칙은 {@link BackendMetricProperties} 로 바꾼다.
 */
@Component
@RequiredArgsConstructor
public class BackendMetricCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    /** raw_score 컬럼은 DECIMAL(5,2) 이므로 넘치지 않게 제한한다. */
    private static final BigDecimal RAW_MAX = new BigDecimal("999.99");

    private final BackendMetricProperties properties;

    /** 대출 이력 기반 수요성. */
    public CriterionScore demand(DemandInput in, LocalDate today) {
        BackendMetricProperties.Demand rule = properties.getDemand();
        boolean neverBorrowed = in.recentLoanCount() == 0 && in.totalLoanCount() == 0 && in.lastLoanDate() == null;
        if (neverBorrowed) {
            return new CriterionScore(CriterionType.DEMAND, scale(BigDecimal.ZERO), scale(HUNDRED), "대출 이력이 없음");
        }

        // 기간 내 대출이 포화 건수 이상이면 0, 없으면 100 으로 선형 보간
        BigDecimal loanPart = in.recentLoanCount() >= rule.getLoanSaturation()
                ? BigDecimal.ZERO
                : HUNDRED.subtract(HUNDRED.multiply(BigDecimal.valueOf(in.recentLoanCount()))
                        .divide(BigDecimal.valueOf(rule.getLoanSaturation()), MC));

        // 마지막 대출이 없거나 기간(년) 이상 지났으면 100, 오늘이면 0 으로 선형 보간
        BigDecimal recencyPart;
        String lastText;
        if (in.lastLoanDate() == null) {
            recencyPart = HUNDRED;
            lastText = "마지막 대출일 없음";
        } else {
            long days = Math.max(ChronoUnit.DAYS.between(in.lastLoanDate(), today), 0);
            BigDecimal ratio = BigDecimal.valueOf(days)
                    .divide(DAYS_PER_YEAR.multiply(BigDecimal.valueOf(rule.getWindowYears())), MC)
                    .min(BigDecimal.ONE);
            recencyPart = HUNDRED.multiply(ratio);
            lastText = "마지막 대출 " + in.lastLoanDate();
        }

        BigDecimal score = loanPart.multiply(rule.getRecentLoanWeight())
                .add(recencyPart.multiply(rule.getRecencyWeight()));
        String basis = "최근 " + rule.getWindowYears() + "년 대출 " + in.recentLoanCount() + "건, " + lastText
                + " (누적 " + in.totalLoanCount() + "건)";
        return new CriterionScore(CriterionType.DEMAND, scale(BigDecimal.valueOf(in.recentLoanCount())),
                scale(clamp(score)), basis);
    }

    /** 오손 기록 기반 물리적 상태. 기록이 없으면 양호(0)이다. */
    public CriterionScore physicalCondition(List<ConditionInput> reports) {
        if (reports.isEmpty()) {
            return new CriterionScore(CriterionType.PHYSICAL_CONDITION, scale(BigDecimal.ZERO),
                    scale(BigDecimal.ZERO), "오손 기록 없음");
        }
        BackendMetricProperties.Condition rule = properties.getCondition();
        BigDecimal worst = BigDecimal.ZERO;
        ConditionInput worstReport = null;
        for (ConditionInput r : reports) {
            ConditionSeverity severity = r.severity() != null ? r.severity() : rule.getDefaultSeverity();
            BigDecimal s = BigDecimal.valueOf(rule.getSeverityScore().get(severity))
                    .multiply(rule.getTypeFactor().get(r.type()));
            if (worstReport == null || s.compareTo(worst) > 0) {
                worst = s;
                worstReport = new ConditionInput(r.type(), severity);
            }
        }
        BigDecimal bonus = BigDecimal.valueOf((long) rule.getExtraReportBonus() * (reports.size() - 1));
        BigDecimal score = clamp(worst.add(bonus));
        String basis = "오손 기록 " + reports.size() + "건, 가장 심한 기록: " + worstReport.type() + "/"
                + worstReport.severity();
        return new CriterionScore(CriterionType.PHYSICAL_CONDITION, scale(worst.min(RAW_MAX)), scale(score), basis);
    }

    /** 복본 수 기반 중복성. copyCount 는 자기 자신을 포함한 같은 청구기호의 도서 수(제적 제외)이다. */
    public CriterionScore duplication(int copyCount) {
        int saturation = properties.getDuplication().getSaturationCopies();
        int copies = Math.max(copyCount, 1);
        BigDecimal ratio = BigDecimal.valueOf(copies - 1)
                .divide(BigDecimal.valueOf(saturation - 1), MC).min(BigDecimal.ONE);
        BigDecimal raw = BigDecimal.valueOf(copies).min(RAW_MAX);
        return new CriterionScore(CriterionType.DUPLICATION, scale(raw), scale(HUNDRED.multiply(ratio)),
                "같은 청구기호의 도서 " + copies + "권");
    }

    private static BigDecimal clamp(BigDecimal v) {
        return v.max(BigDecimal.ZERO).min(HUNDRED);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** 수요성 계산 입력. recentLoanCount 는 설정한 기간(년) 안의 대출 건수이다. */
    public record DemandInput(int recentLoanCount, LocalDate lastLoanDate, int totalLoanCount) {
    }

    /** 오손 기록 하나. severity 가 null 이면 설정의 기본 심각도를 쓴다. */
    public record ConditionInput(ConditionType type, ConditionSeverity severity) {
    }
}
