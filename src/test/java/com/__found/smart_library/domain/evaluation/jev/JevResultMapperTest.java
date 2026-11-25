package com.__found.smart_library.domain.evaluation.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.__found.smart_library.domain.evaluation.entity.CriterionType;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JevResultMapperTest {

    private final JevResultMapper mapper = new JevResultMapper();

    static Map<String, BigDecimal> probabilities(String... values) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            map.put(String.valueOf(i), new BigDecimal(values[i]));
        }
        return map;
    }

    static JevResponse.Answer answer(String score, String confidence, Map<String, BigDecimal> probabilities) {
        return new JevResponse.Answer("score", new BigDecimal(score), new BigDecimal(confidence), probabilities);
    }

    static JevResponse response(JevResponse.Answer rarity, JevResponse.Answer contentValidity) {
        Map<String, JevResponse.Answer> answers = new LinkedHashMap<>();
        answers.put("RARITY", rarity);
        answers.put("CONTENT_VALIDITY", contentValidity);
        return new JevResponse("jev-1.14.0", answers);
    }

    private JevResponse.Answer valid() {
        return answer("2.53", "0.78", probabilities("0.02", "0.08", "0.35", "0.45", "0.10"));
    }

    @Test
    @DisplayName("점수·신뢰도·확률을 저장 형식으로 바꾼다: 정규화는 ÷4×100, 선택 등급은 확률이 가장 큰 단계")
    void mapsValidResponse() {
        List<JevCriterionResult> results = mapper.map(response(valid(),
                answer("2.65", "0.6400", probabilities("0.05", "0.10", "0.20", "0.45", "0.20"))));

        assertThat(results).hasSize(2);
        JevCriterionResult rarity = results.get(0);
        assertThat(rarity.criterionType()).isEqualTo(CriterionType.RARITY);
        assertThat(rarity.rawScore()).isEqualByComparingTo("2.53");
        assertThat(rarity.normalizedScore()).isEqualByComparingTo("63.25");
        assertThat(rarity.confidence()).isEqualByComparingTo("0.78");
        assertThat(rarity.selectedGrade()).isEqualTo(3);
        assertThat(rarity.probabilityDistribution()).containsEntry("3", new BigDecimal("0.45"));
        assertThat(results.get(1).criterionType()).isEqualTo(CriterionType.CONTENT_VALIDITY);
        assertThat(results.get(1).normalizedScore()).isEqualByComparingTo("66.25");
    }

    @Test
    @DisplayName("경계값 0점과 4점은 정규화 0.00 과 100.00 이고 자릿수에 맞게 반올림한다")
    void boundariesAndRounding() {
        List<JevCriterionResult> results = mapper.map(response(
                answer("0", "0", probabilities("1", "0", "0", "0", "0")),
                answer("4", "1", probabilities("0", "0", "0", "0", "1"))));

        assertThat(results.get(0).normalizedScore()).isEqualByComparingTo("0.00");
        assertThat(results.get(0).selectedGrade()).isZero();
        assertThat(results.get(1).normalizedScore()).isEqualByComparingTo("100.00");
        assertThat(results.get(1).selectedGrade()).isEqualTo(4);

        JevCriterionResult rounded = mapper.map(response(
                answer("1.23456", "0.123456", probabilities("0.5", "0.5")), valid())).get(0);
        assertThat(rounded.rawScore()).isEqualByComparingTo("1.23");
        assertThat(rounded.confidence()).isEqualByComparingTo("0.1235");
        assertThat(rounded.normalizedScore()).isEqualByComparingTo("30.86");
    }

    @Test
    @DisplayName("확률이 같으면 번호가 작은 단계를 선택한다")
    void tieBreaksToLowerLevel() {
        JevCriterionResult result = mapper.map(response(
                answer("2", "0.5", probabilities("0.1", "0.4", "0.1", "0.4", "0.0")), valid())).get(0);

        assertThat(result.selectedGrade()).isEqualTo(1);
    }

    @Test
    @DisplayName("점수·신뢰도·확률이 범위를 벗어나면 거부한다")
    void rejectsOutOfRange() {
        assertInvalid(response(answer("-0.01", "0.5", probabilities("1")), valid()), "점수");
        assertInvalid(response(answer("4.01", "0.5", probabilities("1")), valid()), "점수");
        assertInvalid(response(valid(), answer("2", "1.01", probabilities("1"))), "신뢰도");
        assertInvalid(response(valid(), answer("2", "-0.1", probabilities("1"))), "신뢰도");
        assertInvalid(response(answer("2", "0.5", probabilities("1.5")), valid()), "확률");
    }

    @Test
    @DisplayName("확률 분포의 단계 번호가 0~4 가 아니거나 비어 있으면 거부한다")
    void rejectsBadProbabilityKeys() {
        Map<String, BigDecimal> outOfRange = new LinkedHashMap<>(Map.of("5", new BigDecimal("1")));
        Map<String, BigDecimal> notNumber = new LinkedHashMap<>(Map.of("high", new BigDecimal("1")));

        assertInvalid(response(answer("2", "0.5", outOfRange), valid()), "단계 번호");
        assertInvalid(response(answer("2", "0.5", notNumber), valid()), "단계 번호");
        assertInvalid(response(answer("2", "0.5", Map.of()), valid()), "확률 분포");
        assertInvalid(response(new JevResponse.Answer("score", new BigDecimal("2"), new BigDecimal("0.5"), null),
                valid()), "확률 분포");
    }

    @Test
    @DisplayName("확률 합계가 1에서 ±0.05 를 넘게 벗어나면 거부한다(전부 0 인 분포 포함)")
    void validatesProbabilitySum() {
        assertInvalid(response(answer("2", "0.5", probabilities("0", "0", "0", "0", "0")), valid()), "합");
        assertInvalid(response(answer("2", "0.5", probabilities("0.5", "0.4")), valid()), "합");
        assertInvalid(response(answer("2", "0.5", probabilities("0.5", "0.56")), valid()), "합");
        // 반올림 오차 범위는 허용한다
        assertThat(mapper.map(response(answer("2", "0.5", probabilities("0.5", "0.46")), valid()))).hasSize(2);
        assertThat(mapper.map(response(answer("2", "0.5", probabilities("0.5", "0.54")), valid()))).hasSize(2);
        assertThat(mapper.map(response(answer("2", "0.5", probabilities("0.5", "0.5")), valid()))).hasSize(2);
    }

    @Test
    @DisplayName("점수나 신뢰도가 없거나 답이 점수형이 아니면 거부한다")
    void rejectsMissingValuesAndWrongType() {
        assertInvalid(response(new JevResponse.Answer("score", null, new BigDecimal("0.5"),
                probabilities("1")), valid()), "점수");
        assertInvalid(response(new JevResponse.Answer("score", new BigDecimal("2"), null,
                probabilities("1")), valid()), "신뢰도");
        assertInvalid(response(new JevResponse.Answer("choice", new BigDecimal("2"), new BigDecimal("0.5"),
                probabilities("1")), valid()), "점수형");
    }

    @Test
    @DisplayName("질문한 평가축과 답이 다르면(누락·추가·다른 이름) 거부한다")
    void rejectsAxisMismatch() {
        Map<String, JevResponse.Answer> missing = new LinkedHashMap<>();
        missing.put("RARITY", valid());
        assertInvalid(new JevResponse("jev-1", missing), "개수");

        Map<String, JevResponse.Answer> wrongName = new LinkedHashMap<>();
        wrongName.put("RARITY", valid());
        wrongName.put("DEMAND", valid());
        assertInvalid(new JevResponse("jev-1", wrongName), "CONTENT_VALIDITY");

        Map<String, JevResponse.Answer> extra = new LinkedHashMap<>();
        extra.put("RARITY", valid());
        extra.put("CONTENT_VALIDITY", valid());
        extra.put("DEMAND", valid());
        assertInvalid(new JevResponse("jev-1", extra), "개수");
        assertInvalid(new JevResponse("jev-1", null), "개수");
    }

    @Test
    @DisplayName("모델 이름이 없거나 비어 있거나 30자를 넘으면 거부하고, 30자는 허용한다")
    void validatesModelName() {
        Map<String, JevResponse.Answer> answers = response(valid(), valid()).answers();
        assertInvalid(new JevResponse(null, answers), "모델");
        assertInvalid(new JevResponse("  ", answers), "모델");
        assertInvalid(new JevResponse("m".repeat(31), answers), "모델");
        assertInvalid(null, "모델");
        assertThat(mapper.map(new JevResponse("m".repeat(30), answers))).hasSize(2);
    }

    private void assertInvalid(JevResponse response, String messagePart) {
        assertThatThrownBy(() -> mapper.map(response))
                .isInstanceOf(JevInvalidResponseException.class)
                .hasMessageContaining(messagePart);
    }
}
