package com.__found.smart_library.domain.evaluation.query;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.__found.smart_library.domain.book.entity.Book;
import com.__found.smart_library.domain.book.entity.LifecycleStatus;
import com.__found.smart_library.domain.book.entity.LoanStatus;
import com.__found.smart_library.domain.book.repository.BookRepository;
import com.__found.smart_library.domain.shelf.entity.Shelf;
import com.__found.smart_library.domain.shelf.repository.ShelfRepository;
import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.entity.UserRole;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 평가 조회 테스트 공통 준비(롤백). 평가·평가축 결과는 계산 없이 직접 넣어 조회만 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
abstract class EvaluationQueryTestSupport {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected AppUserRepository userRepository;
    @Autowired protected ShelfRepository shelfRepository;
    @Autowired protected BookRepository bookRepository;

    protected final String prefix = "TQ" + UUID.randomUUID().toString().substring(0, 8);
    protected Long userId;
    protected int seq;

    protected void createUser() {
        userId = userRepository.saveAndFlush(AppUser.builder()
                .email("eval-query-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("사서")
                .role(UserRole.LIBRARIAN)
                .build()).getId();
    }

    protected Long session() {
        return jdbc.queryForObject("insert into scan_session (session_name, target_area, status, created_by, "
                + "created_at, updated_at) values ('평가 조회 테스트', ?, 'COMPLETED', ?, now(), now()) "
                + "returning scan_session_id", Long.class, prefix + "-Z", userId);
    }

    protected Shelf shelf(String suffix, int floor, String x, String y, String z) {
        Long id = jdbc.queryForObject("insert into shelf (shelf_code, zone, floor_no, shelf_no, level_no, map_x, "
                + "map_y, map_z, is_active, created_at, updated_at) values (?, ?, ?, 3, 2, ?, ?, ?, true, now(), now()) "
                + "returning shelf_id", Long.class, prefix + "-" + suffix, prefix + "-Z", floor,
                new BigDecimal(x), new BigDecimal(y), new BigDecimal(z));
        return shelfRepository.findById(id).orElseThrow();
    }

    protected Long book(String suffix, Shelf currentShelf) {
        return bookRepository.saveAndFlush(Book.builder()
                .registrationNo(prefix + "-" + suffix + "-" + (++seq))
                .title(prefix + "-" + suffix)
                .author("저자-" + suffix)
                .callNumberRaw("005." + seq + " 김12ㄷ")
                .classificationNo("005." + seq)
                .bookSymbol("김12ㄷ" + seq)
                .currentShelf(currentShelf)
                .lifecycleStatus(LifecycleStatus.NORMAL)
                .loanStatus(LoanStatus.AVAILABLE)
                .build()).getId();
    }

    /** Backend 종합 평가 한 건. score 가 null 이면 평가 불가 상태로 넣는다. 평가 ID 를 돌려준다. */
    protected Long backend(Long sessionId, Long bookId, String score, String status, String reason) {
        return jdbc.queryForObject("insert into book_evaluation (book_id, scan_session_id, evaluation_type, "
                + "model_version, final_score, candidate_status, evaluation_reason, evaluated_at) "
                + "values (?, ?, 'BACKEND', 'v3', ?, ?, ?, now()) returning evaluation_id", Long.class,
                bookId, sessionId, score == null ? null : new BigDecimal(score), status, reason);
    }

    protected Long jevEvaluation(Long sessionId, Long bookId) {
        return jdbc.queryForObject("insert into book_evaluation (book_id, scan_session_id, evaluation_type, "
                + "model_version, candidate_status, evaluated_at) values (?, ?, 'JEV', 'jev-1.14.0', 'UNDECIDED', now()) "
                + "returning evaluation_id", Long.class, bookId, sessionId);
    }

    protected void backendAxis(Long evaluationId, String type, String raw, String normalized) {
        jdbc.update("insert into evaluation_criterion_result (evaluation_id, criterion_type, evaluator_type, "
                + "model_version, raw_score, normalized_score) values (?, ?, 'BACKEND', 'v3', ?, ?)",
                evaluationId, type, new BigDecimal(raw), new BigDecimal(normalized));
    }

    protected void jevAxis(Long evaluationId, String type, String normalized, int grade) {
        jdbc.update("insert into evaluation_criterion_result (evaluation_id, criterion_type, evaluator_type, "
                + "model_version, raw_score, normalized_score, confidence, selected_grade, probability_distribution) "
                + "values (?, ?, 'JEV', 'jev-1.14.0', 2.40, ?, 0.7800, ?, '{\"2\":0.35,\"3\":0.45}'::jsonb)",
                evaluationId, type, new BigDecimal(normalized), grade);
    }

    protected RequestPostProcessor auth(String role) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId))).authorities(new SimpleGrantedAuthority(role));
    }

    protected ResultActions fetch(String url, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(get(url).with(auth));
    }

    protected ResultActions fetch(String url) throws Exception {
        return fetch(url, auth("ROLE_LIBRARIAN"));
    }

    protected JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }
}
