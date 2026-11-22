package com.__found.smart_library.domain.scan.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.__found.smart_library.domain.scan.repository.ScanSessionRepository;
import com.__found.smart_library.domain.scan.repository.ScanSessionShelfRepository;
import com.__found.smart_library.domain.user.entity.AppUser;
import com.__found.smart_library.domain.user.entity.UserRole;
import com.__found.smart_library.domain.user.repository.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 엔티티 ↔ V1 스키마 매핑 검증. 모든 테스트는 롤백되어 DB 에 데이터가 남지 않는다.
 * 각 테스트는 flush() 로 실제 INSERT 를 내보내고 clear() 로 1차 캐시를 비워, DB 에서 다시 읽은 값을 검증한다.
 */
@SpringBootTest
@Transactional
class ScanSessionMappingTest {

    @Autowired ScanSessionRepository sessionRepository;
    @Autowired ScanSessionShelfRepository sessionShelfRepository;
    @Autowired AppUserRepository userRepository;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    Long userId;
    Long shelfId1;
    Long shelfId2;

    @BeforeEach
    void setUp() {
        AppUser user = userRepository.saveAndFlush(AppUser.builder()
                .email("scan-test-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("테스터")
                .role(UserRole.LIBRARIAN)
                .build());
        userId = user.getId();
        shelfId1 = insertShelf();
        shelfId2 = insertShelf();
    }

    @Test
    @DisplayName("회차를 저장한 뒤 flush·clear 후 재조회하면 값이 그대로이고, enum 문자열 저장과 미매핑 컬럼 NULL도 DB에서 확인한다")
    void saveAndReload_session_keepsAllValues() {
        ScanSession saved = sessionRepository.save(
                ScanSession.create("2026 상반기 점검", "TEST-ZONE", LocalDate.of(2026, 12, 1), userId));
        em.flush();
        em.clear();

        ScanSession found = sessionRepository.findById(saved.getId()).orElseThrow();

        assertThat(found).isNotSameAs(saved);
        assertThat(found.getSessionName()).isEqualTo("2026 상반기 점검");
        assertThat(found.getTargetArea()).isEqualTo("TEST-ZONE");
        assertThat(found.getPlannedStartDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(found.getStatus()).isEqualTo(ScanSessionStatus.IN_PROGRESS);
        assertThat(found.getStartedAt()).isNotNull();
        assertThat(found.getFinalizedAt()).isNull();
        assertThat(found.getCreatedBy()).isEqualTo(userId);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();

        // enum 이 이름 문자열로 저장되었는지, 매핑하지 않은 컬럼은 NULL 인지 DB 를 직접 확인
        Map<String, Object> row = jdbc.queryForMap(
                "select status, target_area, total_expected_books, received_observation_count, finalized_by "
                        + "from scan_session where scan_session_id = ?", saved.getId());
        assertThat(row.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(row.get("target_area")).isEqualTo("TEST-ZONE");
        assertThat(row.get("total_expected_books")).isNull();
        assertThat(row.get("received_observation_count")).isNull();
        assertThat(row.get("finalized_by")).isNull();
    }

    @Test
    @DisplayName("점검 예정일이 없어도 저장되고 재조회 시 null이다")
    void saveAndReload_nullPlannedDate_storedAsNull() {
        ScanSession saved = sessionRepository.save(ScanSession.create("예정일 없음", "TEST-ZONE", null, userId));
        em.flush();
        em.clear();

        assertThat(sessionRepository.findById(saved.getId()).orElseThrow().getPlannedStartDate()).isNull();
    }

    @Test
    @DisplayName("대상 서가를 저장한 뒤 재조회하면 복합키와 WAITING 상태가 그대로이고 복합키로 직접 조회도 된다")
    void saveAndReload_sessionShelves_keepsCompositeKeyAndStatus() {
        ScanSession session = sessionRepository.save(ScanSession.create("서가 등록", "TEST-ZONE", null, userId));
        sessionShelfRepository.saveAll(List.of(
                ScanSessionShelf.waiting(session.getId(), shelfId1),
                ScanSessionShelf.waiting(session.getId(), shelfId2)));
        em.flush();
        em.clear();

        List<ScanSessionShelf> found = sessionShelfRepository.findByIdScanSessionId(session.getId());

        assertThat(found).hasSize(2);
        assertThat(found).extracting(s -> s.getId().getShelfId()).containsExactlyInAnyOrder(shelfId1, shelfId2);
        assertThat(found).allSatisfy(s -> assertThat(s.getScanStatus()).isEqualTo(ScanShelfStatus.WAITING));

        // 복합키로 직접 조회도 가능해야 한다
        assertThat(sessionShelfRepository.findById(new ScanSessionShelfId(session.getId(), shelfId1))).isPresent();

        Map<String, Object> row = jdbc.queryForMap(
                "select scan_status, expected_book_count from scan_session_shelf "
                        + "where scan_session_id = ? and shelf_id = ?", session.getId(), shelfId1);
        assertThat(row.get("scan_status")).isEqualTo("WAITING");
        assertThat(row.get("expected_book_count")).isNull();
    }

    @Test
    @DisplayName("서가 N개를 저장할 때 INSERT만 N번 실행하고 서가마다 SELECT가 추가되지 않는다")
    void saveAll_sessionShelves_executesOnlyInsertsWithoutExtraSelect() {
        ScanSession session = sessionRepository.save(ScanSession.create("쿼리 수", "TEST-ZONE", null, userId));
        em.flush();
        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        sessionShelfRepository.saveAll(List.of(
                ScanSessionShelf.waiting(session.getId(), shelfId1),
                ScanSessionShelf.waiting(session.getId(), shelfId2)));
        em.flush();

        // Persistable.isNew() 가 없으면 merge 경로로 SELECT 가 서가마다 추가되어 4번이 된다.
        assertThat(stats.getEntityInsertCount()).isEqualTo(2);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(2);
        stats.setStatisticsEnabled(false);
    }

    @Test
    @DisplayName("존재하지 않는 사용자를 생성자로 저장하면 FK 위반으로 실패한다")
    void flush_unknownCreator_throwsDataIntegrityViolation() {
        ScanSession session = ScanSession.create("FK 위반", "TEST-ZONE", null, -1L);

        assertThatThrownBy(() -> sessionRepository.saveAndFlush(session))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("회차명이 100자를 넘으면 컬럼 길이 제약 위반으로 실패한다")
    void flush_sessionNameOver100Chars_throwsDataIntegrityViolation() {
        ScanSession session = ScanSession.create("가".repeat(101), "TEST-ZONE", null, userId);

        assertThatThrownBy(() -> sessionRepository.saveAndFlush(session))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("존재하지 않는 서가를 대상으로 등록하면 FK 위반으로 실패한다")
    void flush_unknownShelf_throwsDataIntegrityViolation() {
        ScanSession session = sessionRepository.save(ScanSession.create("서가 FK 위반", "TEST-ZONE", null, userId));
        ScanSessionShelf shelf = ScanSessionShelf.waiting(session.getId(), -1L);

        assertThatThrownBy(() -> sessionShelfRepository.saveAndFlush(shelf))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Long insertShelf() {
        String code = "TEST-" + UUID.randomUUID().toString().substring(0, 8);
        return jdbc.queryForObject(
                "insert into shelf (shelf_code, zone, floor_no, is_active, created_at, updated_at) "
                        + "values (?, 'TEST-ZONE', 1, true, now(), now()) returning shelf_id",
                Long.class, code);
    }
}
