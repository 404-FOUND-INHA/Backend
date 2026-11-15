package com.__found.smart_library.domain.shelf.controller;

import com.__found.smart_library.domain.book.dto.BookSummaryResponse;
import com.__found.smart_library.domain.shelf.dto.ShelfResponse;
import com.__found.smart_library.domain.shelf.dto.ShelfUpdateRequest;
import com.__found.smart_library.domain.shelf.service.ShelfService;
import com.__found.smart_library.global.response.ApiResponse;
import com.__found.smart_library.global.response.PageResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shelves")
@RequiredArgsConstructor
public class ShelfController {

    private final ShelfService shelfService;

    @GetMapping
    public ApiResponse<List<ShelfResponse>> list(
            @RequestParam(required = false) String zone,
            @RequestParam(required = false) Integer floorNo,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.ok(shelfService.list(zone, floorNo, includeInactive));
    }

    @GetMapping("/{shelfId}")
    public ApiResponse<ShelfResponse> get(@PathVariable Long shelfId) {
        return ApiResponse.ok(shelfService.get(shelfId));
    }

    @GetMapping("/{shelfId}/books")
    public ApiResponse<PageResponse<BookSummaryResponse>> books(
            @PathVariable Long shelfId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(shelfService.books(shelfId, page, size));
    }

    @PutMapping("/{shelfId}")
    @PreAuthorize("hasRole('LIBRARIAN')")
    public ApiResponse<ShelfResponse> update(
            @PathVariable Long shelfId, @Valid @RequestBody ShelfUpdateRequest request) {
        return ApiResponse.ok(shelfService.update(shelfId, request));
    }
}
