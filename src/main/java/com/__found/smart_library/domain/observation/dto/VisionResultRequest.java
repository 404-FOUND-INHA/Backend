package com.__found.smart_library.domain.observation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Vision 서버가 회차의 OCR 결과 전체를 한 번에 보내는 요청.
 *
 * <p><b>Vision 담당자와 확정해야 할 연동 계약(현재는 Backend 의 임시 가정)</b>
 * <ul>
 *   <li>요청은 회차 전체를 한 번에 담는다. 회차의 모든 점검 대상 서가가 {@code shelves} 에 포함되어야 한다.</li>
 *   <li>{@code observations} 가 빈 배열이면 "촬영을 마쳤고 도서가 0권"이라는 뜻이다.
 *       촬영하지 않았거나 촬영·처리에 실패한 서가는 빈 배열로 표현하지 말고, 그런 서가가 있으면 요청을 보내지 않는다
 *       (빈 배열로 보내면 도서가 없는 서가로 저장되어 이후 소재불명 판단이 왜곡된다).</li>
 *   <li>{@code detectedOrder} 는 서가 안에서 1부터 시작하는 순서이며 중복이 없어야 한다.
 *       왼쪽→오른쪽 순서인지, {@code xPositionCm} 의 기준점이 무엇인지(예: 서가 왼쪽 끝=0)는 확인이 필요하다.
 *       Backend 는 값을 해석·보정하지 않고 그대로 저장한다.</li>
 *   <li>{@code scanSessionId}·{@code detectedOrder} 는 JSON 정수만 허용한다. {@code 42.9}·{@code 1.0}·{@code "1"} 같은
 *       소수·문자열 값은 정수로 바꾸지 않고 400 으로 거부한다.</li>
 *   <li>{@code detectedAt} 은 오프셋 없는 ISO 날짜시간(서울 시각)이다.</li>
 *   <li>인증은 헤더 {@code X-Vision-Api-Key} 이다.</li>
 * </ul>
 */
public record VisionResultRequest(
        @NotNull @JsonDeserialize(using = StrictJsonInteger.AsLong.class) Long scanSessionId,
        @NotEmpty List<@NotNull @Valid ShelfResult> shelves
) {

    /** 서가 1개의 결과. shelfCode 는 서가의 화면 표시 코드(shelf.shelf_code)이다. */
    public record ShelfResult(
            @NotBlank @Size(max = 50) String shelfCode,
            @NotNull List<@NotNull @Valid Observation> observations
    ) {
    }

    /** 서가에서 인식한 도서 1권. 라벨이 없거나 비어 있으면 인식 실패(OCR_FAILED)로 저장된다. */
    public record Observation(
            @NotNull @Min(1) @JsonDeserialize(using = StrictJsonInteger.AsInteger.class) Integer detectedOrder,
            @Size(max = 200) String detectedLabelRaw,
            @Size(max = 255) String detectedTitle,
            BigDecimal xPositionCm,
            LocalDateTime detectedAt
    ) {
    }
}
