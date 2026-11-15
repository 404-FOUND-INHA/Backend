package com.__found.smart_library.domain.book.dto;

import com.__found.smart_library.domain.book.entity.Book;
import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.book.entity.LoanStatus;
import com.__found.smart_library.domain.shelf.dto.ShelfRef;

public record BookSummaryResponse(
        Long id,
        String registrationNo,
        String isbn,
        String title,
        String author,
        String publisher,
        Integer publishedYear,
        String callNumberRaw,
        LifecycleStatus lifecycleStatus,
        LoanStatus loanStatus,
        ShelfRef currentShelf
) {

    public static BookSummaryResponse from(Book b) {
        return new BookSummaryResponse(b.getId(), b.getRegistrationNo(), b.getIsbn(), b.getTitle(), b.getAuthor(),
                b.getPublisher(), b.getPublishedYear(), b.getCallNumberRaw(), b.getLifecycleStatus(),
                b.getLoanStatus(), ShelfRef.from(b.getCurrentShelf()));
    }
}
