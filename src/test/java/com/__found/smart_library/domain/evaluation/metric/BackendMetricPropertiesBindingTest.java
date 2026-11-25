package com.__found.smart_library.domain.evaluation.metric;

import static org.assertj.core.api.Assertions.assertThat;

import com.__found.smart_library.domain.condition.entity.ConditionSeverity;
import com.__found.smart_library.domain.condition.entity.ConditionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** application.yml(환경변수) 값으로 규칙을 바꿀 수 있는지, 지정하지 않은 값은 기본값이 유지되는지 확인한다. */
class BackendMetricPropertiesBindingTest {

    @Configuration
    @EnableConfigurationProperties(BackendMetricProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    @DisplayName("설정이 없으면 기본 규칙을 쓴다")
    void defaults() {
        runner.run(ctx -> {
            BackendMetricProperties p = ctx.getBean(BackendMetricProperties.class);
            assertThat(p.getDemand().getWindowYears()).isEqualTo(3);
            assertThat(p.getDuplication().getSaturationCopies()).isEqualTo(4);
            assertThat(p.getCondition().getSeverityScore().get(ConditionSeverity.HIGH)).isEqualTo(90);
        });
    }

    @Test
    @DisplayName("일부만 바꾸면 나머지는 기본값을 유지한다 (enum 키 맵 포함)")
    void partialOverride() {
        runner.withPropertyValues(
                "app.evaluation.metrics.demand.window-years=5",
                "app.evaluation.metrics.condition.severity-score.high=100",
                "app.evaluation.metrics.condition.type-factor.stain=0.5",
                "app.evaluation.metrics.condition.default-severity=low",
                "app.evaluation.metrics.duplication.saturation-copies=3").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            BackendMetricProperties p = ctx.getBean(BackendMetricProperties.class);
            assertThat(p.getDemand().getWindowYears()).isEqualTo(5);
            assertThat(p.getDemand().getLoanSaturation()).isEqualTo(5);
            assertThat(p.getCondition().getSeverityScore().get(ConditionSeverity.HIGH)).isEqualTo(100);
            assertThat(p.getCondition().getSeverityScore().get(ConditionSeverity.LOW)).isEqualTo(30);
            assertThat(p.getCondition().getTypeFactor().get(ConditionType.STAIN)).isEqualByComparingTo("0.5");
            assertThat(p.getCondition().getTypeFactor().get(ConditionType.TORN)).isEqualByComparingTo("0.8");
            assertThat(p.getCondition().getDefaultSeverity()).isEqualTo(ConditionSeverity.LOW);
            assertThat(p.getDuplication().getSaturationCopies()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("잘못된 값이면 컨텍스트(애플리케이션) 기동이 실패한다")
    void invalidValue_failsStartup() {
        runner.withPropertyValues("app.evaluation.metrics.demand.window-years=0").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseMessage(
                    "app.evaluation.metrics 설정 오류: demand.window-years 는 1 이상이어야 합니다.");
        });
    }
}
