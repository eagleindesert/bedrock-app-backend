package com.bedrock.app.common;

import com.bedrock.app.support.PostgresIntegrationSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.*;

class DatabaseMigrationTest extends PostgresIntegrationSupport {
    @TempDir
    Path ownerMigrations;

    @Test
    void refusesToEraseOldDataThenBuildsCanonicalSchemaAndAcceptsOutOfOrderMigrations() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("3").load().migrate();
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO users (id, email, password, nickname, role) VALUES "
                    + "(17, 'original@example.com', 'old-hash', 'original user', 'USER')");
        }
        var flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .outOfOrder(true).load();
        assertThatThrownBy(flyway::migrate).hasMessageContaining("구형 모델 데이터가 있습니다");
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            var rows = sql.executeQuery("SELECT email FROM users WHERE id=17");
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("original@example.com");
            // 이 테스트가 만든 임시 DB의 샘플 행만 제거해 빈 DB 전환도 검증한다.
            sql.execute("DELETE FROM users WHERE id=17");
        }
        // V4(구형 정리)와 V5부터의 공통 마이그레이션 전부
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1 + CanonicalModelSchemaTest.commonMigrations().size());
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            var rows = sql.executeQuery("SELECT count(*) FROM information_schema.tables "
                    + "WHERE table_schema='public' AND table_type='BASE TABLE' "
                    + "AND table_name <> 'flyway_schema_history'");
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(32);
            rows = sql.executeQuery("SELECT data_type FROM information_schema.columns "
                    + "WHERE table_schema='public' AND table_name='users' AND column_name='id'");
            rows.next();
            assertThat(rows.getString(1)).isEqualTo("uuid");
            rows = sql.executeQuery("SELECT nextval('workspace_sync_seq'), nextval('workspace_sync_seq')");
            rows.next();
            assertThat(rows.getLong(2)).isGreaterThan(rows.getLong(1));
        }

        Files.writeString(ownerMigrations.resolve("V301__be3_probe.sql"), "CREATE TABLE be3_probe (id INT)");
        var ownerFlyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration", "filesystem:" + ownerMigrations)
                .outOfOrder(true).load();
        assertThat(ownerFlyway.migrate().migrationsExecuted).isEqualTo(1);
        Files.writeString(ownerMigrations.resolve("V101__be1_probe.sql"), "CREATE TABLE be1_probe (id INT)");
        assertThat(ownerFlyway.migrate().migrationsExecuted).isEqualTo(1);
        ownerFlyway.validate();
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
