package com.bedrock.app.action.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ActionRequestsJsonTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void legacyPrefillIsAcceptedAsTriggerPayload() throws Exception {
        UUID collectionId = UUID.randomUUID();
        ActionRequests.Execute request = jsonMapper.readValue("""
                {
                  "targetCollectionId": "%s",
                  "prefill": {"title": "기존 클라이언트 입력"},
                  "input": {"title": "최종 입력"}
                }
                """.formatted(collectionId), ActionRequests.Execute.class);

        assertThat(request.targetCollectionId()).isEqualTo(collectionId);
        assertThat(request.triggerPayload())
                .containsEntry("title", "기존 클라이언트 입력");
        assertThat(request.input()).containsEntry("title", "최종 입력");
    }

    @Test
    void newRequestsAreSerializedWithTriggerPayload() throws Exception {
        ActionRequests.Execute request = new ActionRequests.Execute(
                UUID.randomUUID(),
                Map.of("title", "초기값"),
                Map.of("title", "최종 입력")
        );

        String json = jsonMapper.writeValueAsString(request);

        assertThat(json).contains("\"triggerPayload\"");
        assertThat(json).doesNotContain("\"prefill\"");
    }
}
