package com.__found.smart_library.domain.book.repository;

import com.__found.smart_library.domain.book.entity.Book;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface BookRepository extends JpaRepository<Book, Long>, JpaSpecificationExecutor<Book> {

    /** 서가 정보를 함께 가져와 목록 조회 시 N+1 을 막는다. */
    @Override
    @EntityGraph(attributePaths = {"expectedShelf", "currentShelf"})
    Page<Book> findAll(Specification<Book> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"expectedShelf", "currentShelf"})
    Optional<Book> findById(Long id);

    boolean existsByRegistrationNo(String registrationNo);

    long countByCurrentShelfId(Long shelfId);

    @Query("""
            select b.currentShelf.id as shelfId, count(b) as bookCount
            from Book b
            where b.currentShelf is not null
            group by b.currentShelf.id
            """)
    List<ShelfBookCount> countGroupedByCurrentShelf();

    interface ShelfBookCount {
        Long getShelfId();

        long getBookCount();
    }
}
