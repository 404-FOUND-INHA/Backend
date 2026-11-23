package com.__found.smart_library.global.config;

import com.__found.smart_library.global.security.RestAccessDeniedHandler;
import com.__found.smart_library.global.security.RestAuthenticationEntryPoint;
import com.__found.smart_library.global.security.VisionApiKeyFilter;
import com.__found.smart_library.global.security.VisionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Vision 서버 전용 보안 체인. /api/vision/** 요청만 이 체인이 처리하고(JWT 는 사용하지 않는다),
 * 그 밖의 요청은 기존 JWT 체인(SecurityConfig)이 그대로 처리한다.
 * 허용하는 요청은 POST /api/vision/observations 하나뿐이며 나머지 /api/vision/** 는 모두 거부한다.
 */
@Configuration
@EnableConfigurationProperties(VisionProperties.class)
public class VisionSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain visionSecurityFilterChain(
            HttpSecurity http,
            VisionProperties properties,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .securityMatcher("/api/vision/**")
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 서버 간 호출이므로 CORS 는 열지 않는다. 필터는 빈이 아니라 여기서만 생성한다(전역 서블릿 필터 방지).
                .addFilterBefore(new VisionApiKeyFilter(properties), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/vision/observations")
                        .hasRole(VisionApiKeyFilter.ROLE)
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }
}
