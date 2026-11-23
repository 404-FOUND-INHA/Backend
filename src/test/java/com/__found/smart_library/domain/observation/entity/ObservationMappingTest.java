package com.__found.smart_library.domain.observation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.__found.smart_library.domain.observation.repository.BookObservationRepository;
import com.__found.smart_library.domain.observation.repository.ObservationMatchCandidateRepository;
import com.__found.smart_library.domain.scan.entity.ScanSession;
import com.__found.smart_library.domain.scan.entity.ScanSessionShelf;
import com.__found.smart_library.domain.scan.entity.ScanSessionShelfId;
import com.__found.smart_library.domain.scan.entity.ScanSessionStatus;
import com.__found.smart_library.domain.scan.entity.ScanShelfStatus;
import com.__found.smart_library.domain.scan.repository.ScanSessionRepository;
import com.__found.smart_library.domain.scan.repository.ScanSessionShelfRepository;
import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.entity.UserRole;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
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

/**
 * 관측·후보 엔티티와 회차/서가 확장 매핑이 V1 스키마와 맞는지 검증한다(롤백).
 * flush() 로 실제 INSERT 를 내보내고 clear() 로 1차 캐시를 비워 DB 에서 다시 읽은 값을 확인한다.
 */
@SpringBootTest
@Transactional
class ObservationMappingTest {

    @Autowired BookObservationRepository observationRepository;
    @Autowired ObservationMatchCandidateRepository candidateRepository;
    @Autowired ScanSessionRepository sessionRepository;
    @Autowired ScanSessionShelfRepository sessionShelfRepository;
    @Autowired AppUserRepository userRepository;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    Long sessionId;
    Long shelfId;
    Long bookId;
    final String id = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() {
        Long userId = userRepository.saveAndFlush(AppUser.builder()
                .email("obs-map-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("테스터")
                .role(UserRole.LIBRARIAN)
                .build()).getId();
        shelfId = jdbc.queryForObject("insert into shelf (shelf_code, is_active, created_at, updated_at) "
                + "values (?, true, now(), now()) returning shelf_id", Long.class, "TM-" + id);
        bookId = jdbc.queryForObject("insert into book (registration_no, title, call_number_raw, "
                + "classification_no, book_symbol, lifecycle_status, loan_status, created_at, updated_at) "
                + "values (?, '도서', '000 가', '000', '가', 'NORMAL', 'AVAILABLE', now(), now()) returning book_id",
                Long.class, "TM-" + id);
        sessionId = sessionRepository.save(ScanSession.create("매핑 테스트", "TEST-ZONE", null, userId)).getId();
    }

    @Test
    @DisplayName("관측을 저장한 뒤 재조회하면 모든 값이 그대로이고 enum 은 이름 문자열로 저장된다")
    void saveAndReload_observation_keepsAllValues() {
        LocalDateTime detectedAt = LocalDateTime.of(2026, 11, 24, 10, 30, 15, 123_456_000);
        LocalDateTime receivedAt = LocalDateTime.of(2026, 11, 24, 10, 31, 0);
        BookObservation saved = observationRepository.save(BookObservation.builder()
                .scanSessionId(sessionId).bookId(bookId).detectedShelfId(shelfId)
                .detectedLabelRaw("000 가 c.1").detectedClassificationNo("000").detectedBookSymbol("가")
                .detectedCopyNumber("c.1").detectedTitle("제목").detectedOrder(3)
                .xPositionCm(new BigDecimal("12.35")).matchConfidence(new BigDecimal("1.0000"))
                .analysisStatus(ObservationAnalysisStatus.MISPLACED).statusReason(ObservationStatusReason.WRONG_SHELF)
                .detectedAt(detectedAt).receivedAt(receivedAt).build());
        em.flush();
        em.clear();

        BookObservation found = observationRepository.findById(saved.getId()).orElseThrow();

        assertThat(found).isNotSameAs(saved);
        assertThat(found.getScanSessionId()).isEqualTo(sessionId);
        assertThat(found.getBookId()).isEqualTo(bookId);
        assertThat(found.getDetectedShelfId()).isEqualTo(shelfId);
        assertThat(found.getDetectedLabelRaw()).isEqualTo("000 가 c.1");
        assertThat(found.getDetectedClassificationNo()).isEqualTo("000");
        assertThat(found.getDetectedBookSymbol()).isEqualTo("가");
        assertThat(found.getDetectedCopyNumber()).isEqualTo("c.1");
        assertThat(found.getDetectedTitle()).isEqualTo("제목");
        assertThat(found.getDetectedOrder()).isEqualTo(3);
        assertThat(found.getXPositionCm()).isEqualByComparingTo("12.35");
        assertThat(found.getMatchConfidence()).isEqualByComparingTo("1.0000");
        assertThat(found.getAnalysisStatus()).isEqualTo(ObservationAnalysisStatus.MISPLACED);
        assertThat(found.getStatusReason()).isEqualTo(ObservationStatusReason.WRONG_SHELF);
        assertThat(found.getDetectedAt()).isEqualTo(detectedAt);
        assertThat(found.getReceivedAt()).isEqualTo(receivedAt);

        Map<String, Object> row = jdbc.queryForMap("select analysis_status, status_reason from book_observation "
                + "where observation_id = ?", saved.getId());
        assertThat(row.get("analysis_status")).isEqualTo("MISPLACED");
        assertThat(row.get("status_reason")).isEqualTo("WRONG_SHELF");
    }

    @Test
    @DisplayName("선택 값이 없는 관측(도서 미식별)도 null 로 저장되고 재조회된다")
    void saveAndReload_observationWithoutOptionalValues_keepsNulls() {
        BookObservation saved = observationRepository.save(BookObservation.builder()
                .scanSessionId(sessionId).detectedOrder(1)
                .analysisStatus(ObservationAnalysisStatus.REVIEW_REQUIRED)
                .statusReason(ObservationStatusReason.OCR_FAILED)
                .receivedAt(LocalDateTime.of(2026, 11, 24, 10, 31, 0)).build());
        em.flush();
        em.clear();

        BookObservation found = observationRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getBookId()).isNull();
        assertThat(found.getDetectedShelfId()).isNull();
        assertThat(found.getDetectedLabelRaw()).isNull();
        assertThat(found.getXPositionCm()).isNull();
        assertThat(found.getMatchConfidence()).isNull();
        assertThat(found.getDetectedAt()).isNull();
    }

