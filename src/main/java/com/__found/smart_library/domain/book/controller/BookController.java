package com.__found.smart_library.domain.book.controller;

import com.__found.smart_library.domain.book.dto.BookCreateRequest;
import com.__found.smart_library.domain.book.dto.BookHistoryResponse;
import com.__found.smart_library.domain.book.dto.BookResponse;
import com.__found.smart_library.domain.book.dto.BookSearchCondition;
import com.__found.smart_library.domain.book.dto.BookSummaryResponse;
import com.__found.smart_library.domain.book.dto.BookUpdateRequest;
import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.book.entity.LoanStatus;
import com.__found.smart_library.domain.book.service.BookService;
import com.__found.smart_library.global.response.ApiResponse;
import com.__found.smart_library.global.response.PageResponse;
import com.__found.smart_library.global.security.AuthClaims;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
public class BookController {

    private final BookService bookService;

    @GetMapping
    public ApiResponse<PageResponse<BookSummaryResponse>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) LifecycleStatus lifecycleStatus,
            @RequestParam(required = false) LoanStatus loanStatus,
            @RequestParam(required = false) Long shelfId,
            @RequestParam(required = false) String classificationNo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        BookSearchCondition condition =
                new BookSearchCondition(keyword, lifecycleStatus, loanStatus, shelfId, classificationNo);
        return ApiResponse.ok(bookService.search(condition, page, size));
    }

    @GetMapping("/{bookId}")
    public ApiResponse<BookResponse> get(@PathVariable Long bookId) {
        return ApiResponse.ok(bookService.get(bookId));
    }

    @PostMapping
    @PreAuthorize("hasRole('LIBRARIAN')")
    public ResponseEntity<ApiResponse<BookResponse>> create(
            @Valid @RequestBody BookCreateRequest request, @AuthenticationPrincipal Jwt jwt) {
        BookResponse response = bookService.create(request, AuthClaims.userId(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PatchMapping("/{bookId}")
    public ApiResponse<BookResponse> update(
            @PathVariable Long bookId, @Valid @RequestBody BookUpdateRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(bookService.update(bookId, request, AuthClaims.userId(jwt)));
    }

    @GetMapping("/{bookId}/histories")
    public ApiResponse<PageResponse<BookHistoryResponse>> histories(
            @PathVariable Long bookId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(bookService.histories(bookId, page, size));
    }
}
