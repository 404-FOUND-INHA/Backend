package com.__found.smart_library.domain.evaluation.entity;

/**
 * 폐기 평가 기준(평가축). DB 에는 이름 그대로 저장한다.
 * 기관 적합성은 추후 확장 대상이라 아직 없다. 평가축이 바뀌면 JEV 와 코드를 맞춰야 한다.
 */
public enum CriterionType {
    /** 수요성(대출 이력) */
    DEMAND,
    /** 희소성 */
    RARITY,
    /** 중복성(복본) */
    DUPLICATION,
    /** 내용 유효성 */
    CONTENT_VALIDITY,
    /** 물리적 상태(오손) */
    PHYSICAL_CONDITION
}
