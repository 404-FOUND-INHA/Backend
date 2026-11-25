package com.__found.smart_library.domain.evaluation.composite;

import com.__found.smart_library.domain.evaluation.entity.CandidateStatus;
import com.__found.smart_library.domain.evaluation.entity.CriterionType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 평가축별 점수를 가중치로 합쳐 종합 점수를 만들고 폐기 검토 후보 여부와 선정 근거를 정한다(DB 와 무관한 순수 계산).
 *
 * <p>규칙
 * <ul>
 *   <li>모든 점수는 0~100이며 높을수록 폐기 검토 필요성이 크다. 종합 점수 = Σ(점수 × 가중치), 소수 둘째 자리 반올림.</li>
 *   <li>반올림한 종합 점수가 기준 점수 이상이면 {@link CandidateStatus#DISPOSAL_REVIEW}, 미만이면
 *       {@link CandidateStatus#KEEP}(화면에 보이는 점수와 판정이 어긋나지 않게 한다).</li>
 *   <li>가중치가 0보다 큰 평가축 중 점수가 없는 것이 하나라도 있으면 점수를 임의로 채우거나 가중치를 다시 나누지 않고
 *       {@link CandidateStatus#NOT_EVALUABLE} 로 두며 종합 점수는 비운다. 가중치가 0인 평가축은 없어도 된다.</li>
 * </ul>
 */
@Component
public class CompositeScoreCalculator {

    /** 평가축 하나의 점수(0~100)와 그 근거. */
    public record AxisScore(BigDecimal normalizedScore, String basis) {
    }

    /** 평가축 하나가 종합 점수에 기여한 몫. score 가 null 이면 점수가 없는 평가축이다. */
    public record Contribution(CriterionType criterionType, BigDecimal score, BigDecimal weight,
                               BigDecimal contribution, String basis) {
    }

    /** 계산 결과. NOT_EVALUABLE 이면 finalScore 가 null 이다. */
    public record Outcome(CandidateStatus status, BigDecimal finalScore, String reason,
                          List<Contribution> contributions) {
    }

    private static final int SCALE = 2;

    public Outcome calculate(Map<CriterionType, AxisScore> scores, Map<CriterionType, BigDecimal> weights,
                             BigDecimal disposalThreshold, String profileVersion) {
        List<Contribution> contributions = new ArrayList<>();
        List<CriterionType> missing = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (CriterionType type : CriterionType.values()) {
            BigDecimal weight = weights.getOrDefault(type, BigDecimal.ZERO);
            AxisScore axis = scores.get(type);
            if (axis == null) {
                contributions.add(new Contribution(type, null, weight, null, null));
                if (weight.signum() > 0) {
                    missing.add(type);
                }
                continue;
            }
            BigDecimal part = axis.normalizedScore().multiply(weight);
            sum = sum.add(part);
            contributions.add(new Contribution(type, axis.normalizedScore(), weight,
                    part.setScale(SCALE, RoundingMode.HALF_UP), axis.basis()));
        }

        if (!missing.isEmpty()) {
            return new Outcome(CandidateStatus.NOT_EVALUABLE, null,
                    notEvaluableReason(missing, contributions, profileVersion), contributions);
        }
        BigDecimal finalScore = sum.setScale(SCALE, RoundingMode.HALF_UP);
        CandidateStatus status = finalScore.compareTo(disposalThreshold) >= 0
                ? CandidateStatus.DISPOSAL_REVIEW : CandidateStatus.KEEP;
        return new Outcome(status, finalScore,
                reason(status, finalScore, disposalThreshold, contributions, profileVersion), contributions);
    }

    /** 평가축의 화면 표기. */
    public static String label(CriterionType type) {
        return switch (type) {
            case DEMAND -> "수요성";
            case RARITY -> "희소성";
            case DUPLICATION -> "중복성";
            case CONTENT_VALIDITY -> "내용 유효성";
            case PHYSICAL_CONDITION -> "물리적 상태";
        };
    }

    private static String reason(CandidateStatus status, BigDecimal finalScore, BigDecimal threshold,
                                 List<Contribution> contributions, String profileVersion) {
        StringBuilder sb = new StringBuilder();
        sb.append("종합 점수 ").append(plain(finalScore)).append("점으로 기준 점수 ").append(plain(threshold))
                .append(status == CandidateStatus.DISPOSAL_REVIEW
                        ? "점 이상이라 폐기 검토 후보입니다." : "점 미만이라 보존합니다.")
                .append(" (평가 설정 ").append(profileVersion).append(")");
        contributions.stream()
                .filter(c -> c.contribution() != null && c.contribution().signum() > 0)
                .max(Comparator.comparing(Contribution::contribution))
                .ifPresent(top -> sb.append("\n기여도가 가장 큰 평가축: ").append(label(top.criterionType()))
                        .append(" ").append(plain(top.contribution())).append("점"));
        appendAxes(sb, contributions);
        return sb.toString();
    }

    private static String notEvaluableReason(List<CriterionType> missing, List<Contribution> contributions,
                                             String profileVersion) {
        StringBuilder sb = new StringBuilder("점수가 없는 평가축이 있어 종합 점수를 계산하지 않았습니다. 없는 평가축: ");
        sb.append(String.join(", ", missing.stream().map(CompositeScoreCalculator::label).toList()))
                .append(" (평가 설정 ").append(profileVersion).append(")");
        appendAxes(sb, contributions);
        return sb.toString();
    }

    private static void appendAxes(StringBuilder sb, List<Contribution> contributions) {
        for (Contribution c : contributions) {
            sb.append("\n- ").append(label(c.criterionType())).append(" ");
            if (c.score() == null) {
                sb.append(c.weight().signum() > 0 ? "점수 없음" : "점수 없음, 가중치 0이라 제외");
                continue;
            }
            sb.append(plain(c.score())).append("점 × ").append(weight(c.weight())).append(" = ")
                    .append(plain(c.contribution())).append("점");
            if (c.weight().signum() == 0) {
                sb.append(" (가중치 0이라 반영하지 않음)");
            }
            if (c.basis() != null && !c.basis().isBlank()) {
                sb.append(" · ").append(c.basis());
            }
        }
    }

    /** 점수는 항상 소수 둘째 자리로 보여 준다(입력의 자릿수와 상관없이 같은 모양). */
    private static String plain(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /** 가중치는 항상 소수 넷째 자리로 보여 준다. */
    private static String weight(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }
}
