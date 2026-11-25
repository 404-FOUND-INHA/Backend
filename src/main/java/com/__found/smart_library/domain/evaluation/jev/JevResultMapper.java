package com.__found.smart_library.domain.evaluation.jev;

import com.__found.smart_library.domain.evaluation.entity.CriterionType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * JEV 응답을 검증하고 저장 형식으로 바꾼다. 하나라도 규칙을 벗어나면 그 도서의 응답 전체를 버린다.
 *
 * <ul>
 *   <li>모델 이름은 필수이며 모델 버전 컬럼 길이(30자) 이내</li>
 *   <li>답은 질문한 평가축({@link JevQuestions#AXES})과 정확히 같은 이름이고 모두 점수형(score)</li>
 *   <li>score 는 0 ~ {@value JevQuestions#MAX_LEVEL}, confidence 는 0 ~ 1</li>
 *   <li>probabilities 의 키는 0 ~ {@value JevQuestions#MAX_LEVEL} 단계 번호이고 값은 0 ~ 1</li>
 * </ul>
 *
 * <p>정규화 점수는 score ÷ {@value JevQuestions#MAX_LEVEL} × 100 이고, 선택 등급은 확률이 가장 큰 단계이다
 * (같으면 번호가 작은 단계).
 */
@Component
public class JevResultMapper {

    static final int MODEL_VERSION_MAX_LENGTH = 30;
    private static final BigDecimal MAX_LEVEL = BigDecimal.valueOf(JevQuestions.MAX_LEVEL);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public List<JevCriterionResult> map(JevResponse response) {
        if (response == null || response.model() == null || response.model().isBlank()) {
            throw new JevInvalidResponseException("응답에 모델 이름이 없습니다.");
        }
        if (response.model().length() > MODEL_VERSION_MAX_LENGTH) {
            throw new JevInvalidResponseException("모델 이름이 너무 깁니다.");
        }
        Map<String, JevResponse.Answer> answers = response.answers();
        if (answers == null || answers.size() != JevQuestions.AXES.size()) {
            throw new JevInvalidResponseException("질문한 평가축과 답의 개수가 다릅니다.");
        }
        List<JevCriterionResult> results = new ArrayList<>();
        for (CriterionType axis : JevQuestions.AXES) {
            JevResponse.Answer answer = answers.get(axis.name());
            if (answer == null) {
                throw new JevInvalidResponseException(axis + " 답이 없습니다.");
            }
            results.add(mapAnswer(axis, answer));
        }
        return results;
    }

    private static JevCriterionResult mapAnswer(CriterionType axis, JevResponse.Answer answer) {
        if (!"score".equals(answer.type())) {
            throw new JevInvalidResponseException(axis + " 답이 점수형이 아닙니다.");
        }
        BigDecimal score = answer.score();
        if (score == null || score.signum() < 0 || score.compareTo(MAX_LEVEL) > 0) {
            throw new JevInvalidResponseException(axis + " 점수가 0~" + JevQuestions.MAX_LEVEL + " 범위를 벗어났습니다.");
        }
        BigDecimal confidence = answer.confidence();
        if (confidence == null || confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new JevInvalidResponseException(axis + " 신뢰도가 0~1 범위를 벗어났습니다.");
        }
        Map<String, BigDecimal> probabilities = validProbabilities(axis, answer.probabilities());
        return new JevCriterionResult(
                axis,
                score.setScale(2, RoundingMode.HALF_UP),
                score.multiply(HUNDRED).divide(MAX_LEVEL, 2, RoundingMode.HALF_UP),
                confidence.setScale(4, RoundingMode.HALF_UP),
                mostLikelyLevel(probabilities),
                probabilities);
    }

    private static Map<String, BigDecimal> validProbabilities(CriterionType axis, Map<String, BigDecimal> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new JevInvalidResponseException(axis + " 확률 분포가 없습니다.");
        }
        Map<String, BigDecimal> copy = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : raw.entrySet()) {
            int level = parseLevel(axis, entry.getKey());
            BigDecimal probability = entry.getValue();
            if (probability == null || probability.signum() < 0 || probability.compareTo(BigDecimal.ONE) > 0) {
                throw new JevInvalidResponseException(axis + " 확률이 0~1 범위를 벗어났습니다.");
            }
            copy.put(String.valueOf(level), probability);
        }
        return copy;
    }

    private static int parseLevel(CriterionType axis, String key) {
        try {
            int level = Integer.parseInt(key);
            if (level >= 0 && level <= JevQuestions.MAX_LEVEL) {
                return level;
            }
        } catch (NumberFormatException ignored) {
            // 아래에서 같은 오류로 처리한다.
        }
        throw new JevInvalidResponseException(axis + " 확률 분포의 단계 번호가 올바르지 않습니다.");
    }

    private static int mostLikelyLevel(Map<String, BigDecimal> probabilities) {
        int best = -1;
        BigDecimal bestProbability = null;
        for (int level = 0; level <= JevQuestions.MAX_LEVEL; level++) {
            BigDecimal probability = probabilities.get(String.valueOf(level));
            if (probability != null && (bestProbability == null || probability.compareTo(bestProbability) > 0)) {
                best = level;
                bestProbability = probability;
            }
        }
        return best;
    }
}
