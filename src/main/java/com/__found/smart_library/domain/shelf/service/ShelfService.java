package com.__found.smart_library.domain.shelf.service;

import com.__found.smart_library.domain.book.dto.BookSummaryResponse;
import com.__found.smart_library.domain.book.service.BookService;
import com.__found.smart_library.domain.shelf.dto.MapResponse;
import com.__found.smart_library.domain.shelf.dto.ShelfResponse;
import com.__found.smart_library.domain.shelf.dto.ShelfUpdateRequest;
import com.__found.smart_library.domain.shelf.entity.Shelf;
import com.__found.smart_library.domain.shelf.repository.ShelfRepository;
import com.__found.smart_library.domain.shelf.repository.ShelfSpecifications;
import com.__found.smart_library.global.exception.BusinessException;
import com.__found.smart_library.global.exception.ErrorCode;
import com.__found.smart_library.global.response.PageResponse;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShelfService {

    private static final Sort DISPLAY_ORDER =
            Sort.by(Sort.Order.asc("displayOrder").nullsLast(), Sort.Order.asc("id"));

    private final ShelfRepository shelfRepository;
    private final BookService bookService;

    public List<ShelfResponse> list(String zone, Integer floorNo, boolean includeInactive) {
        Specification<Shelf> spec = ShelfSpecifications.all();
        if (zone != null && !zone.isBlank()) {
            spec = spec.and(ShelfSpecifications.zone(zone.trim()));
        }
        if (floorNo != null) {
            spec = spec.and(ShelfSpecifications.floorNo(floorNo));
        }
        if (!includeInactive) {
            spec = spec.and(ShelfSpecifications.activeOnly());
        }
        Map<Long, Long> counts = bookService.countByShelf();
        return shelfRepository.findAll(spec, DISPLAY_ORDER).stream()
                .map(s -> ShelfResponse.of(s, counts.getOrDefault(s.getId(), 0L)))
                .toList();
    }

    public ShelfResponse get(Long shelfId) {
        Shelf shelf = find(shelfId);
        return ShelfResponse.of(shelf, bookService.countByShelf(shelfId));
    }

    public PageResponse<BookSummaryResponse> books(Long shelfId, int page, int size) {
        find(shelfId);
        return bookService.findByShelf(shelfId, page, size);
    }

    public MapResponse map(Integer floorNo) {
        Specification<Shelf> spec = ShelfSpecifications.activeOnly().and(ShelfSpecifications.hasMapCoordinates());
        if (floorNo != null) {
            spec = spec.and(ShelfSpecifications.floorNo(floorNo));
        }
        Map<Long, Long> counts = bookService.countByShelf();
        List<MapResponse.MapShelf> shelves = shelfRepository.findAll(spec, DISPLAY_ORDER).stream()
                .map(s -> MapResponse.MapShelf.of(s, counts.getOrDefault(s.getId(), 0L)))
                .toList();
        return new MapResponse(shelfRepository.findMapFloors(), shelves);
    }

    @Transactional
    public ShelfResponse update(Long shelfId, ShelfUpdateRequest r) {
        Shelf shelf = find(shelfId);
        String markerId = r.markerId() == null || r.markerId().isBlank() ? null : r.markerId().trim();
        if (markerId != null && shelfRepository.existsByMarkerIdAndIdNot(markerId, shelfId)) {
            throw new BusinessException(ErrorCode.SHELF_MARKER_DUPLICATED);
        }
        shelf.replaceConfiguration(r.zone(), r.floorNo(), r.shelfNo(), r.levelNo(), r.callNumberStart(),
                r.callNumberEnd(), markerId, r.mapX(), r.mapY(), r.mapZ(), r.widthCm(), r.heightCm(),
                r.depthCm(), r.rotationDegree(), r.displayOrder(), r.active());
        shelfRepository.flush();
        return ShelfResponse.of(shelf, bookService.countByShelf(shelfId));
    }

    private Shelf find(Long shelfId) {
        return shelfRepository.findById(shelfId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHELF_NOT_FOUND));
    }
}
