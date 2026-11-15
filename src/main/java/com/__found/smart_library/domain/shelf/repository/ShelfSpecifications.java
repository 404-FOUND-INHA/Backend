package com.__found.smart_library.domain.shelf.repository;

import com.__found.smart_library.domain.shelf.entity.Shelf;
import org.springframework.data.jpa.domain.Specification;

public final class ShelfSpecifications {

    private ShelfSpecifications() {
    }

    public static Specification<Shelf> all() {
        return (root, query, cb) -> cb.conjunction();
    }

    public static Specification<Shelf> zone(String zone) {
        return (root, query, cb) -> cb.equal(root.get("zone"), zone);
    }

    public static Specification<Shelf> floorNo(Integer floorNo) {
        return (root, query, cb) -> cb.equal(root.get("floorNo"), floorNo);
    }

    public static Specification<Shelf> activeOnly() {
        return (root, query, cb) -> cb.isTrue(root.get("active"));
    }

    public static Specification<Shelf> hasMapCoordinates() {
        return (root, query, cb) -> cb.and(cb.isNotNull(root.get("mapX")), cb.isNotNull(root.get("mapY")));
    }
}
