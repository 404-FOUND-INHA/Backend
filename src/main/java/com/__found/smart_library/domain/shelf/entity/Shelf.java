package com.__found.smart_library.domain.shelf.entity;

import com.__found.smart_library.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "shelf")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shelf extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "shelf_id")
    private Long id;

    /** 화면 표시 서가 코드. Vision 과 공유하는 식별자라 API 로 바꿀 수 없다. */
    @Column(nullable = false, length = 50)
    private String shelfCode;

    @Column(length = 30)
    private String zone;

    private Integer floorNo;
    private Integer shelfNo;
    private Integer levelNo;

    @Column(length = 150)
    private String callNumberStart;

    @Column(length = 150)
    private String callNumberEnd;

    @Column(length = 50)
    private String markerId;

    // 대문자로 끝나는 필드명은 기본 네이밍 전략이 map_x 로 바꿔주지 않아 컬럼명을 직접 지정한다.
    @Column(name = "map_x")
    private BigDecimal mapX;

    @Column(name = "map_y")
    private BigDecimal mapY;

    @Column(name = "map_z")
    private BigDecimal mapZ;

    private BigDecimal widthCm;
    private BigDecimal heightCm;
    private BigDecimal depthCm;
    private BigDecimal rotationDegree;
    private Integer displayOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    /** PUT 은 전체 교체이므로 값이 없으면 null 로 덮어쓴다. shelfCode 는 변경하지 않는다. */
    public void replaceConfiguration(String zone, Integer floorNo, Integer shelfNo, Integer levelNo,
                                     String callNumberStart, String callNumberEnd, String markerId,
                                     BigDecimal mapX, BigDecimal mapY, BigDecimal mapZ,
                                     BigDecimal widthCm, BigDecimal heightCm, BigDecimal depthCm,
                                     BigDecimal rotationDegree, Integer displayOrder, boolean active) {
        this.zone = zone;
        this.floorNo = floorNo;
        this.shelfNo = shelfNo;
        this.levelNo = levelNo;
        this.callNumberStart = callNumberStart;
        this.callNumberEnd = callNumberEnd;
        this.markerId = markerId;
        this.mapX = mapX;
        this.mapY = mapY;
        this.mapZ = mapZ;
        this.widthCm = widthCm;
        this.heightCm = heightCm;
        this.depthCm = depthCm;
        this.rotationDegree = rotationDegree;
        this.displayOrder = displayOrder;
        this.active = active;
    }
}
