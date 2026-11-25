package com.__found.smart_library.domain.evaluation.metric;

import static org.assertj.core.api.Assertions.assertThat;

import com.__found.smart_library.domain.book.entity.Book;
import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.book.entity.LoanStatus;
import com.__found.smart_library.domain.book.repository.BookRepository;
import com.__found.smart_library.domain.evaluation.entity.CriterionType;
import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.entity.UserRole;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 실제 대출·오손·복본 데이터로 지표를 계산한다(롤백). 점수 규칙 자체는 {@link BackendMetricCalculatorTest} 가 검증한다. */
@SpringBootTest
@Transactional
class BackendMetricServiceTest {

    @Autowired BackendMetricService service;
    @Autowired BookRepository bookRepository;
    @Autowired AppUserRepository userRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private final String prefix = "TMT" + UUID.randomUUID().toString().substring(0, 6);
    private Long userId;

    @BeforeEach
    void setUp() {
        userId = userRepository.saveAndFlush(AppUser.builder()
                .email("metric-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("테스터")
                .role(UserRole.LIBRARIAN)
                .build()).getId();
    }

    @Test
    @DisplayName("대출·오손·복본 기록이 평가축별 점수로 계산된다 (수요성, 중복성, 물리적 상태 순)")
    void calculate_usesLoanConditionAndCopies() {
        String callNo = prefix + "-A";
        Book target = saveBook(callNo, LifecycleStatus.NORMAL);
        saveBook(callNo, LifecycleStatus.NORMAL);        // 복본
        saveBook(callNo, LifecycleStatus.DISPOSED);      // 제적 도서는 복본으로 세지 않는다
        setLoanSummary(target, 10, LocalDate.now().minusDays(10));
        // 기간(3년) 안 3건 + 기간 밖 1건
        loan(target, LocalDateTime.now().minusDays(10));
        loan(target, LocalDateTime.now().minusMonths(6));
        loan(target, LocalDateTime.now().minusYears(2));
        loan(target, LocalDateTime.now().minusYears(4));
        condition(target, "STAIN", "LOW");
        condition(target, "TORN", "HIGH");
        em.flush();
        em.clear();

        List<CriterionScore> scores = service.calculate(List.of(target.getId())).get(target.getId());

        assertThat(scores).extracting(CriterionScore::criterionType)
                .containsExactly(CriterionType.DEMAND, CriterionType.DUPLICATION, CriterionType.PHYSICAL_CONDITION);
        CriterionScore demand = scores.get(0);
        assertThat(demand.rawScore()).isEqualByComparingTo("3.00");   // 기간 밖 대출은 제외
        // 대출 3건 -> 40 * 0.6 = 24, 마지막 대출 10일 전 -> 10/1095*100*0.4 = 0.365
        assertThat(demand.normalizedScore()).isEqualByComparingTo("24.37");
        assertThat(scores.get(1).rawScore()).isEqualByComparingTo("2.00");
        assertThat(scores.get(1).normalizedScore()).isEqualByComparingTo("33.33");
        // TORN/HIGH = 72, 두 번째 기록 가산 5
        assertThat(scores.get(2).normalizedScore()).isEqualByComparingTo("77.00");
    }

    @Test
    @DisplayName("대출·오손 기록이 없고 복본도 없으면 수요성 100, 중복성 0, 물리적 상태 0 이다")
    void calculate_noRecords() {
        Book book = saveBook(prefix + "-B", LifecycleStatus.NORMAL);
        em.flush();
        em.clear();

        List<CriterionScore> scores = service.calculate(List.of(book.getId())).get(book.getId());

        assertThat(scores.get(0).normalizedScore()).isEqualByComparingTo("100.00");
        assertThat(scores.get(1).normalizedScore()).isEqualByComparingTo("0.00");
        assertThat(scores.get(2).normalizedScore()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("loan 이력 없이 마지막 대출일만 있는 이관 도서는 기간 안이면 최소 1건으로 본다")
    void calculate_migratedBookWithoutLoanRows() {
        Book recent = saveBook(prefix + "-C1", LifecycleStatus.NORMAL);
        Book old = saveBook(prefix + "-C2", LifecycleStatus.NORMAL);
        setLoanSummary(recent, 4, LocalDate.now().minusDays(30));
        setLoanSummary(old, 4, LocalDate.now().minusYears(6));
        em.flush();
        em.clear();

        Map<Long, List<CriterionScore>> result = service.calculate(List.of(recent.getId(), old.getId()));

        assertThat(result.get(recent.getId()).get(0).rawScore()).isEqualByComparingTo("1.00");
        assertThat(result.get(old.getId()).get(0).rawScore()).isEqualByComparingTo("0.00");
        assertThat(result.get(old.getId()).get(0).normalizedScore()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("여러 도서를 한 번에 계산하고 중복 ID 는 한 번만, 없는 도서는 결과에서 빠진다")
    void calculate_batch() {
        Book a = saveBook(prefix + "-D1", LifecycleStatus.NORMAL);
        Book b = saveBook(prefix + "-D2", LifecycleStatus.NORMAL);
        saveBook(prefix + "-D2", LifecycleStatus.NORMAL);   // b 의 복본
        em.flush();
        em.clear();

        Map<Long, List<CriterionScore>> result = service.calculate(List.of(a.getId(), b.getId(), a.getId(), 99999999L));

        assertThat(result).containsOnlyKeys(a.getId(), b.getId());
        assertThat(result.get(a.getId()).get(1).rawScore()).isEqualByComparingTo("1.00");
        assertThat(result.get(b.getId()).get(1).rawScore()).isEqualByComparingTo("2.00");
        assertThat(service.calculate(List.of())).isEmpty();
    }

    @Test
    @DisplayName("분류기호만 같거나 도서기호만 같은 도서는 복본이 아니다")
    void calculate_partialCallNumberMatchIsNotCopy() {
        Book book = saveBook(prefix + "-E", LifecycleStatus.NORMAL);   // 분류기호 "005.74" + 도서기호 prefix-E
        Book sameClassOnly = bookRepository.saveAndFlush(newBook("005.74", prefix + "-OTHER", LifecycleStatus.NORMAL));
        em.flush();
        em.clear();

        List<CriterionScore> scores = service.calculate(List.of(book.getId(), sameClassOnly.getId()))
                .get(book.getId());

        assertThat(scores.get(1).rawScore()).isEqualByComparingTo("1.00");
    }

    // ---- helpers ----

    /** 도서기호가 prefix 로 시작하므로 다른 데이터와 청구기호가 겹치지 않는다. */
    private Book saveBook(String bookSymbol, LifecycleStatus lifecycle) {
        return bookRepository.saveAndFlush(newBook("005.74", bookSymbol, lifecycle));
    }

    private Book newBook(String classificationNo, String bookSymbol, LifecycleStatus lifecycle) {
        return Book.builder()
                .registrationNo(prefix + "-" + UUID.randomUUID().toString().substring(0, 8))
                .title("지표 테스트 도서")
                .callNumberRaw(classificationNo + " " + bookSymbol)
                .classificationNo(classificationNo)
                .bookSymbol(bookSymbol)
                .lifecycleStatus(lifecycle)
                .loanStatus(LoanStatus.AVAILABLE)
                .build();
    }

    private void setLoanSummary(Book book, int total, LocalDate last) {
        jdbc.update("update book set total_loan_count = ?, last_loan_date = ? where book_id = ?",
                total, last, book.getId());
    }

    private void loan(Book book, LocalDateTime loanedAt) {
        jdbc.update("insert into loan (book_id, loaned_at, status, created_at, updated_at) "
                + "values (?, ?, 'RETURNED', now(), now())", book.getId(), loanedAt);
    }

    private void condition(Book book, String type, String severity) {
        jdbc.update("insert into book_condition_report (book_id, reporter_id, condition_type, severity, reported_at) "
                + "values (?, ?, ?, ?, now())", book.getId(), userId, type, severity);
    }
}
