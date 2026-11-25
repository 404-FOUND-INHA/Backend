package com.__found.smart_library.domain.evaluation.query;

import com.__found.smart_library.domain.shelf.entity.Shelf;
import java.math.BigDecimal;

/** 도서의 현재 서가 위치(book.current_shelf 기준). 지도 좌표는 3D 시각화에 쓴다. */
public record ShelfInfo(
        Long shelfId,
        String shelfCode,
        String zone,
        Integer floorNo,
        Integer shelfNo,
        Integer levelNo,
        BigDecimal mapX,
        BigDecimal mapY,
        BigDecimal mapZ) {

    /** 서가가 지정되지 않은 도서이면 null. */
    static ShelfInfo from(Shelf shelf) {
        if (shelf == null) {
            return null;
        }
        return new ShelfInfo(shelf.getId(), shelf.getShelfCode(), shelf.getZone(), shelf.getFloorNo(),
                shelf.getShelfNo(), shelf.getLevelNo(), shelf.getMapX(), shelf.getMapY(), shelf.getMapZ());
    }

    /** 서가가 지정되지 않은 도서이면 null. */
    static ShelfInfo from(EvaluationItemRow row) {
        if (row.shelfId() == null) {
            return null;
        }
        return new ShelfInfo(row.shelfId(), row.shelfCode(), row.zone(), row.floorNo(), row.shelfNo(),
                row.levelNo(), row.mapX(), row.mapY(), row.mapZ());
    }
}
