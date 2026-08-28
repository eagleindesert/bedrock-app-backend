package com.bedrock.app.action.dto;

import com.bedrock.app.action.block.BlockCatalog;
import com.bedrock.app.action.block.BlockCatalog.ActionFieldGroup;
import com.bedrock.app.action.block.BlockCatalog.BlockFieldType;
import com.bedrock.app.action.domain.Action;
import com.bedrock.app.action.domain.ActionBlock;
import com.bedrock.app.action.domain.type.BlockCode;
import com.bedrock.app.item.dto.response.ItemResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Schema(description = "액션 응답")
public record ActionResponse(
        @Schema(description = "액션 ID (조회·실행 경로에 사용)",
                example = "835c78c3-8d10-4f26-a40b-9c8cf0599886")
        UUID id,

        @Schema(description = "액션 이름", example = "투두 만들기")
        String name,

        @Schema(description = "액션 설명", example = "새 할 일을 만드는 입력 정의")
        String description,

        @Schema(description = "활성 여부. 비활성 액션을 실행하면 409 다.", example = "true")
        boolean enabled,

        @Schema(description = "프리셋 여부", example = "false")
        boolean preset,

        @Schema(description = "각 Block 의 원본 필드 정의")
        List<Block> blocks,

        @Schema(description = "여러 Block 의 같은 키를 한 번만 남긴 실제 단일 입력 폼 계약")
        List<InputField> inputFields,

        @Schema(description = "생성 일시", example = "2026-04-09T12:00:00")
        LocalDateTime createdAt,

        @Schema(description = "수정 일시", example = "2026-04-09T12:00:00")
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

    @Schema(name = "ActionBlockResponse", description = "액션에 포함된 Block 과 원본 필드")
    public record Block(
            @Schema(description = "Block 코드", example = "TODO")
            BlockCode blockCode,

            @Schema(description = "Block 이름", example = "할 일")
            String blockName,

            @Schema(description = "UI 표시 순서 (실행 순서가 아님)", example = "0")
            int displayOrder,

            @Schema(description = "Block 의 원본 필드 목록")
            List<Field> fields
    ) {
    }

    @Schema(name = "ActionFieldResponse", description = "Block 필드 정의")
    public record Field(
            @Schema(description = "필드 키", example = "title")
            String key,

            @Schema(description = "필드 라벨", example = "제목")
            String label,

            @Schema(description = "필드 의미 그룹", example = "BASIC")
            ActionFieldGroup group,

            @Schema(description = "필드 자료형", example = "TEXT")
            BlockFieldType type,

            @Schema(description = "필수 여부", example = "true")
            boolean required,

            @Schema(description = "기본값", example = "P3")
            Object defaultValue,

            @Schema(description = "선택 가능한 값 목록 (enum 형 필드)",
                    example = "[\"P1\", \"P2\", \"P3\", \"P4\"]")
            List<String> options
    ) {
    }

    @Schema(name = "ActionInputFieldResponse",
            description = "여러 Block 의 필드를 병합한 실제 입력 폼 필드")
    public record InputField(
            @Schema(description = "필드 키", example = "title")
            String key,

            @Schema(description = "필드 라벨", example = "제목")
            String label,

            @Schema(description = "필드 의미 그룹", example = "BASIC")
            ActionFieldGroup group,

            @Schema(description = "필드 자료형", example = "TEXT")
            BlockFieldType type,

            @Schema(description = "필수 여부", example = "true")
            boolean required,

            @Schema(description = "기본값", example = "P3")
            Object defaultValue,

            @Schema(description = "선택 가능한 값 목록 (enum 형 필드)",
                    example = "[\"P1\", \"P2\", \"P3\", \"P4\"]")
            List<String> options,

            @Schema(description = "이 필드가 유래한 Block 목록", example = "[\"TODO\"]")
            List<BlockCode> sourceBlocks
    ) {
    }

    @Schema(name = "ActionExecutionResponse", description = "액션 실행 결과")
    public record Execution(
            @Schema(description = "실행한 액션 ID", example = "835c78c3-8d10-4f26-a40b-9c8cf0599886")
            UUID actionId,

            @Schema(description = "생성된 아이템 ID (이 ID 로 Item DELETE 를 호출해 실행을 취소할 수 있다)",
                    example = "3f9a1c2e-7b4d-4e8a-9c1f-0a2b3c4d5e6f")
            UUID itemId,

            @Schema(description = "실행 상태", example = "COMPLETED")
            Status status,

            @Schema(description = "생성된 아이템 전체")
            ItemResponse item
    ) {

        public enum Status {
            COMPLETED
        }
    }
}
