package com.__found.smart_library.domain.evaluation.entity;

/** 평가 결과로 정해진 도서의 후보 상태. DB 에는 이름 그대로 저장한다. 시스템은 후보를 제안만 하고 최종 결정은 사서가 한다. */
public enum CandidateStatus {
    /** 아직 종합 판정 전. JEV 원본 결과는 후보 여부를 판정하지 않으므로 항상 이 값이다. */
    UNDECIDED,
    /** 종합 점수가 기준 점수 미만이라 보존한다. */
    KEEP,
    /** 종합 점수가 기준 점수 이상이라 폐기 검토 후보이다. */
    DISPOSAL_REVIEW,
    /** 점수가 없는 평가축이 있어 종합 점수를 계산하지 않았다(후보로 판정하지 않는다). */
    NOT_EVALUABLE
}
