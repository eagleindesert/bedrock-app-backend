package com.bedrock.app.action.dto;

import com.bedrock.app.action.block.BlockCatalog;
import com.bedrock.app.action.block.BlockCatalog.ActionFieldGroup;
import com.bedrock.app.action.block.BlockCatalog.BlockFieldType;
import com.bedrock.app.action.domain.Action;
import com.bedrock.app.action.domain.ActionBlock;
import com.bedrock.app.action.domain.type.BlockCode;
import com.bedrock.app.item.dto.response.ItemResponse;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record ActionResponse(
        UUID id,
        String name,
        String description,
        boolean enabled,
        boolean preset,
        List<Block> blocks,
        List<InputField> inputFields,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static ActionResponse from(
            Action action,
            BlockCatalog blockCatalog
    ) {
        List<ActionBlock> orderedBlocks =
                new ArrayList<>(action.getBlocks());
        orderedBlocks.sort(
                Comparator.comparingInt(ActionBlock::getDisplayOrder)
        );

        List<BlockCode> blockCodes = new ArrayList<>();
        List<Block> blockResponses = new ArrayList<>();
        for (ActionBlock block : orderedBlocks) {
            blockCodes.add(block.getBlockCode());
            blockResponses.add(toBlockResponse(block, blockCatalog));
        }

        List<InputField> inputFields = new ArrayList<>();
        for (BlockCatalog.MergedField merged
                : blockCatalog.mergeFields(blockCodes)) {
            inputFields.add(toInputField(merged));
        }

        return new ActionResponse(
                action.getId(),
                action.getName(),
                action.getDescription(),
                action.isEnabled(),
                action.isPreset(),
                blockResponses,
                inputFields,
                action.getCreatedAt(),
                action.getUpdatedAt()
        );
    }

    private static Block toBlockResponse(
            ActionBlock actionBlock,
            BlockCatalog blockCatalog
    ) {
        BlockCatalog.Schema schema =
                blockCatalog.getSchema(actionBlock.getBlockCode());
        List<Field> fields = new ArrayList<>();
        for (BlockCatalog.Field field : schema.fields()) {
            fields.add(toField(field));
        }
        return new Block(
                schema.code(),
                schema.name(),
                actionBlock.getDisplayOrder(),
                List.copyOf(fields)
        );
    }

    private static Field toField(BlockCatalog.Field field) {
        return new Field(
                field.key(),
                field.label(),
                field.group(),
                field.type(),
                field.required(),
                field.defaultValue(),
                field.options()
        );
    }

    private static InputField toInputField(BlockCatalog.MergedField merged) {
        BlockCatalog.Field field = merged.field();
        return new InputField(
                field.key(),
                field.label(),
                field.group(),
                field.type(),
                field.required(),
                field.defaultValue(),
                field.options(),
                merged.sourceBlocks()
        );
    }

    public record Block(
            BlockCode blockCode,
            String blockName,
            int displayOrder,
            List<Field> fields
    ) {
    }

    public record Field(
            String key,
            String label,
            ActionFieldGroup group,
            BlockFieldType type,
            boolean required,
            Object defaultValue,
            List<String> options
    ) {
    }

    public record InputField(
            String key,
            String label,
            ActionFieldGroup group,
            BlockFieldType type,
            boolean required,
            Object defaultValue,
            List<String> options,
            List<BlockCode> sourceBlocks
    ) {
    }

    public record Execution(
            UUID actionId,
            UUID itemId,
            Status status,
            ItemResponse item
    ) {

        public enum Status {
            COMPLETED
        }
    }
}
