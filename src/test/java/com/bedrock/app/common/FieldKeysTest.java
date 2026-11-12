package com.bedrock.app.common;

import com.bedrock.app.common.domain.FieldKeys;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldKeysTest {
    @Test
    void buildsAndRecognisesKeys() {
        UUID slot = UUID.fromString("0b9a7f2e-5f4d-4c3a-9a1b-2c3d4e5f6a7b");
        assertThat(FieldKeys.exception(LocalDate.of(2026, 11, 9))).isEqualTo("EXCEPTION:2026-11-09");
        assertThat(FieldKeys.exception(LocalDate.of(2026, 11, 9), slot)).isEqualTo("EXCEPTION:2026-11-09:" + slot);
        assertThat(FieldKeys.extraField("mood")).isEqualTo("EXTRA_FIELD:mood");

        for (String key : new String[]{"TIME_ZONE", "HAS_SCHEDULE", "HAS_TODO", "HAS_NOTE", "TIMETABLE_PERIOD",
                "PENDING_INBOX", "DUE_AT", "EXCEPTION:2026-11-09", "EXCEPTION:2026-11-09:" + slot, "EXTRA_FIELD:mood"}) {
            assertThat(FieldKeys.isValid(key)).as(key).isTrue();
        }
        for (String key : new String[]{"DUE", "DUE_DATE", "name", "EXCEPTION:2026-13-01", "EXCEPTION:2026-11-9",
                "EXCEPTION:2026-11-09:not-a-uuid", "EXCEPTION:2026-11-09x", "EXTRA_FIELD:", "EXTRA_FIELD:1a", ""}) {
            assertThat(FieldKeys.isValid(key)).as(key).isFalse();
        }
        assertThatThrownBy(() -> FieldKeys.extraField("bad key")).isInstanceOf(IllegalArgumentException.class);
    }
}
