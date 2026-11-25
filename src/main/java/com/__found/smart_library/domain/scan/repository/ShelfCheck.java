package com.__found.smart_library.domain.scan.repository;

import java.time.LocalDateTime;

/** 어떤 서가를 점검한 확정 회차와 그 확정 시각. */
public record ShelfCheck(Long shelfId, Long sessionId, LocalDateTime finalizedAt) {
}
