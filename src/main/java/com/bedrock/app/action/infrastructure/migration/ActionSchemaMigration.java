package com.bedrock.app.action.infrastructure.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ActionSchemaMigration implements ApplicationRunner {

    private static final String ACTION_STEPS = "action_steps";
    private static final String ACTIONS = "actions";
    private static final String DISPLAY_ORDER = "display_order";
    private static final String STEP_ORDER = "step_order";
    private static final String ORDER_CONSTRAINT =
            "uk_action_steps_action_display_order";
    private static final String ACTION_INDEX = "idx_action_steps_action";

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
                SELECT pg_advisory_xact_lock(
                    hashtext('bedrock_action_schema')
                )
                """);

        boolean migrateActionSteps = tableExists(ACTION_STEPS)
                && !hasFinalActionStepsSchema();
        boolean dropEndView = tableExists(ACTIONS)
                && columnExists(ACTIONS, "end_view");
        if (!migrateActionSteps && !dropEndView) {
            return;
        }

        if (dropEndView) {
            jdbcTemplate.execute("LOCK TABLE actions IN ACCESS EXCLUSIVE MODE");
        }
        if (migrateActionSteps) {
            jdbcTemplate.execute(
                    "LOCK TABLE action_steps IN ACCESS EXCLUSIVE MODE"
            );
        }

        if (dropEndView) {
            jdbcTemplate.execute(
                    "ALTER TABLE actions DROP COLUMN IF EXISTS end_view"
            );
            log.info("Dropped legacy actions.end_view column");
        }
        if (migrateActionSteps) {
            migrateDisplayOrder();
        }
    }

    private boolean hasFinalActionStepsSchema() {
        if (columnExists(ACTION_STEPS, STEP_ORDER)
                || !columnExists(ACTION_STEPS, DISPLAY_ORDER)
                || !columnIsNotNull(ACTION_STEPS, DISPLAY_ORDER)) {
            return false;
        }
        List<UniqueConstraintInfo> constraints =
                findDisplayOrderConstraints();
        boolean canonicalConstraint = constraints.size() == 1
                && ORDER_CONSTRAINT.equals(constraints.get(0).name())
                && constraints.get(0).deferrable()
                && constraints.get(0).initiallyDeferred();
        return canonicalConstraint
                && findStandaloneDisplayOrderUniqueIndexes().isEmpty()
                && isCanonicalActionIndex(findActionIndex());
    }

    private void migrateDisplayOrder() {
        boolean hasStepOrder = columnExists(ACTION_STEPS, STEP_ORDER);
        boolean hasDisplayOrder = columnExists(ACTION_STEPS, DISPLAY_ORDER);

        if (hasStepOrder && !hasDisplayOrder) {
            jdbcTemplate.execute("""
                    ALTER TABLE action_steps
                    RENAME COLUMN step_order TO display_order
                    """);
            hasStepOrder = false;
            hasDisplayOrder = true;
            log.info("Renamed action_steps.step_order to display_order");
        } else if (hasStepOrder && hasDisplayOrder) {
            validatePartiallyMigratedOrders();
            jdbcTemplate.update("""
                    UPDATE action_steps
                    SET display_order = step_order
                    WHERE display_order IS NULL
                    """);
            jdbcTemplate.execute("""
                    ALTER TABLE action_steps
                    DROP COLUMN step_order
                    """);
            hasStepOrder = false;
            log.info("Merged action_steps.step_order into display_order");
        }

        if (!hasDisplayOrder) {
            if (rowCount(ACTION_STEPS) > 0) {
                throw new IllegalStateException(
                        "action_steps has rows but no display_order or step_order"
                );
            }
            jdbcTemplate.execute("""
                    ALTER TABLE action_steps
                    ADD COLUMN display_order integer
                    """);
            log.info("Added action_steps.display_order column");
        }

        validateFinalDisplayOrders();
        jdbcTemplate.execute("""
                ALTER TABLE action_steps
                ALTER COLUMN display_order SET NOT NULL
                """);
        ensureDeferredDisplayOrderConstraint();
        ensureActionIndex();
    }

    private void validatePartiallyMigratedOrders() {
        long conflicts = queryCount("""
                SELECT count(*)
                FROM action_steps
                WHERE display_order IS NOT NULL
                  AND step_order IS NOT NULL
                  AND display_order <> step_order
                """);
        if (conflicts > 0) {
            throw new IllegalStateException(
                    "action_steps contains conflicting step_order and display_order values"
            );
        }

        long invalidOrders = queryCount("""
                SELECT count(*)
                FROM action_steps
                WHERE COALESCE(display_order, step_order) IS NULL
                   OR COALESCE(display_order, step_order) < 0
                """);
        if (invalidOrders > 0) {
            throw new IllegalStateException(
                    "action_steps contains null or negative display orders"
            );
        }

        long duplicates = queryCount("""
                SELECT count(*)
                FROM (
                    SELECT action_id, COALESCE(display_order, step_order)
                    FROM action_steps
                    GROUP BY action_id, COALESCE(display_order, step_order)
                    HAVING count(*) > 1
                ) duplicated_orders
                """);
        if (duplicates > 0) {
            throw new IllegalStateException(
                    "action_steps contains duplicate display orders"
            );
        }
    }

    private void validateFinalDisplayOrders() {
        long invalidOrders = queryCount("""
                SELECT count(*)
                FROM action_steps
                WHERE display_order IS NULL OR display_order < 0
                """);
        if (invalidOrders > 0) {
            throw new IllegalStateException(
                    "action_steps contains null or negative display orders"
            );
        }

        long duplicates = queryCount("""
                SELECT count(*)
                FROM (
                    SELECT action_id, display_order
                    FROM action_steps
                    GROUP BY action_id, display_order
                    HAVING count(*) > 1
                ) duplicated_orders
                """);
        if (duplicates > 0) {
            throw new IllegalStateException(
                    "action_steps contains duplicate display orders"
            );
        }
    }

    private void ensureDeferredDisplayOrderConstraint() {
        List<UniqueConstraintInfo> matchingConstraints =
                findDisplayOrderConstraints();
        boolean alreadyCanonical = matchingConstraints.size() == 1
                && ORDER_CONSTRAINT.equals(matchingConstraints.get(0).name())
                && matchingConstraints.get(0).deferrable()
                && matchingConstraints.get(0).initiallyDeferred();
        dropStandaloneDisplayOrderUniqueIndexes();
        if (alreadyCanonical) {
            return;
        }

        for (UniqueConstraintInfo constraint : matchingConstraints) {
            jdbcTemplate.execute(
                    "ALTER TABLE action_steps DROP CONSTRAINT "
                            + quoteIdentifier(constraint.name())
            );
        }
        jdbcTemplate.execute("""
                ALTER TABLE action_steps
                ADD CONSTRAINT uk_action_steps_action_display_order
                UNIQUE (action_id, display_order)
                DEFERRABLE INITIALLY DEFERRED
                """);
        log.info("Aligned action_steps display-order unique constraint");
    }

    private List<UniqueConstraintInfo> findDisplayOrderConstraints() {
        return jdbcTemplate.query("""
                SELECT
                    tc.constraint_name,
                    tc.is_deferrable,
                    tc.initially_deferred,
                    string_agg(
                        kcu.column_name,
                        ',' ORDER BY kcu.ordinal_position
                    ) AS column_names
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_catalog = kcu.constraint_catalog
                 AND tc.constraint_schema = kcu.constraint_schema
                 AND tc.constraint_name = kcu.constraint_name
                 AND tc.table_name = kcu.table_name
                WHERE tc.table_schema = current_schema()
                  AND tc.table_name = ?
                  AND tc.constraint_type = 'UNIQUE'
                GROUP BY
                    tc.constraint_name,
                    tc.is_deferrable,
                    tc.initially_deferred
                HAVING string_agg(
                    kcu.column_name,
                    ',' ORDER BY kcu.ordinal_position
                ) IN (
                    'action_id,display_order',
                    'display_order,action_id'
                )
                """,
                (resultSet, rowNumber) -> new UniqueConstraintInfo(
                        resultSet.getString("constraint_name"),
                        "YES".equals(resultSet.getString("is_deferrable")),
                        "YES".equals(resultSet.getString("initially_deferred"))
                ),
                ACTION_STEPS
        );
    }

    private void dropStandaloneDisplayOrderUniqueIndexes() {
        String schema = currentSchema();
        for (String indexName : findStandaloneDisplayOrderUniqueIndexes()) {
            jdbcTemplate.execute(
                    "DROP INDEX " + quoteIdentifier(schema) + "."
                            + quoteIdentifier(indexName)
            );
        }
    }

    private List<String> findStandaloneDisplayOrderUniqueIndexes() {
        return jdbcTemplate.query("""
                SELECT index_class.relname AS index_name
                FROM pg_class table_class
                JOIN pg_namespace table_namespace
                  ON table_namespace.oid = table_class.relnamespace
                JOIN pg_index index_metadata
                  ON index_metadata.indrelid = table_class.oid
                JOIN pg_class index_class
                  ON index_class.oid = index_metadata.indexrelid
                LEFT JOIN pg_constraint constraint_metadata
                  ON constraint_metadata.conindid = index_class.oid
                JOIN LATERAL unnest(index_metadata.indkey)
                     WITH ORDINALITY AS index_key(attnum, position)
                  ON index_key.position <= index_metadata.indnkeyatts
                JOIN pg_attribute column_metadata
                  ON column_metadata.attrelid = table_class.oid
                 AND column_metadata.attnum = index_key.attnum
                WHERE table_namespace.nspname = current_schema()
                  AND table_class.relname = ?
                  AND index_metadata.indisunique
                  AND index_metadata.indexprs IS NULL
                  AND index_metadata.indpred IS NULL
                  AND index_metadata.indnkeyatts = 2
                  AND constraint_metadata.oid IS NULL
                GROUP BY index_class.relname
                HAVING string_agg(
                    column_metadata.attname,
                    ',' ORDER BY index_key.position
                ) IN (
                    'action_id,display_order',
                    'display_order,action_id'
                )
                """,
                (resultSet, rowNumber) -> resultSet.getString("index_name"),
                ACTION_STEPS
        );
    }

    private void ensureActionIndex() {
        IndexInfo index = findActionIndex();
        if (isCanonicalActionIndex(index)) {
            return;
        }

        if (index != null) {
            if (index.constraintBacked()) {
                throw new IllegalStateException(
                        "idx_action_steps_action is owned by a database constraint"
                );
            }
            jdbcTemplate.execute(
                    "DROP INDEX " + quoteIdentifier(currentSchema()) + "."
                            + quoteIdentifier(ACTION_INDEX)
            );
        }
        jdbcTemplate.execute("""
                CREATE INDEX idx_action_steps_action
                ON action_steps (action_id, display_order)
                """);
        log.info("Aligned idx_action_steps_action index");
    }

    private IndexInfo findActionIndex() {
        List<IndexInfo> indexes = jdbcTemplate.query("""
                SELECT
                    index_class.relname AS index_name,
                    access_method.amname AS access_method,
                    index_metadata.indisunique,
                    index_metadata.indisvalid,
                    index_metadata.indisready,
                    index_metadata.indpred IS NOT NULL AS has_predicate,
                    index_metadata.indexprs IS NOT NULL AS has_expression,
                    index_metadata.indnkeyatts,
                    constraint_metadata.oid IS NOT NULL AS constraint_backed,
                    string_agg(
                        column_metadata.attname,
                        ',' ORDER BY index_key.position
                    ) AS key_columns
                FROM pg_class table_class
                JOIN pg_namespace table_namespace
                  ON table_namespace.oid = table_class.relnamespace
                JOIN pg_index index_metadata
                  ON index_metadata.indrelid = table_class.oid
                JOIN pg_class index_class
                  ON index_class.oid = index_metadata.indexrelid
                JOIN pg_am access_method
                  ON access_method.oid = index_class.relam
                LEFT JOIN pg_constraint constraint_metadata
                  ON constraint_metadata.conindid = index_class.oid
                LEFT JOIN LATERAL unnest(index_metadata.indkey)
                     WITH ORDINALITY AS index_key(attnum, position)
                  ON index_key.position <= index_metadata.indnkeyatts
                LEFT JOIN pg_attribute column_metadata
                  ON column_metadata.attrelid = table_class.oid
                 AND column_metadata.attnum = index_key.attnum
                WHERE table_namespace.nspname = current_schema()
                  AND table_class.relname = ?
                  AND index_class.relname = ?
                GROUP BY
                    index_class.relname,
                    access_method.amname,
                    index_metadata.indisunique,
                    index_metadata.indisvalid,
                    index_metadata.indisready,
                    (index_metadata.indpred IS NOT NULL),
                    (index_metadata.indexprs IS NOT NULL),
                    index_metadata.indnkeyatts,
                    constraint_metadata.oid
                """,
                (resultSet, rowNumber) -> new IndexInfo(
                        resultSet.getString("index_name"),
                        resultSet.getString("access_method"),
                        resultSet.getBoolean("indisunique"),
                        resultSet.getBoolean("indisvalid"),
                        resultSet.getBoolean("indisready"),
                        resultSet.getBoolean("has_predicate"),
                        resultSet.getBoolean("has_expression"),
                        resultSet.getInt("indnkeyatts"),
                        resultSet.getBoolean("constraint_backed"),
                        resultSet.getString("key_columns")
                ),
                ACTION_STEPS,
                ACTION_INDEX
        );
        return indexes.isEmpty() ? null : indexes.get(0);
    }

    private boolean isCanonicalActionIndex(IndexInfo index) {
        return index != null
                && "btree".equals(index.accessMethod())
                && !index.unique()
                && index.valid()
                && index.ready()
                && !index.hasPredicate()
                && !index.hasExpression()
                && index.keyColumnCount() == 2
                && !index.constraintBacked()
                && "action_id,display_order".equals(index.keyColumns());
    }

    private boolean tableExists(String tableName) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = current_schema()
                      AND table_name = ?
                )
                """, Boolean.class, tableName);
        return Boolean.TRUE.equals(exists);
    }

    private boolean columnExists(String tableName, String columnName) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = ?
                      AND column_name = ?
                )
                """, Boolean.class, tableName, columnName);
        return Boolean.TRUE.equals(exists);
    }

    private boolean columnIsNotNull(String tableName, String columnName) {
        Boolean notNull = jdbcTemplate.queryForObject("""
                SELECT is_nullable = 'NO'
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = ?
                  AND column_name = ?
                """, Boolean.class, tableName, columnName);
        return Boolean.TRUE.equals(notNull);
    }

    private long rowCount(String tableName) {
        if (!ACTION_STEPS.equals(tableName)) {
            throw new IllegalArgumentException("Unsupported table: " + tableName);
        }
        return queryCount("SELECT count(*) FROM action_steps");
    }

    private long queryCount(String sql) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class);
        return count == null ? 0L : count;
    }

    private String currentSchema() {
        String schema = jdbcTemplate.queryForObject(
                "SELECT current_schema()",
                String.class
        );
        if (schema == null || schema.isBlank()) {
            throw new IllegalStateException("Database current_schema() is empty");
        }
        return schema;
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private record UniqueConstraintInfo(
            String name,
            boolean deferrable,
            boolean initiallyDeferred
    ) {
    }

    private record IndexInfo(
            String name,
            String accessMethod,
            boolean unique,
            boolean valid,
            boolean ready,
            boolean hasPredicate,
            boolean hasExpression,
            int keyColumnCount,
            boolean constraintBacked,
            String keyColumns
    ) {
    }
}
