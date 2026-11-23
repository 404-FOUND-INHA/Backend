package com.__found.smart_library.domain.observation.service;

/**
 * 매칭 요청 1건. detectedShelfId 는 관측된 서가이며 후보 정렬에만 쓰이고 자동 매칭 여부에는 영향을 주지 않는다(null 가능).
 */
public record MatchRequest(ParsedCallNumber parsed, Long detectedShelfId) {
}
