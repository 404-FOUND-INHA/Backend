package com.__found.smart_library.domain.observation.controller;

import com.__found.smart_library.domain.observation.dto.VisionResultRequest;
import com.__found.smart_library.domain.observation.dto.VisionResultResponse;
import com.__found.smart_library.domain.observation.service.VisionObservationService;
import com.__found.smart_library.domain.observation.service.VisionObservationService.ReceiveResult;
import com.__found.smart_library.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vision 서버가 호출하는 결과 수신 API. 사서 JWT 가 아니라 Vision 전용 API Key(헤더 X-Vision-Api-Key)로 인증한다
 * (VisionSecurityConfig).
 */
@Slf4j
@RestController
@RequestMapping("/api/vision")
@RequiredArgsConstructor
public class VisionObservationController {

    private final VisionObservationService visionObservationService;

    /** 회차 전체 결과 수신. 최초 수신은 201, 이미 수신한 결과와 같은 재전송은 200(변경 없음), 다른 결과는 409. */
    @PostMapping("/observations")
    @PreAuthorize("hasRole('VISION')")
    public ResponseEntity<ApiResponse<VisionResultResponse>> receive(
            @Valid @RequestBody VisionResultRequest request) {
        long startNanos = System.nanoTime();
        ReceiveResult result = visionObservationService.receive(request);
        // 서비스 트랜잭션은 receive() 가 반환되면 이미 커밋된 뒤이므로 여기서 처리 완료를 기록한다(커밋 실패는 예외로 여기까지 오지 않는다).
        log.info("Vision 결과 처리 완료: sessionId={}, created={}, observations={}, elapsedMs={}",
                result.response().scanSessionId(), result.created(), result.response().observationCount(),
                (System.nanoTime() - startNanos) / 1_000_000);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(result.response()));
    }
}
