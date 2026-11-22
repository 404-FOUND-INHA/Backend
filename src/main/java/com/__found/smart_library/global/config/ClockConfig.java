package com.__found.smart_library.global.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * "오늘" 판단에 쓰는 기준 시계. JVM 기본 시간대에 의존하지 않도록 서울 시간대로 고정하고,
 * 테스트에서는 고정 Clock 으로 교체할 수 있게 빈으로 노출한다.
 */
@Configuration
public class ClockConfig {

    public static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(SERVICE_ZONE);
    }
}
