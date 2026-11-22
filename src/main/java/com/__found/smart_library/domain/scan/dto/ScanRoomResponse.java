package com.__found.smart_library.domain.scan.dto;

import com.__found.smart_library.domain.shelf.repository.ShelfZoneSummary;

/**
 * 점검 대상으로 고를 수 있는 자료실. hasOpenSession 이 true 이면 미완료 회차가 있어 새 회차를 만들 수 없다.
 */
public record ScanRoomResponse(String zone, Integer floorNo, long shelfCount, boolean hasOpenSession) {

    public static ScanRoomResponse of(ShelfZoneSummary summary, boolean hasOpenSession) {
        return new ScanRoomResponse(summary.getZone(), summary.getFloorNo(), summary.getShelfCount(), hasOpenSession);
    }
}
