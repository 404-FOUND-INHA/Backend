package com.__found.smart_library.domain.book.entity;

import com.__found.smart_library.domain.shelf.entity.Shelf;
import com.__found.smart_library.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "book")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Book extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "book_id")
    private Long id;

    /** 동시 수정 감지용(낙관적 락). */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false, length = 50)
    private String registrationNo;

    @Column(length = 13)
    private String isbn;

    @Column(nullable = false)
    private String title;

    private String author;
    private String publisher;
    private Integer publishedYear;

    @Column(length = 50)
    private String edition;

    @Column(nullable = false, length = 150)
    private String callNumberRaw;

    @Column(nullable = false, length = 50)
    private String classificationNo;

    @Column(nullable = false, length = 50)
    private String bookSymbol;

    @Column(length = 30)
    private String copyNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "expected_shelf_id")
    private Shelf expectedShelf;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_shelf_id")
    private Shelf currentShelf;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LifecycleStatus lifecycleStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LoanStatus loanStatus;

    private LocalDate lastLoanDate;

    @Column(nullable = false)
    private int totalLoanCount;

    private BigDecimal bookWidthCm;

    private LocalDateTime importedAt;

    @Builder
    private Book(String registrationNo, String isbn, String title, String author, String publisher,
                 Integer publishedYear, String edition, String callNumberRaw, String classificationNo,
                 String bookSymbol, String copyNumber, Shelf expectedShelf, Shelf currentShelf,
                 LifecycleStatus lifecycleStatus, LoanStatus loanStatus, BigDecimal bookWidthCm) {
        this.registrationNo = registrationNo;
        this.isbn = isbn;
        this.title = title;
        this.author = author;
        this.publisher = publisher;
        this.publishedYear = publishedYear;
        this.edition = edition;
        this.callNumberRaw = callNumberRaw;
        this.classificationNo = classificationNo;
        this.bookSymbol = bookSymbol;
        this.copyNumber = copyNumber;
        this.expectedShelf = expectedShelf;
        this.currentShelf = currentShelf;
        this.lifecycleStatus = lifecycleStatus;
        this.loanStatus = loanStatus;
        this.bookWidthCm = bookWidthCm;
    }

    /**
     * 장서점검에서 이 도서가 해당 서가에 있는 것으로 확인되었을 때의 반영. 마지막 확인 서가를 관측 서가로 바꾸고,
     * 소재불명(MISSING)이던 도서는 찾은 것이므로 정상(NORMAL)으로 되돌린다. 훼손·제적 상태는 바꾸지 않는다.
     * 정상 배치 서가(expectedShelf)는 건드리지 않는다.
     */
    public void confirmAtShelf(Shelf observedShelf) {
        this.currentShelf = observedShelf;
        if (this.lifecycleStatus == LifecycleStatus.MISSING) {
            this.lifecycleStatus = LifecycleStatus.NORMAL;
        }
    }

    /** PATCH 용 부분 수정: null 로 들어온 값은 기존 값을 유지한다. registrationNo 는 바꿀 수 없다. */
    public void applyPatch(String isbn, String title, String author, String publisher, Integer publishedYear,
                           String edition, String callNumberRaw, String classificationNo, String bookSymbol,
                           String copyNumber, Shelf expectedShelf, Shelf currentShelf,
                           LifecycleStatus lifecycleStatus, LoanStatus loanStatus, BigDecimal bookWidthCm) {
        if (isbn != null) this.isbn = isbn;
        if (title != null) this.title = title;
        if (author != null) this.author = author;
        if (publisher != null) this.publisher = publisher;
        if (publishedYear != null) this.publishedYear = publishedYear;
        if (edition != null) this.edition = edition;
        if (callNumberRaw != null) this.callNumberRaw = callNumberRaw;
        if (classificationNo != null) this.classificationNo = classificationNo;
        if (bookSymbol != null) this.bookSymbol = bookSymbol;
        if (copyNumber != null) this.copyNumber = copyNumber;
        if (expectedShelf != null) this.expectedShelf = expectedShelf;
        if (currentShelf != null) this.currentShelf = currentShelf;
        if (lifecycleStatus != null) this.lifecycleStatus = lifecycleStatus;
        if (loanStatus != null) this.loanStatus = loanStatus;
        if (bookWidthCm != null) this.bookWidthCm = bookWidthCm;
    }
}
