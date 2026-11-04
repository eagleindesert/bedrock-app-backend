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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** DB 연결 없이도 전체 JPA 매핑의 컬럼·타입·NULL 허용 여부를 정본 SQL과 대조한다. */
class CanonicalModelSchemaTest {
    private static final Pattern TABLE = Pattern.compile(
            "CREATE TABLE IF NOT EXISTS (\\w+) \\(\\n([\\s\\S]*?)\\n\\);");
    private static final Pattern COLUMN = Pattern.compile(
            "(?m)^\\s+(\\w+)\\s+(UUID|VARCHAR|TEXT|INT|BIGINT|SMALLINT|TIMESTAMPTZ|BYTEA|BOOLEAN|DATE|TIME|JSONB)\\b([^\\n]*)");

    @Test
    void allTwentySixEntitiesMatchTheCanonicalSchema() throws Exception {
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
            assertThat(metadata.getEntityBindings()).hasSize(26);
            assertThat(expected).hasSize(26);
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
        String sql;
        try (var input = getClass().getResourceAsStream("/db/migration/V5__init_workspace_schema.sql")) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var tables = new LinkedHashMap<String, Map<String, ColumnSpec>>();
        var tableMatcher = TABLE.matcher(sql);
        while (tableMatcher.find()) {
            var columns = new LinkedHashMap<String, ColumnSpec>();
            var columnMatcher = COLUMN.matcher(tableMatcher.group(2));
            while (columnMatcher.find()) {
                String declaration = columnMatcher.group(3);
                var length = Pattern.compile("^\\((\\d+)\\)").matcher(declaration);
                columns.put(columnMatcher.group(1), new ColumnSpec(columnMatcher.group(2),
                        !declaration.contains("NOT NULL") && !declaration.contains("PRIMARY KEY"),
                        length.find() ? Integer.parseInt(length.group(1)) : null));
            }
            tables.put(tableMatcher.group(1), columns);
        }
        return tables;
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
