package com.__found.smart_library.domain.condition.entity;

/** 도서 오손·파손 유형. DB 에는 이름 그대로 저장한다. */
public enum ConditionType {
    /** 오염 */
    STAIN,
    /** 찢김 */
    TORN,
    /** 침수 */
    WATER_DAMAGE,
    /** 제본 파손 */
    BINDING_DAMAGE
}
