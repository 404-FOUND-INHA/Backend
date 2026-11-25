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

    /** 평가 지표 계산용: 도서의 대출 요약과 청구기호. */
    @Query("""
            select new com.__found.smart_library.domain.book.repository.BookMetricSource(
                b.id, b.totalLoanCount, b.lastLoanDate, b.classificationNo, b.bookSymbol)
            from Book b where b.id in :bookIds
            """)
    List<BookMetricSource> findMetricSources(@Param("bookIds") Collection<Long> bookIds);

    /**
     * 평가 지표 계산용: 청구기호별 복본 수(제적 제외). 분류기호와 도서기호를 각각 IN 으로 거르므로
     * 요청하지 않은 조합도 섞여 나올 수 있어 호출 측에서 필요한 쌍만 고른다.
     */
    @Query("""
            select new com.__found.smart_library.domain.book.repository.CopyCount(
                b.classificationNo, b.bookSymbol, count(b))
            from Book b
            where b.classificationNo in :classificationNos and b.bookSymbol in :bookSymbols
              and b.lifecycleStatus <> com.__found.smart_library.domain.book.entity.LifecycleStatus.DISPOSED
            group by b.classificationNo, b.bookSymbol
            """)
    List<CopyCount> countCopies(@Param("classificationNos") Collection<String> classificationNos,
                                @Param("bookSymbols") Collection<String> bookSymbols);

    /**
     * 소재불명 후보 계산용: 주어진 서가가 정상 배치 서가이면서 점검 대상인 도서(제적·대출 중·분실 처리 제외).
     */
    @Query("""
            select new com.__found.smart_library.domain.book.repository.ExpectedBook(b.id, b.expectedShelf.id)
            from Book b
            where b.expectedShelf.id in :shelfIds
              and b.lifecycleStatus <> com.__found.smart_library.domain.book.entity.LifecycleStatus.DISPOSED
              and b.loanStatus not in (com.__found.smart_library.domain.book.entity.LoanStatus.ON_LOAN,
                                       com.__found.smart_library.domain.book.entity.LoanStatus.LOST)
            """)
    List<ExpectedBook> findExpectedOnShelves(@Param("shelfIds") Collection<Long> shelfIds);

    interface ShelfBookCount {
        Long getShelfId();

        long getBookCount();
    }
}
