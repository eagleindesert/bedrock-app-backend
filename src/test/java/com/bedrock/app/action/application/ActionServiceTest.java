package com.bedrock.app.action.application;

import com.bedrock.app.action.block.BlockCatalog;
import com.bedrock.app.action.block.BlockCatalog.ActionFieldGroup;
import com.bedrock.app.action.block.BlockCatalog.BlockFieldType;
import com.bedrock.app.action.domain.Action;
import com.bedrock.app.action.domain.ActionRepository;
import com.bedrock.app.action.domain.type.BlockCode;
import com.bedrock.app.action.dto.ActionRequests;
import com.bedrock.app.action.dto.ActionResponse;
import com.bedrock.app.item.service.ItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ActionServiceTest {

    private ActionRepository repository;
    private ItemService itemService;
    private ActionService service;

    @BeforeEach
    void setUp() {
        repository = mock(ActionRepository.class);
        itemService = mock(ItemService.class);
        BlockCatalog catalog = new BlockCatalog();
        when(repository.save(any(Action.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new ActionService(
                repository,
                catalog,
                itemService
        );
    }

    @Test
    void missingDisplayOrdersAreAssignedInRequestOrder() {
        var response = service.create(
                17L,
                false,
                request(
                        false,
                        List.of(
                                new ActionRequests.Block(BlockCode.TODO, null),
                                new ActionRequests.Block(BlockCode.MEMO, null)
                        )
                )
        );

        assertThat(response.blocks())
                .extracting(block -> block.blockCode().name())
                .containsExactly("TODO", "MEMO");
        assertThat(response.blocks())
                .extracting(block -> block.displayOrder())
                .containsExactly(0, 1);
    }

    @Test
    void multipleBlocksExposeOneDeduplicatedInputFieldList() {
        var response = service.create(
                17L,
                false,
                request(
                        false,
                        List.of(
                                new ActionRequests.Block(BlockCode.TODO, 0),
                                new ActionRequests.Block(BlockCode.MEMO, 1)
                        )
                )
        );

        assertThat(response.inputFields())
                .extracting(field -> field.key())
                .containsExactly(
                        "title",
                        "dueDate",
                        "priority",
                        "tags",
                        "memo",
                        "completed",
                        "content"
                );

        var title = response.inputFields().stream()
                .filter(field -> field.key().equals("title"))
                .findFirst()
                .orElseThrow();
        assertThat(title.group()).isEqualTo(ActionFieldGroup.BASIC);
        assertThat(title.type()).isEqualTo(BlockFieldType.TEXT);
        assertThat(title.required()).isTrue();
        assertThat(title.sourceBlocks())
                .containsExactly(BlockCode.TODO, BlockCode.MEMO);

        var priority = response.inputFields().stream()
                .filter(field -> field.key().equals("priority"))
                .findFirst()
                .orElseThrow();
        assertThat(priority.defaultValue()).isEqualTo("P3");
        assertThat(priority.options())
                .containsExactly("P1", "P2", "P3", "P4");
    }

    @Test
    void sameBlockCannotBeAssignedTwice() {
        assertThatThrownBy(() -> service.create(
                17L,
                false,
                request(
                        false,
                        List.of(
                                new ActionRequests.Block(BlockCode.TODO, 0),
                                new ActionRequests.Block(BlockCode.TODO, 1)
                        )
                )
        ))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void displayOrderMustBeUnique() {
        assertThatThrownBy(() -> service.create(
                17L,
                false,
                request(
                        false,
                        List.of(
                                new ActionRequests.Block(BlockCode.TODO, 0),
                                new ActionRequests.Block(BlockCode.MEMO, 0)
                        )
                )
        ))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void nonAdminCannotCreatePreset() {
        assertThatThrownBy(() -> service.create(
                17L,
                false,
                request(
                        true,
                        List.of(new ActionRequests.Block(BlockCode.TODO, 0))
                )
        ))
                .isInstanceOf(ResponseStatusException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void adminCanCreatePreset() {
        ActionResponse response = service.create(
                17L,
                true,
                request(
                        true,
                        List.of(new ActionRequests.Block(BlockCode.TODO, 0))
                )
        );

        assertThat(response.preset()).isTrue();
        verify(repository).save(any(Action.class));
    }

    @Test
    void createUsesDefaultsWhenFlagsAreMissing() {
        ActionResponse response = service.create(
                17L,
                false,
                new ActionRequests.Create(
                        "기본 액션",
                        null,
                        null,
                        null,
                        List.of(new ActionRequests.Block(BlockCode.TODO, 0))
                )
        );

        assertThat(response.enabled()).isTrue();
        assertThat(response.preset()).isFalse();
    }

    @Test
    void createRejectsEmptyBlockList() {
        assertThatThrownBy(() -> service.create(
                17L,
                false,
                request(false, List.of())
        )).isInstanceOf(ResponseStatusException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void findOneReturnsVisibleAction() {
        UUID actionId = UUID.randomUUID();
        when(repository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.of(action(true)));

        ActionResponse response = service.findOne(actionId, 17L);

        assertThat(response.name()).isEqualTo("액션");
        assertThat(response.blocks()).hasSize(1);
    }

    @Test
    void findOneReturnsNotFoundWhenActionIsNotVisible() {
        UUID actionId = UUID.randomUUID();
        when(repository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.findOne(actionId, 17L)
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateHidesActionsThatAreNotVisibleToOwner() {
        UUID actionId = UUID.randomUUID();
        when(repository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(
                actionId,
                17L,
                false,
                new ActionRequests.Update(
                        "수정",
                        null,
                        null,
                        null,
                        null
                )
        ))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void updateChangesDefinitionAndBlocks() {
        UUID actionId = UUID.randomUUID();
        when(repository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.of(action(true)));

        var response = service.update(
                actionId,
                17L,
                false,
                new ActionRequests.Update(
                        "updated action",
                        "updated description",
                        false,
                        false,
                        List.of(new ActionRequests.Block(BlockCode.MEMO, 0))
                )
        );

        assertThat(response.name()).isEqualTo("updated action");
        assertThat(response.description()).isEqualTo("updated description");
        assertThat(response.enabled()).isFalse();
        assertThat(response.preset()).isFalse();
        assertThat(response.blocks())
                .extracting(block -> block.blockCode())
                .containsExactly(BlockCode.MEMO);
    }

    @Test
    void partialUpdateKeepsValuesThatWereNotProvided() {
        UUID actionId = UUID.randomUUID();
        Action original = Action.builder()
                .ownerId(17L)
                .name("기존 이름")
                .description("기존 설명")
                .enabled(true)
                .preset(false)
                .blockAssignments(List.of(
                        new Action.BlockAssignment(BlockCode.TODO, 0)
                ))
                .build();
        when(repository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.of(original));

        ActionResponse response = service.update(
                actionId,
                17L,
                false,
                new ActionRequests.Update(null, null, null, null, null)
        );

        assertThat(response.name()).isEqualTo("기존 이름");
        assertThat(response.description()).isEqualTo("기존 설명");
        assertThat(response.enabled()).isTrue();
        assertThat(response.preset()).isFalse();
        assertThat(response.blocks())
                .extracting(block -> block.blockCode())
                .containsExactly(BlockCode.TODO);
    }

    @Test
    void nonAdminCannotUpdatePreset() {
        UUID actionId = UUID.randomUUID();
        when(repository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.of(action(true, 99L, true)));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.update(
                        actionId,
                        17L,
                        false,
                        new ActionRequests.Update(
                                "수정",
                                null,
                                null,
                                null,
                                null
                        )
                )
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void nonAdminCannotPromoteOwnedActionToPreset() {
        UUID actionId = UUID.randomUUID();
        when(repository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.of(action(true)));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.update(
                        actionId,
                        17L,
                        false,
                        new ActionRequests.Update(
                                null,
                                null,
                                null,
                                true,
                                null
                        )
                )
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminCanUpdatePreset() {
        UUID actionId = UUID.randomUUID();
        when(repository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.of(action(true, 99L, true)));

        ActionResponse response = service.update(
                actionId,
                17L,
                true,
                new ActionRequests.Update(
                        "관리자 수정",
                        null,
                        null,
                        null,
                        null
                )
        );

        assertThat(response.name()).isEqualTo("관리자 수정");
        assertThat(response.preset()).isTrue();
    }

    @Test
    void deleteSoftDeletesOwnedAction() {
        UUID actionId = UUID.randomUUID();
        Action ownedAction = action(true);
        when(repository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.of(ownedAction));

        service.delete(actionId, 17L, false);

        assertThat(ownedAction.getDeletedAt()).isNotNull();
    }

    @Test
    void nonAdminCannotDeletePreset() {
        UUID actionId = UUID.randomUUID();
        Action preset = action(true, 99L, true);
        when(repository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.of(preset));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.delete(actionId, 17L, false)
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(preset.getDeletedAt()).isNull();
    }

    @Test
    void adminCanDeletePreset() {
        UUID actionId = UUID.randomUUID();
        Action preset = action(true, 99L, true);
        when(repository.findVisibleById(actionId, 17L))
                .thenReturn(Optional.of(preset));

        service.delete(actionId, 17L, true);

        assertThat(preset.getDeletedAt()).isNotNull();
    }

    @Test
    void availableScopeIncludesVisibleActions() {
        when(repository.findAllVisible(17L))
                .thenReturn(List.of(
                        action(false, 17L, false),
                        action(true, 99L, true)
                ));

        assertThat(service.findAll(17L, "available")).hasSize(2);
    }

    @Test
    void missingScopeUsesAvailableScope() {
        when(repository.findAllVisible(17L))
                .thenReturn(List.of(
                        action(false, 17L, false),
                        action(true, 99L, true)
                ));

        assertThat(service.findAll(17L, null)).hasSize(2);
    }

    @Test
    void meScopeIncludesOnlyOwnedNonPresetActions() {
        when(repository.findAllVisible(17L))
                .thenReturn(List.of(
                        action(true, 17L, false),
                        action(true, 99L, true)
                ));

        assertThat(service.findAll(17L, "me"))
                .singleElement()
                .extracting(ActionResponse::preset)
                .isEqualTo(false);
    }

    @Test
    void presetScopeIncludesOnlyPresetActions() {
        when(repository.findAllVisible(17L))
                .thenReturn(List.of(
                        action(true, 17L, false),
                        action(true, 99L, true)
                ));

        assertThat(service.findAll(17L, "preset"))
                .singleElement()
                .extracting(ActionResponse::preset)
                .isEqualTo(true);
    }

    @Test
    void scopeIsCaseInsensitive() {
        when(repository.findAllVisible(17L)).thenReturn(List.of(action(true)));

        assertThat(service.findAll(17L, "ME")).hasSize(1);
    }

    @Test
    void invalidScopeIsRejected() {
        assertThatThrownBy(() -> service.findAll(17L, "mine"))
                .isInstanceOf(ResponseStatusException.class);
    }

    private ActionRequests.Create request(
            boolean preset,
            List<ActionRequests.Block> blocks
    ) {
        return new ActionRequests.Create(
                "액션",
                null,
                true,
                preset,
                blocks
        );
    }

    private Action action(boolean enabled) {
        return action(enabled, 17L, false);
    }

    private Action action(boolean enabled, Long ownerId, boolean preset) {
        return Action.builder()
                .ownerId(ownerId)
                .name("액션")
                .enabled(enabled)
                .preset(preset)
                .blockAssignments(List.of(
                        new Action.BlockAssignment(BlockCode.TODO, 0)
                ))
                .build();
    }
}
