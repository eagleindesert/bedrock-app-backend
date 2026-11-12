package com.bedrock.app.common;

import com.bedrock.app.setting.domain.UserSetting;
import com.bedrock.app.support.PostgresIntegrationSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V8: 가입 때 종류별 기본 알림 넷이 들어가고, 꼴이 다른 값은 저장하지 않는다. */
class DefaultAlarmsSchemaTest extends PostgresIntegrationSupport {
    @Test
    void newSettingsGetInitialDefaultsAndOnlyFourAlarmListsAreStored() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .outOfOrder(true).load().migrate();
        UUID user = UUID.randomUUID();
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO users (id, email, name, status) VALUES ('" + user + "', 'alarm@example.com', 'alarm', 'ACTIVE')");
            sql.execute(insertSettings(sql, user));
            var rows = sql.executeQuery("SELECT default_alarms_json::text FROM user_settings WHERE user_id = '" + user + "'");
            assertThat(rows.next()).isTrue();
            Map<?, ?> stored = JsonMapper.builder().build().readValue(rows.getString(1), Map.class);
            assertThat(stored).isEqualTo(UserSetting.INITIAL_DEFAULT_ALARMS);

            sql.execute(update(user, "{\"timedEvent\":[0,30],\"allDayEvent\":[],\"timedTodo\":[60],\"dateOnlyTodo\":[0]}"));
            for (String rejected : List.of(
                    "{\"timedEvent\":[10],\"allDayEvent\":[0],\"timedTodo\":[0]}",
                    "{\"timedEvent\":[10],\"allDayEvent\":[0],\"timedTodo\":[0],\"dateOnlyTodo\":[0],\"other\":[0]}",
                    "{\"timedEvent\":10,\"allDayEvent\":[0],\"timedTodo\":[0],\"dateOnlyTodo\":[0]}",
                    "{\"timedEvent\":[-5],\"allDayEvent\":[0],\"timedTodo\":[0],\"dateOnlyTodo\":[0]}",
                    "{\"timedEvent\":[1.5],\"allDayEvent\":[0],\"timedTodo\":[0],\"dateOnlyTodo\":[0]}",
                    "{\"timedEvent\":[\"10\"],\"allDayEvent\":[0],\"timedTodo\":[0],\"dateOnlyTodo\":[0]}")) {
                assertThatThrownBy(() -> sql.execute(update(user, rejected))).as(rejected)
                        .isInstanceOf(SQLException.class).hasMessageContaining("ck_user_settings_default_alarms");
            }
        }
    }

    /** 다른 공통 보정이 user_settings에 기본값 없는 열(V7 time_zone)을 더했으면 그 값도 넣는다. */
    private String insertSettings(Statement sql, UUID user) throws SQLException {
        var rows = sql.executeQuery("SELECT 1 FROM information_schema.columns "
                + "WHERE table_name = 'user_settings' AND column_name = 'time_zone'");
        return rows.next()
                ? "INSERT INTO user_settings (user_id, time_zone) VALUES ('" + user + "', 'Asia/Seoul')"
                : "INSERT INTO user_settings (user_id) VALUES ('" + user + "')";
    }

    private String update(UUID user, String json) {
        return "UPDATE user_settings SET default_alarms_json = '" + json + "'::jsonb WHERE user_id = '" + user + "'";
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
