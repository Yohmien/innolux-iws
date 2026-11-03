-- 规则版本服务端属性绑定快照结构验收。
-- 只读校验 wms_rule_version.attribute_bindings_json 的类型、NULL、注释和列序。

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
SET @version_ok = (
    CAST(VERSION() AS BINARY) = CAST(@expected_mysql_version AS BINARY)
    AND VERSION() NOT LIKE '%MariaDB%'
);
SET @exact_column_count = (
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
       AND ORDINAL_POSITION = 7
);
SET @verify_ok = IF(
    @schema_ok = 1 AND @version_ok = 1 AND @exact_column_count = 1
    AND @predecessor_ok = 1 AND @successor_ok = 1,
    1, 0
);

SELECT
    @target_schema AS `目标库`,
    DATABASE() AS `当前库`,
    VERSION() AS `MySQL版本`,
    @exact_column_count AS `精确绑定列数`,
    @predecessor_ok AS `前驱列顺序通过`,
    @successor_ok AS `后继列顺序通过`,
    IF(@verify_ok = 1, '规则绑定结构验收通过', '规则绑定结构验收失败') AS `验收结果`;
SELECT IF(
    @verify_ok = 1,
    1,
    CAST(CONCAT(
        'invalid rule binding schema: schema=', @schema_ok,
        ', version=', @version_ok,
        ', column=', @exact_column_count,
        ', predecessor=', @predecessor_ok,
        ', successor=', @successor_ok
    ) AS JSON)
) AS `规则绑定结构硬门禁`;
