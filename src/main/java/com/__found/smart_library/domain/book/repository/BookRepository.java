package com.__found.smart_library.domain.book.repository;

import com.__found.smart_library.domain.book.entity.Book;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** 도서 매칭 1단계: 분류기호·도서기호가 모두 포함된 도서(쌍의 일치는 호출 측에서 확인한다). */
    @Query("""
            select new com.__found.smart_library.domain.book.repository.BookMatchInfo(
                b.id, b.registrationNo, b.title, b.classificationNo, b.bookSymbol, b.copyNumber,
                b.expectedShelf.id, b.lifecycleStatus)
            from Book b
            where b.classificationNo in :classificationNos and b.bookSymbol in :bookSymbols
            """)
    List<BookMatchInfo> findMatchInfosByClassificationNoAndBookSymbol(
            @Param("classificationNos") Collection<String> classificationNos,
            @Param("bookSymbols") Collection<String> bookSymbols);

    /** 도서 매칭 2단계(후보 탐색): 도서기호가 일치하는 도서. */
    @Query("""
            select new com.__found.smart_library.domain.book.repository.BookMatchInfo(
                b.id, b.registrationNo, b.title, b.classificationNo, b.bookSymbol, b.copyNumber,
                b.expectedShelf.id, b.lifecycleStatus)
            from Book b
            where b.bookSymbol in :bookSymbols
            """)
    List<BookMatchInfo> findMatchInfosByBookSymbol(@Param("bookSymbols") Collection<String> bookSymbols);

    /** 도서 매칭 3단계(후보 탐색): 분류기호가 일치하는 도서. */
    @Query("""
            select new com.__found.smart_library.domain.book.repository.BookMatchInfo(
                b.id, b.registrationNo, b.title, b.classificationNo, b.bookSymbol, b.copyNumber,
                b.expectedShelf.id, b.lifecycleStatus)
            from Book b
            where b.classificationNo in :classificationNos
            """)
    List<BookMatchInfo> findMatchInfosByClassificationNo(@Param("classificationNos") Collection<String> classificationNos);

    interface ShelfBookCount {
        Long getShelfId();

        long getBookCount();
    }
}
