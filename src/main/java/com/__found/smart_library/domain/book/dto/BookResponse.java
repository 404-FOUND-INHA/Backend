package com.__found.smart_library.domain.book.dto;

import com.__found.smart_library.domain.book.entity.Book;
import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.book.entity.LoanStatus;
import com.__found.smart_library.domain.shelf.dto.ShelfRef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record BookResponse(
        Long id,
        String registrationNo,
        String isbn,
        String title,
        String author,
        String publisher,
        Integer publishedYear,
        String edition,
        String callNumberRaw,
        String classificationNo,
        String bookSymbol,
        String copyNumber,
        ShelfRef expectedShelf,
        ShelfRef currentShelf,
        LifecycleStatus lifecycleStatus,
        LoanStatus loanStatus,
        LocalDate lastLoanDate,
        int totalLoanCount,
        BigDecimal bookWidthCm,
        LocalDateTime importedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static BookResponse from(Book b) {
        return new BookResponse(b.getId(), b.getRegistrationNo(), b.getIsbn(), b.getTitle(), b.getAuthor(),
                b.getPublisher(), b.getPublishedYear(), b.getEdition(), b.getCallNumberRaw(),
                b.getClassificationNo(), b.getBookSymbol(), b.getCopyNumber(),
                ShelfRef.from(b.getExpectedShelf()), ShelfRef.from(b.getCurrentShelf()),
                b.getLifecycleStatus(), b.getLoanStatus(), b.getLastLoanDate(), b.getTotalLoanCount(),
                b.getBookWidthCm(), b.getImportedAt(), b.getCreatedAt(), b.getUpdatedAt());
    }
}
