package com.__found.smart_library.domain.scan.repository;

/** 점검에서 확인된 도서와 그 도서가 관측된 서가. */
public record BookFinding(Long bookId, Long shelfId) {
}
