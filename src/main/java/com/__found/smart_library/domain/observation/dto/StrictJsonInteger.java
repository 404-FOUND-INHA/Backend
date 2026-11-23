package com.__found.smart_library.domain.observation.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * JSON 정수 토큰만 받는 역직렬화기. Jackson 기본 동작은 {@code 42.9} 를 42 로, {@code "42"} 를 42 로 바꿔 받는데,
 * Vision 요청의 식별자·순서 값이 조용히 다른 값으로 처리되지 않도록 Vision 요청 DTO 의 해당 필드에만 적용한다
 * (전역 Jackson 설정은 바꾸지 않는다). 정수가 아니거나 범위를 벗어나면 역직렬화 예외 → 400 이다.
 */
public final class StrictJsonInteger {

    private StrictJsonInteger() {
    }

    public static final class AsLong extends ValueDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser p, DeserializationContext ctxt) {
            if (p.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return (Long) ctxt.handleUnexpectedToken(Long.class, p);
            }
            return p.getLongValue();
        }
    }

    public static final class AsInteger extends ValueDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser p, DeserializationContext ctxt) {
            if (p.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return (Integer) ctxt.handleUnexpectedToken(Integer.class, p);
            }
            return p.getIntValue();
        }
    }
}
