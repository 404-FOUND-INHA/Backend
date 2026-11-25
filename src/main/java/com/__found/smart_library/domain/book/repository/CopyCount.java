package com.__found.smart_library.domain.book.repository;

/** 같은 청구기호(분류기호 + 도서기호)의 도서 수. */
public record CopyCount(String classificationNo, String bookSymbol, long count) {
}