    @Test
    @DisplayName("후보를 저장한 뒤 복합키로 재조회하면 순위·도서·선정 근거가 그대로이고 선정 근거는 이름으로 저장된다")
    void saveAndReload_candidate_keepsCompositeKeyAndReason() {
        BookObservation obs = observationRepository.save(BookObservation.builder()
                .scanSessionId(sessionId).detectedOrder(1)
                .analysisStatus(ObservationAnalysisStatus.REVIEW_REQUIRED)
                .statusReason(ObservationStatusReason.NO_MATCH)
                .receivedAt(LocalDateTime.of(2026, 11, 24, 10, 31, 0)).build());
        candidateRepository.saveAll(List.of(
                ObservationMatchCandidate.of(obs.getId(), 1, bookId, CandidateReason.COPY_MISMATCH),
                ObservationMatchCandidate.of(obs.getId(), 2, bookId, CandidateReason.SYMBOL_ONLY)));
        em.flush();
        em.clear();

        ObservationMatchCandidate found = candidateRepository
                .findById(new ObservationMatchCandidateId(obs.getId(), 2)).orElseThrow();

        assertThat(found.getBookId()).isEqualTo(bookId);
        assertThat(found.getMatchReason()).isEqualTo(CandidateReason.SYMBOL_ONLY);
        assertThat(found.isNew()).isFalse();
        assertThat(jdbc.queryForObject("select match_reason from observation_match_candidate "
                + "where observation_id = ? and candidate_rank = 1", String.class, obs.getId()))
                .isEqualTo("COPY_MISMATCH");
        assertThat(new ObservationMatchCandidateId(1L, 2)).isEqualTo(new ObservationMatchCandidateId(1L, 2))
                .hasSameHashCodeAs(new ObservationMatchCandidateId(1L, 2))
                .isNotEqualTo(new ObservationMatchCandidateId(1L, 3));
    }

    @Test
    @DisplayName("서가에 결과 수신을 기록하면 상태·검출 도서 수·촬영 시각이 저장되고 재조회된다")
    void saveAndReload_sessionShelfReceiveResult_keepsValues() {
        sessionShelfRepository.save(ScanSessionShelf.waiting(sessionId, shelfId));
        em.flush();
        em.clear();
        ScanSessionShelf shelf = sessionShelfRepository.findById(new ScanSessionShelfId(sessionId, shelfId))
                .orElseThrow();
        LocalDateTime scannedAt = LocalDateTime.of(2026, 11, 24, 10, 30, 0);

        shelf.receiveResult(7, scannedAt);
        em.flush();
        em.clear();

        ScanSessionShelf found = sessionShelfRepository.findById(new ScanSessionShelfId(sessionId, shelfId))
                .orElseThrow();
        assertThat(found.getScanStatus()).isEqualTo(ScanShelfStatus.RESULT_RECEIVED);
        assertThat(found.getDetectedBookCount()).isEqualTo(7);
        assertThat(found.getScannedAt()).isEqualTo(scannedAt);
        assertThat(jdbc.queryForObject("select scan_status from scan_session_shelf where scan_session_id = ? "
                + "and shelf_id = ?", String.class, sessionId, shelfId)).isEqualTo("RESULT_RECEIVED");
    }

    @Test
    @DisplayName("진행 중인 회차만 검토 상태로 바꿀 수 있고, 이미 REVIEW 인 회차를 다시 바꾸면 예외이다")
    void markReviewReady_onlyFromInProgress() {
        ScanSession session = sessionRepository.findById(sessionId).orElseThrow();

        session.markReviewReady();
        em.flush();
        em.clear();

        assertThat(sessionRepository.findById(sessionId).orElseThrow().getStatus())
                .isEqualTo(ScanSessionStatus.REVIEW);
        assertThat(jdbc.queryForObject("select status from scan_session where scan_session_id = ?",
                String.class, sessionId)).isEqualTo("REVIEW");
        ScanSession review = sessionRepository.findById(sessionId).orElseThrow();
        assertThatThrownBy(review::markReviewReady).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("회차 행 잠금 조회는 해당 회차를 돌려주고 없는 회차는 비어 있다")
    void findByIdForUpdate_returnsSessionOrEmpty() {
        assertThat(sessionRepository.findByIdForUpdate(sessionId)).isPresent();
        assertThat(sessionRepository.findByIdForUpdate(Long.MAX_VALUE)).isEmpty();
    }
}
