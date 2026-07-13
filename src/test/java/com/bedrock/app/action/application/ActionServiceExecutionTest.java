package com.bedrock.app.action.application;

import com.bedrock.app.action.block.BlockCatalog;
import com.bedrock.app.action.domain.*;
import com.bedrock.app.action.domain.type.BlockCode;
import com.bedrock.app.action.dto.ActionRequests;
import com.bedrock.app.action.dto.ActionResponse;
import com.bedrock.app.item.dto.response.ItemResponse;
import com.bedrock.app.item.service.ItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActionServiceExecutionTest {

    private ActionRepository actionRepository;
    private ItemService itemService;
    private ActionService service;

    @BeforeEach
    void setUp() {
        actionRepository = mock(ActionRepository.class);
        itemService = mock(ItemService.class);
        service = new ActionService(
                actionRepository,
                new BlockCatalog(),
                itemService
        );
    }

    @Test
    void executeMergesDefaultPrefillAndInputThenReturnsFullItem() {
        UUID actionId = UUID.randomUUID();
        UUID collectionId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Action action = todoAction(actionId, true);
        ItemResponse item = new ItemResponse(
                itemId,
                "캡스톤 발표자료 준비",
                17L,
                collectionId,
                Set.of("TODO"),
                Map.of(
                        "title", "캡스톤 발표자료 준비",
                        "priority", "P1",
                        "completed", false,
                        "tags", List.of()
                ),
                LocalDateTime.of(2026, 4, 9, 12, 0),
                LocalDateTime.of(2026, 4, 9, 12, 0)
        );

        when(actionRepository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.of(action));
        when(itemService.createInCollection(
                eq(17L),
                eq(collectionId),
                anySet(),
                anyString(),
                anyMap()
        )).thenReturn(item);

        var response = service.execute(
                actionId,
                17L,
                new ActionRequests.Execute(
                        collectionId,
                        Map.of(
                                "title", "빠른 입력",
                                "priority", "P2"
                        ),
                        Map.of(
                                "title", "캡스톤 발표자료 준비",
                                "priority", "P1",
                                "dueDate", "2026-04-28"
                        )
                )
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> attributesCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(itemService).createInCollection(
                eq(17L),
                eq(collectionId),
                eq(Set.of("TODO")),
                eq("캡스톤 발표자료 준비"),
                attributesCaptor.capture()
        );

        assertThat(attributesCaptor.getValue())
                .containsEntry("priority", "P1")
                .containsEntry("dueDate", "2026-04-28")
                .containsEntry("completed", false)
                .containsEntry("tags", List.of());
        assertThat(response.actionId()).isEqualTo(actionId);
        assertThat(response.itemId()).isEqualTo(itemId);
        assertThat(response.status()).isEqualTo(ActionResponse.Execution.Status.COMPLETED);
        assertThat(response.item()).isEqualTo(item);
    }

    @Test
    void prefillOverridesDefaultWhenUserDoesNotChangeTheField() {
        UUID actionId = UUID.randomUUID();
        UUID collectionId = UUID.randomUUID();
        when(actionRepository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.of(todoAction(actionId, true)));
        when(itemService.createInCollection(
                eq(17L),
                eq(collectionId),
                anySet(),
                anyString(),
                anyMap()
        )).thenReturn(createdItem(collectionId));

        service.execute(
                actionId,
                17L,
                new ActionRequests.Execute(
                        collectionId,
                        Map.of("priority", "P2"),
                        Map.of("title", "사용자 입력 제목")
                )
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> attributesCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(itemService).createInCollection(
                eq(17L),
                eq(collectionId),
                eq(Set.of("TODO")),
                eq("사용자 입력 제목"),
                attributesCaptor.capture()
        );
        assertThat(attributesCaptor.getValue())
                .containsEntry("priority", "P2");
    }

    @Test
    void multipleBlocksCreateOneItemWithAllBlockCodes() {
        UUID actionId = UUID.randomUUID();
        UUID collectionId = UUID.randomUUID();
        Action action = actionWithBlocks(
                actionId,
                true,
                BlockCode.TODO,
                BlockCode.MEMO
        );
        when(actionRepository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.of(action));
        when(itemService.createInCollection(
                eq(17L),
                eq(collectionId),
                anySet(),
                anyString(),
                anyMap()
        )).thenReturn(createdItem(collectionId));

        service.execute(
                actionId,
                17L,
                new ActionRequests.Execute(
                        collectionId,
                        Map.of(),
                        Map.of(
                                "title", "복합 아이템",
                                "content", "메모 내용"
                        )
                )
        );

        verify(itemService, times(1)).createInCollection(
                eq(17L),
                eq(collectionId),
                eq(Set.of("TODO", "MEMO")),
                eq("복합 아이템"),
                anyMap()
        );
    }

    @Test
    void disabledActionDoesNotCreateAnItem() {
        UUID actionId = UUID.randomUUID();
        when(actionRepository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.of(todoAction(actionId, false)));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.execute(
                        actionId,
                        17L,
                        new ActionRequests.Execute(
                                UUID.randomUUID(),
                                Map.of(),
                                Map.of()
                        )
                )
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verifyNoInteractions(itemService);
    }

    @Test
    void invalidInputDoesNotCreateAnItem() {
        UUID actionId = UUID.randomUUID();
        when(actionRepository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.of(todoAction(actionId, true)));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.execute(
                        actionId,
                        17L,
                        new ActionRequests.Execute(
                                UUID.randomUUID(),
                                Map.of(),
                                Map.of("unknown", "value")
                        )
                )
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(itemService);
    }

    @Test
    void missingRequiredTitleIsRejected() {
        UUID actionId = UUID.randomUUID();
        when(actionRepository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.of(todoAction(actionId, true)));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.execute(
                        actionId,
                        17L,
                        new ActionRequests.Execute(
                                UUID.randomUUID(),
                                Map.of(),
                                Map.of()
                        )
                )
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(itemService);
    }

    @Test
    void invalidPriorityIsRejected() {
        UUID actionId = UUID.randomUUID();
        when(actionRepository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.of(todoAction(actionId, true)));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.execute(
                        actionId,
                        17L,
                        new ActionRequests.Execute(
                                UUID.randomUUID(),
                                Map.of(),
                                Map.of(
                                        "title", "할 일",
                                        "priority", "P9"
                                )
                        )
                )
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(itemService);
    }

    @Test
    void invalidDateIsRejected() {
        UUID actionId = UUID.randomUUID();
        when(actionRepository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.of(todoAction(actionId, true)));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.execute(
                        actionId,
                        17L,
                        new ActionRequests.Execute(
                                UUID.randomUUID(),
                                Map.of(),
                                Map.of(
                                        "title", "할 일",
                                        "dueDate", "2026-02-30"
                                )
                        )
                )
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(itemService);
    }

    @Test
    void invalidTimeIsRejected() {
        UUID actionId = UUID.randomUUID();
        Action action = actionWithBlock(
                actionId,
                true,
                BlockCode.TIMETABLE
        );
        when(actionRepository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.of(action));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.execute(
                        actionId,
                        17L,
                        new ActionRequests.Execute(
                                UUID.randomUUID(),
                                Map.of(),
                                Map.of(
                                        "title", "자료구조",
                                        "dayOfWeek", "MONDAY",
                                        "startTime", "아침",
                                        "endTime", "10:00"
                                )
                        )
                )
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(itemService);
    }

    @Test
    void missingTargetCollectionIsRejected() {
        UUID actionId = UUID.randomUUID();
        when(actionRepository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.of(todoAction(actionId, true)));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.execute(
                        actionId,
                        17L,
                        new ActionRequests.Execute(
                                null,
                                Map.of(),
                                Map.of("title", "할 일")
                        )
                )
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(itemService);
    }

    @Test
    void invisibleActionCannotBeExecuted() {
        UUID actionId = UUID.randomUUID();
        when(actionRepository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.execute(
                        actionId,
                        17L,
                        new ActionRequests.Execute(
                                UUID.randomUUID(),
                                Map.of(),
                                Map.of("title", "할 일")
                        )
                )
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(itemService);
    }

    private Action todoAction(UUID actionId, boolean enabled) {
        return actionWithBlock(actionId, enabled, BlockCode.TODO);
    }

    private Action actionWithBlock(
            UUID actionId,
            boolean enabled,
            BlockCode blockCode
    ) {
        return actionWithBlocks(actionId, enabled, blockCode);
    }

    private Action actionWithBlocks(
            UUID actionId,
            boolean enabled,
            BlockCode... blockCodes
    ) {
        List<Action.BlockAssignment> assignments = new ArrayList<>();
        for (int index = 0; index < blockCodes.length; index++) {
            assignments.add(new Action.BlockAssignment(
                    blockCodes[index],
                    index
            ));
        }

        Action action = Action.builder()
                .ownerId(17L)
                .name("투두 만들기")
                .description("TODO 화면 생성")
                .enabled(enabled)
                .preset(false)
                .blockAssignments(assignments)
                .build();
        ReflectionTestUtils.setField(action, "id", actionId);
        return action;
    }

    private ItemResponse createdItem(UUID collectionId) {
        LocalDateTime now = LocalDateTime.of(2026, 4, 9, 12, 0);
        return new ItemResponse(
                UUID.randomUUID(),
                "사용자 입력 제목",
                17L,
                collectionId,
                Set.of("TODO"),
                Map.of(),
                now,
                now
        );
    }
}
