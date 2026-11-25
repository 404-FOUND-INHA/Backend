package com.__found.smart_library.domain.book.repository;

/** 점검 대상 서가에 있어야 하는 도서와 그 서가. */
public record ExpectedBook(Long bookId, Long shelfId) {
}
