package com.__found.smart_library.domain.shelf.dto;

import com.__found.smart_library.domain.shelf.entity.Shelf;
import java.math.BigDecimal;
import java.util.List;

/**
 * 서가 지도 데이터. 지도 좌표(mapX, mapY)가 설정된 사용 중 서가만 내려준다.
 */
public record MapResponse(List<Integer> floors, List<MapShelf> shelves) {

    public record MapShelf(
            Long id,
            String shelfCode,
            String zone,
            Integer floorNo,
            Integer shelfNo,
            String markerId,
            BigDecimal mapX,
            BigDecimal mapY,
            BigDecimal mapZ,
            BigDecimal widthCm,
            BigDecimal heightCm,
            BigDecimal depthCm,
            BigDecimal rotationDegree,
            long bookCount
    ) {

        public static MapShelf of(Shelf s, long bookCount) {
            return new MapShelf(s.getId(), s.getShelfCode(), s.getZone(), s.getFloorNo(), s.getShelfNo(),
                    s.getMarkerId(), s.getMapX(), s.getMapY(), s.getMapZ(), s.getWidthCm(), s.getHeightCm(),
                    s.getDepthCm(), s.getRotationDegree(), bookCount);
        }
    }
}
