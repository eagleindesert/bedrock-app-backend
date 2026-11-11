package com.bedrock.app.common;

import jakarta.persistence.Entity;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.spi.MetadataImplementor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB 연결 없이도 전체 JPA 매핑의 컬럼·타입·NULL 허용 여부를 정본 SQL과 대조한다.
 * 정본은 V5의 전체 스키마에 V6부터의 공통 보정(CREATE TABLE · ADD COLUMN · DROP COLUMN · DROP NOT NULL)을 차례로 얹은 것이다.
 */
class CanonicalModelSchemaTest {
    private static final int CANONICAL_TABLE_COUNT = 31;
    private static final String TYPES = "UUID|VARCHAR|TEXT|INT|BIGINT|SMALLINT|TIMESTAMPTZ|BYTEA|BOOLEAN|DATE|TIME|JSONB";
    private static final Pattern TABLE = Pattern.compile(
            "CREATE TABLE IF NOT EXISTS (\\w+) \\(\\n([\\s\\S]*?)\\n\\);");
    private static final Pattern COLUMN = Pattern.compile(
            "(?m)^\\s+(\\w+)\\s+(" + TYPES + ")\\b([^\\n]*)");
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "(?m)^ALTER TABLE (\\w+) ADD COLUMN (\\w+) (" + TYPES + ")\\b([^;\\n]*);");
    private static final Pattern DROP_COLUMN = Pattern.compile(
            "(?m)^ALTER TABLE (\\w+) DROP COLUMN (\\w+);");
    private static final Pattern DROP_NOT_NULL = Pattern.compile(
            "(?m)^ALTER TABLE (\\w+) ALTER COLUMN (\\w+) DROP NOT NULL;");
    private static final List<String> COMMON_MIGRATIONS = List.of(
            "V5__init_workspace_schema.sql",
            "V6__align_with_api_v1.sql");

    @Test
    void allEntitiesMatchTheCanonicalSchema() throws Exception {
        var expected = schemaColumns();
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .build();
        try {
            var sources = new MetadataSources(registry);
            var scanner = new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
            for (var candidate : scanner.findCandidateComponents("com.bedrock.app")) {
                sources.addAnnotatedClass(Class.forName(candidate.getBeanClassName()));
            }
            var metadata = (MetadataImplementor) sources.buildMetadata();
            metadata.validate();
            assertThat(metadata.getEntityBindings()).hasSize(CANONICAL_TABLE_COUNT);
            assertThat(expected).hasSize(CANONICAL_TABLE_COUNT);
            for (var entity : metadata.getEntityBindings()) {
                var table = entity.getTable();
                var expectedColumns = expected.get(table.getName());
                assertThat(expectedColumns).as(table.getName()).isNotNull();
                assertThat(table.getColumns()).extracting(org.hibernate.mapping.Column::getName)
                        .containsExactlyInAnyOrderElementsOf(expectedColumns.keySet());
                for (var column : table.getColumns()) {
                    var expectation = expectedColumns.get(column.getName());
                    assertThat(normalizeType(column.getSqlType(metadata)))
                            .as("%s.%s type", table.getName(), column.getName()).isEqualTo(expectation.type());
                    assertThat(column.isNullable())
                            .as("%s.%s nullable", table.getName(), column.getName()).isEqualTo(expectation.nullable());
                    if (expectation.length() != null) {
                        assertThat(column.getLength()).as("%s.%s length", table.getName(), column.getName())
                                .isEqualTo(expectation.length().longValue());
                    }
                }
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private Map<String, Map<String, ColumnSpec>> schemaColumns() throws Exception {
        var tables = new LinkedHashMap<String, Map<String, ColumnSpec>>();
        for (String migration : COMMON_MIGRATIONS) {
            applyMigration(tables, readMigration(migration));
        }
        return tables;
    }

    private String readMigration(String fileName) throws Exception {
        try (var input = getClass().getResourceAsStream("/db/migration/" + fileName)) {
            assertThat(input).as(fileName).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void applyMigration(Map<String, Map<String, ColumnSpec>> tables, String sql) {
        var tableMatcher = TABLE.matcher(sql);
        while (tableMatcher.find()) {
            var columns = new LinkedHashMap<String, ColumnSpec>();
            var columnMatcher = COLUMN.matcher(tableMatcher.group(2));
            while (columnMatcher.find()) {
                columns.put(columnMatcher.group(1), columnSpec(columnMatcher.group(2), columnMatcher.group(3)));
            }
            tables.put(tableMatcher.group(1), columns);
        }
        var added = ADD_COLUMN.matcher(sql);
        while (added.find()) {
            existing(tables, added.group(1)).put(added.group(2), columnSpec(added.group(3), added.group(4)));
        }
        var dropped = DROP_COLUMN.matcher(sql);
        while (dropped.find()) {
            assertThat(existing(tables, dropped.group(1)).remove(dropped.group(2)))
                    .as("%s.%s", dropped.group(1), dropped.group(2)).isNotNull();
        }
        var nullable = DROP_NOT_NULL.matcher(sql);
        while (nullable.find()) {
            var columns = existing(tables, nullable.group(1));
            var column = columns.get(nullable.group(2));
            assertThat(column).as("%s.%s", nullable.group(1), nullable.group(2)).isNotNull();
            columns.put(nullable.group(2), new ColumnSpec(column.type(), true, column.length()));
        }
    }

    private Map<String, ColumnSpec> existing(Map<String, Map<String, ColumnSpec>> tables, String table) {
        var columns = tables.get(table);
        assertThat(columns).as(table).isNotNull();
        return columns;
    }

    private ColumnSpec columnSpec(String type, String declaration) {
        var length = Pattern.compile("^\\((\\d+)\\)").matcher(declaration);
        return new ColumnSpec(type,
                !declaration.contains("NOT NULL") && !declaration.contains("PRIMARY KEY"),
                length.find() ? Integer.parseInt(length.group(1)) : null);
    }

    private String normalizeType(String sqlType) {
        String type = sqlType.toUpperCase(Locale.ROOT);
        if (type.startsWith("TIMESTAMP") && type.contains("WITH TIME ZONE")) return "TIMESTAMPTZ";
        if (type.startsWith("VARCHAR")) return "VARCHAR";
        if (type.startsWith("TIME(")) return "TIME";
        if (type.equals("INTEGER")) return "INT";
        return type;
    }

    private record ColumnSpec(String type, boolean nullable, Integer length) {
    }
}
