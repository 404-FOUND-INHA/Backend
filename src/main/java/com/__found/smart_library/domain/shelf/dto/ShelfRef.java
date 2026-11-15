package com.__found.smart_library.domain.shelf.dto;

import com.__found.smart_library.domain.shelf.entity.Shelf;

/** 다른 응답에 끼워 넣는 서가 요약. */
public record ShelfRef(Long id, String shelfCode) {

    public static ShelfRef from(Shelf shelf) {
        return shelf == null ? null : new ShelfRef(shelf.getId(), shelf.getShelfCode());
    }
}
