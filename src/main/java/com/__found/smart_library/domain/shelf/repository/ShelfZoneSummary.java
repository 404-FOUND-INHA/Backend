package com.__found.smart_library.domain.shelf.repository;

/** 사용 중 서가의 zone(자료실)별 집계 결과. */
public interface ShelfZoneSummary {

    String getZone();

    /** zone 내 서가의 가장 낮은 층. 층 정보가 없으면 null. */
    Integer getFloorNo();

    long getShelfCount();
}
