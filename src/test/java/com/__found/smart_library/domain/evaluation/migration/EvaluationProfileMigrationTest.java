package com.__found.smart_library.domain.evaluation.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * V4(평가 설정 테이블 + FK) 마이그레이션 검증. 운영 스키마를 건드리지 않도록 임시 스키마에서
 * V3 까지 적용 -> 가중치 행 입력 -> V4 적용 순서로 "이미 가중치가 있는 DB" 상황을 재현한다.
 */
@SpringBootTest
class EvaluationProfileMigrationTest {

    @Autowired DataSource dataSource;

    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        schema = "mig_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @AfterEach
    void dropSchema() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    @DisplayName("가중치 행이 이미 있어도 V4가 성공하고, 부모 행이 만들어지며 가중치는 보존되고 활성 버전은 1개다")
    void v4_withExistingWeights_backfillsParentsAndKeepsOneActive() throws Exception {
        flyway("3").migrate();
        long userId = insertUser();
        insertWeight("legacy-old", "DEMAND", "0.5", false, userId, "2026-01-01 00:00:00");
        insertWeight("legacy-old", "RARITY", "0.5", false, userId, "2026-01-01 00:00:00");
        insertWeight("legacy-new", "DEMAND", "0.6", true, userId, "2026-02-01 00:00:00");
        insertWeight("legacy-new", "RARITY", "0.4", true, userId, "2026-02-01 00:00:00");
        // 활성 표시가 둘 이상인 비정상 상태: 더 최근 버전만 남아야 한다
        insertWeight("legacy-mid", "DEMAND", "1.0", true, userId, "2026-01-15 00:00:00");

        flyway(null).migrate();

        assertThat(queryStrings("select profile_version from evaluation_profile order by profile_version"))
                .containsExactly("legacy-mid", "legacy-new", "legacy-old");
        assertThat(queryStrings("select profile_version from evaluation_profile where is_active"))
                .containsExactly("legacy-new");
        assertThat(queryStrings("select distinct disposal_threshold::text from evaluation_profile"))
                .containsExactly("50.00");
        assertThat(queryStrings("select distinct configured_by::text from evaluation_profile"))
                .containsExactly(String.valueOf(userId));
        assertThat(queryStrings("select profile_version || ':' || criterion_type || ':' || weight || ':' || is_active "
                + "from evaluation_criterion_weight order by 1")).containsExactly(
                "legacy-mid:DEMAND:1.0000:false",
                "legacy-new:DEMAND:0.6000:true",
                "legacy-new:RARITY:0.4000:true",
                "legacy-old:DEMAND:0.5000:false",
                "legacy-old:RARITY:0.5000:false");
        // FK 가 실제로 걸렸는지: 없는 버전의 가중치는 넣을 수 없다
        assertThatThrownBy(() -> insertWeight("no-such-version", "DEMAND", "1.0", false, userId,
                "2026-03-01 00:00:00")).hasMessageContaining("fk_weight_profile");
    }

    @Test
    @DisplayName("가중치 행이 없는 빈 DB에서도 V4가 성공하고 설정 행은 만들어지지 않는다")
    void v4_onEmptyDatabase_succeedsWithoutRows() throws Exception {
        flyway(null).migrate();

        assertThat(queryStrings("select count(*)::text from evaluation_profile")).containsExactly("0");
        assertThat(queryStrings("select count(*)::text from evaluation_criterion_weight")).containsExactly("0");
    }

    // ---- helpers ----

    private Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .createSchemas(true)
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private long insertUser() throws SQLException {
        try (Connection c = inSchema(); PreparedStatement ps = c.prepareStatement(
                "insert into app_user (email, password_hash, name, role, status, created_at, updated_at) "
                        + "values (?, 'h', '테스터', 'LIBRARIAN', 'ACTIVE', now(), now()) returning user_id")) {
            ps.setString(1, "mig-" + UUID.randomUUID() + "@test.com");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void insertWeight(String version, String criterion, String weight, boolean active, long userId,
                              String createdAt) throws SQLException {
        try (Connection c = inSchema(); PreparedStatement ps = c.prepareStatement(
                "insert into evaluation_criterion_weight (profile_version, criterion_type, weight, is_active, "
                        + "configured_by, created_at) values (?, ?, ?::numeric, ?, ?, ?::timestamp)")) {
            ps.setString(1, version);
            ps.setString(2, criterion);
            ps.setString(3, weight);
            ps.setBoolean(4, active);
            ps.setLong(5, userId);
            ps.setString(6, createdAt);
            ps.executeUpdate();
        }
    }

    private List<String> queryStrings(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = inSchema(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    /** 임시 스키마를 기본 스키마로 쓰는 연결. 커넥션 풀에 돌려주기 전에 search_path 를 되돌려야 하므로 close 시 초기화한다. */
    private Connection inSchema() throws SQLException {
        Connection raw = dataSource.getConnection();
        String original = raw.getSchema();
        raw.setSchema(schema);
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        raw.setSchema(original);
                    }
                    try {
                        return method.invoke(raw, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
