-- 规则版本服务端属性绑定快照升级。
-- 目标对象：wms_rule_version.attribute_bindings_json
-- 前置：已执行 iws_wms_smt_core.sql 与 iws_inventory_management_rules.sql。
-- 特性：首次精确加列；再次执行只复核，不更新历史行，不回填 ACTIVE 快照。
-- 版本：2026-11-02 / iws.rule.attribute-bindings/1

SET NAMES utf8mb4;
SET @target_schema = 'innolux-iws';
SET @expected_mysql_version = '8.0.45';
SET @target_table = 'wms_rule_version';
SET @target_column = 'attribute_bindings_json';
SET @target_column_comment = '服务端属性绑定、用途和限额快照';

SET @schema_ok = (
    SELECT DATABASE() IS NOT NULL
       AND CAST(DATABASE() AS BINARY) = CAST(@target_schema AS BINARY)
);
SET @schema_exists = (
    SELECT COUNT(*) = 1
      FROM information_schema.SCHEMATA
     WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@target_schema AS BINARY)
);
SET @version_ok = (
    CAST(VERSION() AS BINARY) = CAST(@expected_mysql_version AS BINARY)
    AND VERSION() NOT LIKE '%MariaDB%'
);
SET @table_count = (
    SELECT COUNT(*)
      FROM information_schema.TABLES
     WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
       AND CAST(TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY)
       AND TABLE_TYPE = 'BASE TABLE'
);
SET @column_count = (
    SELECT COUNT(*)
      FROM information_schema.COLUMNS
     WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
       AND CAST(TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY)
       AND CAST(COLUMN_NAME AS BINARY) = CAST(@target_column AS BINARY)
);
SET @column_definition_mismatch = (
    SELECT COUNT(*)
      FROM information_schema.COLUMNS
     WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
       AND CAST(TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY)
       AND CAST(COLUMN_NAME AS BINARY) = CAST(@target_column AS BINARY)
       AND NOT (
           CAST(COLUMN_TYPE AS BINARY) = CAST('json' AS BINARY)
           AND CAST(IS_NULLABLE AS BINARY) = CAST('YES' AS BINARY)
           AND COLUMN_DEFAULT IS NULL
           AND CAST(EXTRA AS BINARY) = CAST('' AS BINARY)
           AND CHARACTER_SET_NAME IS NULL
           AND COLLATION_NAME IS NULL
           AND CAST(COLUMN_COMMENT AS BINARY) = CAST(@target_column_comment AS BINARY)
       )
);
SET @predecessor_ok = (
    SELECT COUNT(*) = 1
      FROM information_schema.COLUMNS
     WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
       AND CAST(TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY)
       AND CAST(COLUMN_NAME AS BINARY) = CAST('result_json' AS BINARY)
       AND ORDINAL_POSITION = 5
);
SET @successor_ok = (
    SELECT COUNT(*) = 1
      FROM information_schema.COLUMNS
     WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
       AND CAST(TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY)
       AND CAST(COLUMN_NAME AS BINARY) = CAST('canonical_digest' AS BINARY)
       AND ORDINAL_POSITION = IF(@column_count = 0, 6, 7)
);
SET @preflight_ok = IF(
    @schema_ok = 1 AND @schema_exists = 1 AND @version_ok = 1
    AND @table_count = 1 AND @column_count IN (0, 1)
    AND @column_definition_mismatch = 0
    AND @predecessor_ok = 1 AND @successor_ok = 1,
    1, 0
);

SELECT
    @target_schema AS `目标库`,
    DATABASE() AS `当前库`,
    VERSION() AS `MySQL版本`,
    @table_count AS `目标表数`,
    @column_count AS `绑定列数`,
    @column_definition_mismatch AS `定义冲突数`,
    IF(@preflight_ok = 1, '预检通过', '预检失败，禁止加列') AS `预检结果`;
SELECT IF(
    @preflight_ok = 1,
    1,
    CAST(CONCAT(
        'invalid rule binding preflight: schema=', @schema_ok,
        ', schema_exists=', @schema_exists,
        ', version=', @version_ok,
        ', table=', @table_count,
        ', column=', @column_count,
        ', definition=', @column_definition_mismatch,
        ', predecessor=', @predecessor_ok,
        ', successor=', @successor_ok
    ) AS JSON)
) AS `规则绑定预检硬门禁`;

SET @ddl = IF(
    @column_count = 0,
    CONCAT(
        'ALTER TABLE `', @target_schema, '`.`', @target_table, '` ',
        'ADD COLUMN `', @target_column, '` json NULL ',
        'COMMENT ''', @target_column_comment, ''' AFTER `result_json`'
    ),
    'DO 0'
);
PREPARE add_binding_column FROM @ddl;
EXECUTE add_binding_column;
DEALLOCATE PREPARE add_binding_column;

SET @post_column_count = (
    SELECT COUNT(*)
      FROM information_schema.COLUMNS
     WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
       AND CAST(TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY)
       AND CAST(COLUMN_NAME AS BINARY) = CAST(@target_column AS BINARY)
       AND CAST(COLUMN_TYPE AS BINARY) = CAST('json' AS BINARY)
       AND CAST(IS_NULLABLE AS BINARY) = CAST('YES' AS BINARY)
       AND COLUMN_DEFAULT IS NULL
       AND CAST(EXTRA AS BINARY) = CAST('' AS BINARY)
       AND CHARACTER_SET_NAME IS NULL
       AND COLLATION_NAME IS NULL
       AND CAST(COLUMN_COMMENT AS BINARY) = CAST(@target_column_comment AS BINARY)
       AND ORDINAL_POSITION = 6
);
SET @post_successor_ok = (
    SELECT COUNT(*) = 1
      FROM information_schema.COLUMNS
     WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
       AND CAST(TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY)
       AND CAST(COLUMN_NAME AS BINARY) = CAST('canonical_digest' AS BINARY)
       AND ORDINAL_POSITION = 7
);
SET @postflight_ok = IF(
    @post_column_count = 1 AND @post_successor_ok = 1,
    1, 0
);
SELECT
    @post_column_count AS `精确绑定列数`,
    @post_successor_ok AS `后继列顺序通过`,
    IF(@postflight_ok = 1, '规则绑定列验收通过', '规则绑定列验收失败') AS `验收结果`;
SELECT IF(
    @postflight_ok = 1,
    1,
    CAST('invalid rule binding postflight' AS JSON)
) AS `规则绑定后置硬门禁`;

SELECT '规则绑定列已满足；再次执行只复核，不更新历史行' AS `执行说明`;
