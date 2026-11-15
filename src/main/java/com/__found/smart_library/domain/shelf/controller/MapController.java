package com.__found.smart_library.domain.shelf.controller;

import com.__found.smart_library.domain.shelf.dto.MapResponse;
import com.__found.smart_library.domain.shelf.service.ShelfService;
import com.__found.smart_library.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/maps")
@RequiredArgsConstructor
public class MapController {

    private final ShelfService shelfService;

    /** 서가 지도 데이터. floorNo 를 주면 해당 층만, 생략하면 전체 층을 내려준다. */
    @GetMapping
    public ApiResponse<MapResponse> map(@RequestParam(required = false) Integer floorNo) {
        return ApiResponse.ok(shelfService.map(floorNo));
    }
}
