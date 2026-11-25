package com.__found.smart_library.domain.evaluation.entity;

/** 평가 결과를 만든 주체. DB 에는 이름 그대로 저장한다. */
public enum EvaluationType {
    /** JEV(TypeSafe System One) 가 계산한 원본 결과 */
    JEV,
    /** Backend 가 계산하거나 종합한 결과 */
    BACKEND
}
