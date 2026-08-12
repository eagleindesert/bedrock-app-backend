package com.bedrock.app.action.block;

import com.bedrock.app.action.domain.type.BlockCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
public class BlockCatalog {

    public enum ActionFieldGroup {
        BASIC,
        SCHEDULE,
        TODO,
        NOTE
    }

    public enum BlockFieldType {
        TEXT,
        DATE,
        TIME,
        ENUM,
        STRING_LIST,
        MARKDOWN,
        BOOLEAN,
        INTEGER
    }

    private static final Field TITLE =
            field("title", "제목", ActionFieldGroup.BASIC, BlockFieldType.TEXT, true);
    private static final Field MEMO =
            field("memo", "메모", ActionFieldGroup.NOTE, BlockFieldType.MARKDOWN, false);
    private static final Field TAGS =
            field(
                    "tags",
                    "태그",
                    ActionFieldGroup.BASIC,
                    BlockFieldType.STRING_LIST,
                    false,
                    List.of(),
                    List.of()
            );

    private final Map<BlockCode, Schema> schemas;

    public BlockCatalog() {
        EnumMap<BlockCode, Schema> definitions = new EnumMap<>(BlockCode.class);
        definitions.put(BlockCode.TODO, todoSchema());
        definitions.put(BlockCode.SCHEDULE, scheduleSchema());
        definitions.put(BlockCode.MEMO, memoSchema());
        definitions.put(BlockCode.TIMETABLE, timetableSchema());
        schemas = Map.copyOf(definitions);
    }

    public Schema getSchema(BlockCode blockCode) {
        Schema schema = schemas.get(blockCode);
        if (schema == null) {
            throw new IllegalArgumentException("Unknown blockCode: " + blockCode);
        }
        return schema;
    }

    public List<MergedField> mergeFields(List<BlockCode> blockCodes) {
        if (blockCodes == null || blockCodes.isEmpty()) {
            throw new IllegalArgumentException("Action must have at least one block");
        }

        LinkedHashMap<String, FieldSources> merged = new LinkedHashMap<>();
        for (BlockCode blockCode : blockCodes) {
            for (Field field : getSchema(blockCode).fields()) {
                FieldSources sources = merged.get(field.key());
                if (sources == null) {
                    sources = new FieldSources(field);
                    merged.put(field.key(), sources);
                } else if (!sources.field.equals(field)) {
                    throw new IllegalArgumentException(
                            "Conflicting block field: " + field.key()
                    );
                }
                sources.blockCodes.add(blockCode);
            }
        }

        List<MergedField> result = new ArrayList<>();
        for (FieldSources sources : merged.values()) {
            result.add(new MergedField(
                    sources.field,
                    List.copyOf(sources.blockCodes)
            ));
        }
        return List.copyOf(result);
    }

    private Schema todoSchema() {
        return new Schema(
                BlockCode.TODO,
                "투두 블럭",
                List.of(
                        TITLE,
                        field(
                                "dueDate",
                                "마감일",
                                ActionFieldGroup.TODO,
                                BlockFieldType.DATE,
                                false
                        ),
                        field(
                                "priority",
                                "우선순위",
                                ActionFieldGroup.TODO,
                                BlockFieldType.ENUM,
                                false,
                                "P3",
                                List.of("P1", "P2", "P3", "P4")
                        ),
                        TAGS,
                        MEMO,
                        field(
                                "completed",
                                "완료",
                                ActionFieldGroup.TODO,
                                BlockFieldType.BOOLEAN,
                                false,
                                false,
                                List.of()
                        )
                )
        );
    }

    private Schema scheduleSchema() {
        return new Schema(
                BlockCode.SCHEDULE,
                "일정 블럭",
                List.of(
                        TITLE,
                        field(
                                "startDate",
                                "시작일",
                                ActionFieldGroup.SCHEDULE,
                                BlockFieldType.DATE,
                                true
                        ),
                        field(
                                "endDate",
                                "종료일",
                                ActionFieldGroup.SCHEDULE,
                                BlockFieldType.DATE,
                                false
                        ),
                        field(
                                "startTime",
                                "시작 시간",
                                ActionFieldGroup.SCHEDULE,
                                BlockFieldType.TIME,
                                false
                        ),
                        field(
                                "endTime",
                                "종료 시간",
                                ActionFieldGroup.SCHEDULE,
                                BlockFieldType.TIME,
                                false
                        ),
                        field(
                                "allDay",
                                "종일",
                                ActionFieldGroup.SCHEDULE,
                                BlockFieldType.BOOLEAN,
                                false,
                                false,
                                List.of()
                        ),
                        MEMO
                )
        );
    }

    private Schema memoSchema() {
        return new Schema(
                BlockCode.MEMO,
                "메모 블럭",
                List.of(
                        TITLE,
                        field(
                                "content",
                                "내용",
                                ActionFieldGroup.NOTE,
                                BlockFieldType.MARKDOWN,
                                false
                        ),
                        TAGS
                )
        );
    }

    private Schema timetableSchema() {
        return new Schema(
                BlockCode.TIMETABLE,
                "시간표 블럭",
                List.of(
                        TITLE,
                        field(
                                "dayOfWeek",
                                "요일",
                                ActionFieldGroup.SCHEDULE,
                                BlockFieldType.ENUM,
                                true,
                                null,
                                List.of(
                                        "MONDAY",
                                        "TUESDAY",
                                        "WEDNESDAY",
                                        "THURSDAY",
                                        "FRIDAY",
                                        "SATURDAY",
                                        "SUNDAY"
                                )
                        ),
                        field(
                                "startTime",
                                "시작 시간",
                                ActionFieldGroup.SCHEDULE,
                                BlockFieldType.TIME,
                                true
                        ),
                        field(
                                "endTime",
                                "종료 시간",
                                ActionFieldGroup.SCHEDULE,
                                BlockFieldType.TIME,
                                true
                        ),
                        field(
                                "location",
                                "장소",
                                ActionFieldGroup.BASIC,
                                BlockFieldType.TEXT,
                                false
                        ),
                        MEMO
                )
        );
    }

    private static Field field(
            String key,
            String label,
            ActionFieldGroup group,
            BlockFieldType type,
            boolean required
    ) {
        return field(key, label, group, type, required, null, List.of());
    }

    private static Field field(
            String key,
            String label,
            ActionFieldGroup group,
            BlockFieldType type,
            boolean required,
            Object defaultValue,
            List<String> options
    ) {
        return new Field(
                key,
                label,
                group,
                type,
                required,
                defaultValue,
                options
        );
    }

    public record MergedField(
            Field field,
            List<BlockCode> sourceBlocks
    ) {
        public MergedField {
            sourceBlocks = List.copyOf(sourceBlocks);
        }
    }

    private static final class FieldSources {

        private final Field field;
        private final LinkedHashSet<BlockCode> blockCodes = new LinkedHashSet<>();

        private FieldSources(Field field) {
            this.field = field;
        }
    }

    public record Schema(
            BlockCode code,
            String name,
            List<Field> fields
    ) {
        public Schema {
            fields = List.copyOf(fields);
        }
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
        public Field {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }
}
