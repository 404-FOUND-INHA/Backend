package com.__found.smart_library.global.config;

import com.__found.smart_library.domain.evaluation.jev.JevClient;
import com.__found.smart_library.domain.evaluation.jev.JevProperties;
import com.__found.smart_library.domain.evaluation.jev.TypeSafeJevClient;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** JEV(System One) 호출용 RestClient. API Key 가 없으면 Authorization 헤더 없이 만들어지며, 서비스가 호출 전에 거부한다. */
@Configuration
@EnableConfigurationProperties(JevProperties.class)
public class JevClientConfig {

    @Bean
    public JevClient jevClient(JevProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
        factory.setReadTimeout(properties.readTimeout());
        RestClient.Builder configured = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory);
        if (properties.enabled()) {
            configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
        }
        return new TypeSafeJevClient(configured.build(), properties.maxAttempts(), properties.retryBackoff(),
                duration -> Thread.sleep(duration.toMillis()));
    }
}
