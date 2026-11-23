package com.__found.smart_library.domain.observation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.__found.smart_library.global.exception.BusinessException;
import com.__found.smart_library.global.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * 운영과 같은 구성(파서 구현 빈 없음)에서도 앱은 정상 기동하고, 분석만 성공한 것처럼 처리하지 않고 503 으로 실패한다.
 */
@SpringBootTest
class ObservationAnalyzerParserAbsentTest {

    @Autowired ApplicationContext context;
    @Autowired ObservationAnalyzer analyzer;

    @Test
    @DisplayName("기본 구성에는 CallNumberParser 빈이 없어도 컨텍스트가 정상 기동하고 분석 호출은 503 으로 실패한다")
    void analyze_defaultContextWithoutParser_throwsServiceUnavailable() {
        assertThat(context.getBeanProvider(CallNumberParser.class).getIfAvailable()).isNull();

        assertThatThrownBy(() -> analyzer.analyze(List.of(new AnalysisInput("라벨", 1L))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SCAN_PARSER_UNAVAILABLE));
    }
}
