package com.__found.smart_library.domain.evaluation.jev;

import com.__found.smart_library.domain.evaluation.entity.CriterionType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JEV 에 보내는 질문. 평가축마다 점수형(score) 질문 하나이고, 질문 이름이 곧 평가축 코드다.
 *
 * <p>단계 설명은 위에서부터 0, 1, 2… 점이며 단계 번호가 클수록 폐기 검토 필요성이 크다(Backend 점수 방향과 같다).
 * 문구는 초안이며 사서 검토로 확정해야 한다. 문구를 바꾸면 이전 평가 결과와 의미가 달라지므로 모델 버전과 함께 관리한다.
 */
public final class JevQuestions {

    /** JEV 가 맡는 평가축. 응답에도 이 축이 모두 있어야 한다. */
    public static final List<CriterionType> AXES = List.of(CriterionType.RARITY, CriterionType.CONTENT_VALIDITY);

    /** 단계 번호 범위(0 ~ MAX_LEVEL). */
    public static final int MAX_LEVEL = 4;

    private static final List<String> RARITY_LEVELS = List.of(
            "매우 희소함. 구하기 어려워 보존 가치가 큼",
            "희소한 편임",
            "보통임",
            "흔한 편임",
            "매우 흔함. 쉽게 구할 수 있음");

    private static final List<String> CONTENT_VALIDITY_LEVELS = List.of(
            "내용이 지금도 완전히 유효함",
            "대체로 유효함",
            "일부 내용이 낡았음",
            "상당 부분이 낡았음",
            "내용이 낡아 더는 유효하지 않음");

    private JevQuestions() {
    }

    /** 평가축별 질문. 질문 이름은 평가축 코드(enum 이름)이다. */
    public static Map<String, Object> build() {
        Map<String, Object> questions = new LinkedHashMap<>();
        questions.put(CriterionType.RARITY.name(),
                scoreQuestion("이 도서가 도서관 사이에서 얼마나 흔한지 평가하세요.", RARITY_LEVELS));
        questions.put(CriterionType.CONTENT_VALIDITY.name(),
                scoreQuestion("이 도서의 내용이 evaluationYear 기준으로 얼마나 유효한지 평가하세요.",
                        CONTENT_VALIDITY_LEVELS));
        return questions;
    }

    private static Map<String, Object> scoreQuestion(String instructions, List<String> levels) {
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", "score");
        question.put("instructions", instructions);
        question.put("criteria", levels);
        return question;
    }
}
