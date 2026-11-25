package com.__found.smart_library.global.config;

import com.__found.smart_library.domain.evaluation.metric.BackendMetricProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Backend 평가 지표 규칙 설정(app.evaluation.metrics.*)을 등록한다. */
@Configuration
@EnableConfigurationProperties(BackendMetricProperties.class)
public class EvaluationMetricConfig {
}
