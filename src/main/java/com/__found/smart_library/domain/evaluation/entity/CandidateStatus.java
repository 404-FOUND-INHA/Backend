package com.__found.smart_library.domain.evaluation.entity;

/** 평가 결과로 정해진 도서의 후보 상태. DB 에는 이름 그대로 저장한다. */
public enum CandidateStatus {
    /** 아직 종합 판정 전. JEV 원본 결과는 후보 여부를 판정하지 않으므로 항상 이 값이다. */
    UNDECIDED
}
