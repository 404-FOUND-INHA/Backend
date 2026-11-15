package com.__found.smart_library.domain.shelf.dto;

import com.__found.smart_library.domain.shelf.entity.Shelf;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ShelfResponse(
        Long id,
        String shelfCode,
        String zone,
        Integer floorNo,
        Integer shelfNo,
        Integer levelNo,
        String callNumberStart,
        String callNumberEnd,
        String markerId,
        BigDecimal mapX,
        BigDecimal mapY,
        BigDecimal mapZ,
        BigDecimal widthCm,
        BigDecimal heightCm,
        BigDecimal depthCm,
        BigDecimal rotationDegree,
        Integer displayOrder,
        boolean active,
        long bookCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static ShelfResponse of(Shelf s, long bookCount) {
        return new ShelfResponse(s.getId(), s.getShelfCode(), s.getZone(), s.getFloorNo(), s.getShelfNo(),
                s.getLevelNo(), s.getCallNumberStart(), s.getCallNumberEnd(), s.getMarkerId(),
                s.getMapX(), s.getMapY(), s.getMapZ(), s.getWidthCm(), s.getHeightCm(), s.getDepthCm(),
                s.getRotationDegree(), s.getDisplayOrder(), s.isActive(), bookCount,
                s.getCreatedAt(), s.getUpdatedAt());
    }
}
