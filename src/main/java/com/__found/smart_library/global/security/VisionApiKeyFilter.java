package com.__found.smart_library.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Vision 서버 호출을 API Key 로 인증한다. Vision 전용 SecurityFilterChain 안에서만 쓰며,
 * 일치하면 ROLE_VISION 인증을 설정하고 그렇지 않으면 아무것도 하지 않는다(거부는 인가 단계의 몫).
 *
 * <p>이 필터는 빈으로 등록하지 않는다. 필터 빈은 Spring Boot 가 모든 요청의 서블릿 필터로 자동 등록해
 * Vision 경로 밖의 요청에도 실행되기 때문이다.
 * 키 값은 로그·예외 메시지에 남기지 않는다.
 */
public class VisionApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Vision-Api-Key";
    public static final String ROLE = "VISION";

    /** 설정된 키의 SHA-256. 키가 설정되지 않았으면 null 이며 이때는 어떤 요청도 인증하지 않는다. */
    private final byte[] expectedDigest;

    public VisionApiKeyFilter(VisionProperties properties) {
        this.expectedDigest = properties.enabled() ? sha256(properties.apiKey()) : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (matches(provided)) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    "vision-service", null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE))));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    /** 길이 차이로 정보가 새지 않도록 해시한 값을 상수 시간으로 비교한다. */
    private boolean matches(String provided) {
        if (expectedDigest == null || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedDigest, sha256(provided));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다.", e);
        }
    }
}
