package com.__found.smart_library.domain.book.repository;

import com.__found.smart_library.domain.book.entity.Book;
import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.book.entity.LoanStatus;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

public final class BookSpecifications {

    private static final char ESCAPE = '\\';

    private BookSpecifications() {
    }

    public static Specification<Book> all() {
        return (root, query, cb) -> cb.conjunction();
    }

    /** 서명·저자·출판사·ISBN·등록번호·청구기호 중 하나라도 키워드를 포함하면 일치(대소문자 무시). */
    public static Specification<Book> keyword(String keyword) {
        String pattern = "%" + escapeLike(keyword.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), pattern, ESCAPE),
                cb.like(cb.lower(root.get("author")), pattern, ESCAPE),
                cb.like(cb.lower(root.get("publisher")), pattern, ESCAPE),
                cb.like(cb.lower(root.get("isbn")), pattern, ESCAPE),
                cb.like(cb.lower(root.get("registrationNo")), pattern, ESCAPE),
                cb.like(cb.lower(root.get("callNumberRaw")), pattern, ESCAPE));
    }

    public static Specification<Book> lifecycleStatus(LifecycleStatus status) {
        return (root, query, cb) -> cb.equal(root.get("lifecycleStatus"), status);
    }

    public static Specification<Book> loanStatus(LoanStatus status) {
        return (root, query, cb) -> cb.equal(root.get("loanStatus"), status);
    }

    public static Specification<Book> currentShelfId(Long shelfId) {
        return (root, query, cb) -> cb.equal(root.get("currentShelf").get("id"), shelfId);
    }

    /** 분류기호가 해당 값으로 시작하는 도서 (예: 005 → 005.133 포함). */
    public static Specification<Book> classificationPrefix(String prefix) {
        String pattern = escapeLike(prefix.trim()) + "%";
        return (root, query, cb) -> cb.like(root.get("classificationNo"), pattern, ESCAPE);
    }

    private static String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
