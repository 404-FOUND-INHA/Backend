package com.__found.smart_library.domain.review.dto;

import com.__found.smart_library.domain.book.entity.Book;

/** 검토 화면에서 도서를 알아볼 수 있는 최소 정보. */
public record BookBrief(Long bookId, String registrationNo, String title, String callNumber) {

    public static BookBrief from(Book book) {
        return new BookBrief(book.getId(), book.getRegistrationNo(), book.getTitle(), book.getCallNumberRaw());
    }
}
