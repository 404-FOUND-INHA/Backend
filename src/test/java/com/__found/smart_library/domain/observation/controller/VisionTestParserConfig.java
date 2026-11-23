package com.__found.smart_library.domain.observation.controller;

import com.__found.smart_library.domain.observation.service.CallNumberParser;
import com.__found.smart_library.domain.observation.service.ParsedCallNumber;
import java.util.Optional;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 테스트 전용 파서. 운영에는 파서 구현이 없고 실제 구현은 Vision 담당자가 제공하므로, 수신 API 의 성공 경로를 검증하기 위해
 * 테스트에서만 "분류기호|도서기호|복본기호" 형식의 라벨을 해석하는 파서를 등록한다. 라벨 "BOOM" 은 파서 내부 오류를, "OVER_*" 는 저장 컬럼 길이를 넘는 결과를 만드는 파서를 흉내 낸다.
 */
@TestConfiguration
public class VisionTestParserConfig {

    @Bean
    CallNumberParser testCallNumberParser() {
        return raw -> {
            if ("BOOM".equals(raw)) {
                throw new IllegalStateException("parser failure (test)");
            }
            // 저장 컬럼 길이를 넘는 결과를 만드는 파서(계약 위반)를 흉내 낸다.
            if ("OVER_CLASS".equals(raw)) {
                return Optional.of(new ParsedCallNumber("a".repeat(51), "b", null));
            }
            if ("OVER_SYMBOL".equals(raw)) {
                return Optional.of(new ParsedCallNumber("a", "b".repeat(51), null));
            }
            if ("OVER_COPY".equals(raw)) {
                return Optional.of(new ParsedCallNumber("a", "b", "c".repeat(31)));
            }
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new ParsedCallNumber(parts[0], parts[1], parts[2]));
        };
    }
}
