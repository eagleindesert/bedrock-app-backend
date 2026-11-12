package com.bedrock.app.common;

import com.bedrock.app.support.PostgresIntegrationSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V10: 번호 올리기와 tombstone은 코드가 아니라 DB가 한다. CASCADE로 지워진 행도 빠지지 않는다. */
class SyncTriggerTest extends PostgresIntegrationSupport {
    @Test
    void updatesBumpSyncSeqAndDeletesLeaveTombstonesEvenThroughCascade() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .outOfOrder(true).load().migrate();
        UUID user = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID collection = UUID.randomUUID();
        UUID master = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID link = UUID.randomUUID();
        UUID exception = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO users (id, email, name, status) VALUES ('" + user + "', 'sync@example.com', 's', 'ACTIVE')");
            sql.execute("INSERT INTO workspaces (id, owner_user_id) VALUES ('" + workspace + "', '" + user + "')");
            sql.execute("INSERT INTO collections (id, user_id, workspace_id, name_enc, color, icon) VALUES ('"
                    + collection + "', '" + user + "', '" + workspace + "', 'enc', 'BLUE', 'folder')");
            for (UUID item : List.of(master, other)) {
                sql.execute("INSERT INTO items (id, user_id, workspace_id, collection_id) VALUES ('" + item + "', '"
                        + user + "', '" + workspace + "', '" + collection + "')");
            }
            sql.execute("INSERT INTO item_links (id, user_id, workspace_id, source_item_id, target_item_id, link_type) "
                    + "VALUES ('" + link + "', '" + user + "', '" + workspace + "', '" + master + "', '" + other + "', 'REFERENCE')");
            sql.execute("INSERT INTO user_pinned_items (user_id, workspace_id, item_id) VALUES ('" + user + "', '"
                    + workspace + "', '" + master + "')");
            sql.execute("INSERT INTO item_exceptions (id, user_id, workspace_id, master_item_id, recurrence_date) VALUES ('"
                    + exception + "', '" + user + "', '" + workspace + "', '" + master + "', '2026-11-16')");
            sql.execute("INSERT INTO conversations (id, user_id, status, record_date, content_enc, revision) VALUES ('"
                    + conversation + "', '" + user + "', 'SAVED', '2026-11-12', 'enc', 'r1')");

            // UPDATE는 sync_seq를 직접 넣어도 트리거가 새 번호를 준다.
            long before = number(sql, "SELECT sync_seq FROM collections WHERE id = '" + collection + "'");
            sql.execute("UPDATE collections SET icon = 'star', sync_seq = 1 WHERE id = '" + collection + "'");
            assertThat(number(sql, "SELECT sync_seq FROM collections WHERE id = '" + collection + "'")).isGreaterThan(before);

            // 아이템을 이루는 행(칸 버전 · 반복 · 충돌 후보)이 바뀌면 아이템 번호가 오른다.
            long item = number(sql, "SELECT sync_seq FROM items WHERE id = '" + master + "'");
            sql.execute("INSERT INTO item_field_versions (item_id, field_key, user_id, workspace_id, last_write_id) VALUES ('"
                    + master + "', 'NAME', '" + user + "', '" + workspace + "', '" + UUID.randomUUID() + "')");
            long afterVersion = number(sql, "SELECT sync_seq FROM items WHERE id = '" + master + "'");
            assertThat(afterVersion).isGreaterThan(item);
            sql.execute("INSERT INTO item_recurrences (item_id, user_id, workspace_id, rrule, series_root_id) VALUES ('"
                    + master + "', '" + user + "', '" + workspace + "', 'FREQ=WEEKLY', '" + master + "')");
            long afterRule = number(sql, "SELECT sync_seq FROM items WHERE id = '" + master + "'");
            assertThat(afterRule).isGreaterThan(afterVersion);
            sql.execute("INSERT INTO conflict_candidates (id, user_id, workspace_id, item_id, field_key, candidate_value_enc, "
                    + "source_write_id, kind) VALUES ('" + UUID.randomUUID() + "', '" + user + "', '" + workspace + "', '"
                    + master + "', 'NAME', 'enc', '" + UUID.randomUUID() + "', 'FIELD_VALUE')");
            assertThat(number(sql, "SELECT sync_seq FROM items WHERE id = '" + master + "'")).isGreaterThan(afterRule);

            // 아이템을 물리 삭제하면 CASCADE로 지워진 연결 · 고정 · 회차 예외도 tombstone을 남긴다.
            sql.execute("DELETE FROM items WHERE id = '" + master + "'");
            assertThat(tombstones(sql, workspace)).containsExactlyInAnyOrder(
                    "ITEM:" + master, "LINK:" + link, "PIN:" + master, "OCCURRENCE_EXCEPTION:" + exception);

            // 계정에 속한 자원은 그 계정의 워크스페이스에 남긴다.
            sql.execute("DELETE FROM conversations WHERE id = '" + conversation + "'");
            assertThat(tombstones(sql, workspace)).contains("CONVERSATION:" + conversation);

            // 탈퇴 정리는 tombstone을 남기지 않고 막히지도 않는다.
            sql.execute("DELETE FROM users WHERE id = '" + user + "'");
            assertThat(number(sql, "SELECT count(*) FROM sync_tombstones")).isZero();
            assertThat(number(sql, "SELECT count(*) FROM workspaces")).isZero();
        }
    }

    private static long number(Statement sql, String query) throws Exception {
        try (var rows = sql.executeQuery(query)) {
            assertThat(rows.next()).as(query).isTrue();
            return rows.getLong(1);
        }
    }

    private static List<String> tombstones(Statement sql, UUID workspace) throws Exception {
        var found = new ArrayList<String>();
        try (var rows = sql.executeQuery("SELECT resource_type, resource_id FROM sync_tombstones WHERE workspace_id = '"
                + workspace + "' ORDER BY sync_seq")) {
            while (rows.next()) found.add(rows.getString(1) + ":" + rows.getString(2));
        }
        return found;
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
