package com.bedrock.app.common;

import com.bedrock.app.support.PostgresIntegrationSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V9를 V8까지 적용되어 행이 있는 DB에 올린다. 채운 값과 옮긴 값이 맞는지 본다. */
class WorkspaceMigrationTest extends PostgresIntegrationSupport {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void v9FillsWorkspacesAndMovesSettingsOnADatabaseWithRows() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .outOfOrder(true).target("8").load().migrate();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID shown = UUID.randomUUID();
        UUID hiddenLater = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            for (UUID user : List.of(alice, bob)) {
                sql.execute("INSERT INTO users (id, email, name, status) VALUES ('" + user + "', '" + user
                        + "@example.com', 'u', 'ACTIVE')");
            }
            sql.execute("INSERT INTO collections (id, user_id, name_enc, color, icon) VALUES "
                    + "('" + shown + "', '" + alice + "', 'enc', 'BLUE', 'folder'), "
                    + "('" + hiddenLater + "', '" + alice + "', 'enc', 'RED', 'folder')");
            sql.execute("INSERT INTO user_settings (user_id, time_zone, visible_collection_ids_json) VALUES ('"
                    + alice + "', 'Asia/Seoul', '[\"" + shown + "\"]')");
            sql.execute("INSERT INTO user_view_states (id, user_id, view_key, state_json) VALUES ('"
                    + UUID.randomUUID() + "', '" + alice + "', 'CALENDAR_LAST_VIEW', '{\"mode\":\"MONTH\"}')");
            sql.execute("INSERT INTO items (id, user_id, collection_id, created_at) VALUES ('" + item + "', '"
                    + alice + "', '" + shown + "', '2026-11-01T09:00:00Z')");
            sql.execute("INSERT INTO item_timetables (item_id, user_id, period_start) VALUES ('" + item + "', '"
                    + alice + "', '2026-09-01')");
            sql.execute("INSERT INTO system_notifications (id, user_id, notification_type, payload_enc) VALUES ('"
                    + UUID.randomUUID() + "', NULL, 'SERVER_NOTICE', 'enc')");
        }

        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .outOfOrder(true).load().migrate();

        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            assertThat(single(sql, "SELECT count(*) FROM workspaces")).isEqualTo("2");
            String aliceWorkspace = single(sql, "SELECT id FROM workspaces WHERE owner_user_id = '" + alice + "'");
            assertThat(single(sql, "SELECT workspace_id FROM items WHERE id = '" + item + "'")).isEqualTo(aliceWorkspace);
            assertThat(single(sql, "SELECT count(*) FROM collections WHERE workspace_id = '" + aliceWorkspace + "'"))
                    .isEqualTo("2");

            // 켠 목록에 없던 컬렉션이 두 모듈의 끈 목록으로 옮겨지고, 있던 보기 상태는 지킨다.
            Map<?, ?> calendar = json.readValue(single(sql, "SELECT state_json::text FROM user_view_states "
                    + "WHERE user_id = '" + alice + "' AND view_key = 'CALENDAR_LAST_VIEW'"), Map.class);
            assertThat(calendar.get("mode")).isEqualTo("MONTH");
            assertThat(calendar.get("hiddenCollectionIds")).isEqualTo(List.of(hiddenLater.toString()));
            Map<?, ?> timetable = json.readValue(single(sql, "SELECT state_json::text FROM user_view_states "
                    + "WHERE user_id = '" + alice + "' AND view_key = 'TIMETABLE'"), Map.class);
            assertThat(timetable.get("hiddenCollectionIds")).isEqualTo(List.of(hiddenLater.toString()));
            assertThat(single(sql, "SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_name = 'user_settings' AND column_name = 'visible_collection_ids_json'")).isEqualTo("0");

            // 사용자 없는 공지는 사용자마다 한 줄로 펼쳐진다.
            assertThat(single(sql, "SELECT count(*) FROM system_notifications")).isEqualTo("2");
            assertThat(single(sql, "SELECT count(DISTINCT user_id) FROM system_notifications")).isEqualTo("2");

            assertThat(single(sql, "SELECT server_created_at = created_at FROM items WHERE id = '" + item + "'"))
                    .isEqualTo("t");
            assertThat(single(sql, "SELECT series_root_id FROM item_timetables WHERE item_id = '" + item + "'"))
                    .isEqualTo(item.toString());
        }
    }

    private static String single(Statement sql, String query) throws Exception {
        try (var rows = sql.executeQuery(query)) {
            assertThat(rows.next()).as(query).isTrue();
            return rows.getString(1);
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
