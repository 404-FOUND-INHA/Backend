package com.__found.smart_library.domain.book.service;

import com.__found.smart_library.domain.audit.entity.AuditLog;
import com.__found.smart_library.domain.audit.repository.AuditLogRepository;
import com.__found.smart_library.domain.book.dto.BookCreateRequest;
import com.__found.smart_library.domain.book.dto.BookHistoryResponse;
import com.__found.smart_library.domain.book.dto.BookResponse;
import com.__found.smart_library.domain.book.dto.BookSearchCondition;
import com.__found.smart_library.domain.book.dto.BookSummaryResponse;
import com.__found.smart_library.domain.book.dto.BookUpdateRequest;
import com.__found.smart_library.domain.book.entity.Book;
import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.book.entity.LoanStatus;
import com.__found.smart_library.domain.book.repository.BookRepository;
import com.__found.smart_library.domain.book.repository.BookSpecifications;
import com.__found.smart_library.domain.shelf.entity.Shelf;
import com.__found.smart_library.domain.shelf.repository.ShelfRepository;
import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import com.__found.smart_library.global.exception.BusinessException;
import com.__found.smart_library.global.exception.ErrorCode;
import com.__found.smart_library.global.response.PageResponse;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> STATUS_KEYS = Set.of("lifecycleStatus", "loanStatus");

    private final BookRepository bookRepository;
    private final ShelfRepository shelfRepository;
    private final AuditLogRepository auditLogRepository;
    private final AppUserRepository userRepository;

    public PageResponse<BookSummaryResponse> search(BookSearchCondition cond, int page, int size) {
        Specification<Book> spec = BookSpecifications.all();
        if (cond.keyword() != null && !cond.keyword().isBlank()) {
            spec = spec.and(BookSpecifications.keyword(cond.keyword()));
        }
        if (cond.lifecycleStatus() != null) {
            spec = spec.and(BookSpecifications.lifecycleStatus(cond.lifecycleStatus()));
        }
        if (cond.loanStatus() != null) {
            spec = spec.and(BookSpecifications.loanStatus(cond.loanStatus()));
        }
        if (cond.shelfId() != null) {
            spec = spec.and(BookSpecifications.currentShelfId(cond.shelfId()));
        }
        if (cond.classificationNo() != null && !cond.classificationNo().isBlank()) {
            spec = spec.and(BookSpecifications.classificationPrefix(cond.classificationNo()));
        }
        return PageResponse.from(bookRepository.findAll(spec, pageRequest(page, size)).map(BookSummaryResponse::from));
    }

    /** 서가에 현재 배치된(current_shelf) 도서 목록. 서가 존재 여부는 호출하는 쪽에서 확인한다. */
    public PageResponse<BookSummaryResponse> findByShelf(Long shelfId, int page, int size) {
        Page<Book> books = bookRepository.findAll(BookSpecifications.currentShelfId(shelfId), pageRequest(page, size));
        return PageResponse.from(books.map(BookSummaryResponse::from));
    }

    public BookResponse get(Long bookId) {
        return BookResponse.from(find(bookId));
    }

    public Map<Long, Long> countByShelf() {
        return bookRepository.countGroupedByCurrentShelf().stream()
                .collect(Collectors.toMap(BookRepository.ShelfBookCount::getShelfId, BookRepository.ShelfBookCount::getBookCount));
    }

    public long countByShelf(Long shelfId) {
        return bookRepository.countByCurrentShelfId(shelfId);
    }

    @Transactional
    public BookResponse create(BookCreateRequest request, Long actorId) {
        String registrationNo = request.registrationNo().trim();
        if (bookRepository.existsByRegistrationNo(registrationNo)) {
            throw new BusinessException(ErrorCode.BOOK_REGISTRATION_NO_DUPLICATED);
        }
        Book book = bookRepository.save(Book.builder()
                .registrationNo(registrationNo)
                .isbn(request.isbn())
                .title(request.title().trim())
                .author(request.author())
                .publisher(request.publisher())
                .publishedYear(request.publishedYear())
                .edition(request.edition())
                .callNumberRaw(request.callNumberRaw().trim())
                .classificationNo(request.classificationNo().trim())
                .bookSymbol(request.bookSymbol().trim())
                .copyNumber(request.copyNumber())
                .expectedShelf(findShelf(request.expectedShelfId()))
                .currentShelf(findShelf(request.currentShelfId()))
                .lifecycleStatus(request.lifecycleStatus() != null ? request.lifecycleStatus() : LifecycleStatus.NORMAL)
                .loanStatus(request.loanStatus() != null ? request.loanStatus() : LoanStatus.AVAILABLE)
                .bookWidthCm(request.bookWidthCm())
                .build());

        Map<String, Object> after = snapshot(book);
        after.put("registrationNo", book.getRegistrationNo());
        record(actorId, book.getId(), AuditLog.ACTION_CREATE, null, after);
        return BookResponse.from(book);
    }

    @Transactional
    public BookResponse update(Long bookId, BookUpdateRequest request, Long actorId) {
        Book book = find(bookId);
        Map<String, Object> beforeSnapshot = snapshot(book);

        book.applyPatch(request.isbn(), trim(request.title()), request.author(), request.publisher(),
                request.publishedYear(), request.edition(), trim(request.callNumberRaw()),
                trim(request.classificationNo()), trim(request.bookSymbol()), request.copyNumber(),
                findShelf(request.expectedShelfId()), findShelf(request.currentShelfId()),
                request.lifecycleStatus(), request.loanStatus(), request.bookWidthCm());

        Map<String, Object> afterSnapshot = snapshot(book);
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        afterSnapshot.forEach((key, value) -> {
            if (!Objects.equals(beforeSnapshot.get(key), value)) {
                before.put(key, beforeSnapshot.get(key));
                after.put(key, value);
            }
        });
        if (!after.isEmpty()) {
            String action = after.keySet().stream().anyMatch(STATUS_KEYS::contains)
                    ? AuditLog.ACTION_STATUS_CHANGE : AuditLog.ACTION_UPDATE;
            record(actorId, bookId, action, before, after);
        }
        return BookResponse.from(book);
    }

    public PageResponse<BookHistoryResponse> histories(Long bookId, int page, int size) {
        if (!bookRepository.existsById(bookId)) {
            throw new BusinessException(ErrorCode.BOOK_NOT_FOUND);
        }
        PageRequest pageable = PageRequest.of(Math.max(page, 0), clampSize(size), Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<AuditLog> logs = auditLogRepository.findByEntityTypeAndEntityId(AuditLog.ENTITY_BOOK, bookId, pageable);

        Set<Long> actorIds = logs.stream().map(AuditLog::getActorId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> actorNames = new HashMap<>();
        for (AppUser user : userRepository.findAllById(actorIds)) {
            actorNames.put(user.getId(), user.getName());
        }
        return PageResponse.from(logs.map(log -> BookHistoryResponse.of(log, actorNames.get(log.getActorId()))));
    }

    private Book find(Long bookId) {
        return bookRepository.findById(bookId).orElseThrow(() -> new BusinessException(ErrorCode.BOOK_NOT_FOUND));
    }

    private Shelf findShelf(Long shelfId) {
        if (shelfId == null) {
            return null;
        }
        return shelfRepository.findById(shelfId).orElseThrow(() -> new BusinessException(ErrorCode.SHELF_NOT_FOUND));
    }

    private void record(Long actorId, Long bookId, String action, Map<String, Object> before, Map<String, Object> after) {
        auditLogRepository.save(AuditLog.builder()
                .actorId(actorId)
                .entityType(AuditLog.ENTITY_BOOK)
                .entityId(bookId)
                .action(action)
                .beforeData(before)
                .afterData(after)
                .build());
    }

    /** 변경 감지용 스냅샷. 이력에 남는 필드 이름이 곧 이 키들이다. */
    private Map<String, Object> snapshot(Book b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("isbn", b.getIsbn());
        m.put("title", b.getTitle());
        m.put("author", b.getAuthor());
        m.put("publisher", b.getPublisher());
        m.put("publishedYear", b.getPublishedYear());
        m.put("edition", b.getEdition());
        m.put("callNumberRaw", b.getCallNumberRaw());
        m.put("classificationNo", b.getClassificationNo());
        m.put("bookSymbol", b.getBookSymbol());
        m.put("copyNumber", b.getCopyNumber());
        m.put("expectedShelfId", b.getExpectedShelf() == null ? null : b.getExpectedShelf().getId());
        m.put("expectedShelfCode", b.getExpectedShelf() == null ? null : b.getExpectedShelf().getShelfCode());
        m.put("currentShelfId", b.getCurrentShelf() == null ? null : b.getCurrentShelf().getId());
        m.put("currentShelfCode", b.getCurrentShelf() == null ? null : b.getCurrentShelf().getShelfCode());
        m.put("lifecycleStatus", b.getLifecycleStatus().name());
        m.put("loanStatus", b.getLoanStatus().name());
        m.put("bookWidthCm", normalize(b.getBookWidthCm()));
        return m;
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static PageRequest pageRequest(int page, int size) {
        return PageRequest.of(Math.max(page, 0), clampSize(size), Sort.by("title", "id"));
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
