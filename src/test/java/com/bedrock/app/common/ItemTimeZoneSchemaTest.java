package com.bedrock.app.common;

import com.bedrock.app.support.PostgresIntegrationSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V7: 마감은 날짜와 시각 가운데 하나만, 계정 설정에는 기본 시간대가 늘 있다. */
class ItemTimeZoneSchemaTest extends PostgresIntegrationSupport {
    @Test
    void dueIsDateOrInstantAndSettingsAlwaysHaveTimeZone() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .outOfOrder(true).load().migrate();
        UUID user = UUID.randomUUID();
        UUID collection = UUID.randomUUID();
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO users (id, email, name, status) VALUES ('" + user + "', 'tz@example.com', 'tz', 'ACTIVE')");
            // V9부터 아이템 · 컬렉션은 워크스페이스에 속한다.
            String workspaceColumn = ", workspace_id";
            String workspaceValue = ", '" + workspace(sql, user) + "'";
            sql.execute("INSERT INTO collections (id, user_id, name_enc, color, icon" + workspaceColumn + ") VALUES ('"
                    + collection + "', '" + user + "', 'enc', 'BLUE', 'calendar'" + workspaceValue + ")");

            sql.execute("INSERT INTO items (id, user_id, collection_id, has_todo, todo_status, time_zone, due_date" + workspaceColumn
                    + ") VALUES ('" + UUID.randomUUID() + "', '" + user + "', '" + collection + "', TRUE, 'TODO', 'Asia/Seoul', '2026-11-20'"
                    + workspaceValue + ")");
            assertThatThrownBy(() -> sql.execute("INSERT INTO items (id, user_id, collection_id, has_todo, todo_status, "
                    + "due_at, due_date" + workspaceColumn + ") VALUES ('" + UUID.randomUUID() + "', '" + user + "', '" + collection
                    + "', TRUE, 'TODO', '2026-11-20T14:59:59Z', '2026-11-20'" + workspaceValue + ")"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_items_due_at_or_due_date");

            assertThatThrownBy(() -> sql.execute("INSERT INTO user_settings (user_id) VALUES ('" + user + "')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("time_zone");
            sql.execute("INSERT INTO user_settings (user_id, time_zone) VALUES ('" + user + "', 'America/Los_Angeles')");
            var rows = sql.executeQuery("SELECT time_zone FROM user_settings WHERE user_id = '" + user + "'");
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("America/Los_Angeles");
        }
    }

    private static UUID workspace(Statement sql, UUID user) throws SQLException {
        var id = UUID.randomUUID();
        sql.execute("INSERT INTO workspaces (id, owner_user_id) VALUES ('" + id + "', '" + user + "')");
        return id;
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
