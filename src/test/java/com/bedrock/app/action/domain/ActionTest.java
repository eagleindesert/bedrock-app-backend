package com.bedrock.app.action.domain;

import com.bedrock.app.action.domain.type.BlockCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActionTest {

    @Test
    void replacingBlocksKeepsExistingRowsAndOnlyAddsOrRemovesDifferences() {
        Action action = Action.builder()
                .ownerId(17L)
                .name("복합 액션")
                .enabled(true)
                .blockAssignments(List.of(
                        new Action.BlockAssignment(BlockCode.TODO, 0),
                        new Action.BlockAssignment(BlockCode.MEMO, 1)
                ))
                .build();

        ActionBlock existingTodo = action.getBlocks().stream()
                .filter(block -> block.getBlockCode() == BlockCode.TODO)
                .findFirst()
                .orElseThrow();

        action.replaceBlocks(List.of(
                new Action.BlockAssignment(BlockCode.SCHEDULE, 0),
                new Action.BlockAssignment(BlockCode.TODO, 1)
        ));

        ActionBlock updatedTodo = action.getBlocks().stream()
                .filter(block -> block.getBlockCode() == BlockCode.TODO)
                .findFirst()
                .orElseThrow();

        assertThat(updatedTodo).isSameAs(existingTodo);
        assertThat(updatedTodo.getDisplayOrder()).isEqualTo(1);
        assertThat(action.getBlocks())
                .extracting(ActionBlock::getBlockCode)
                .containsExactlyInAnyOrder(BlockCode.SCHEDULE, BlockCode.TODO)
                .doesNotContain(BlockCode.MEMO);
    }
}
