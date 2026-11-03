-- ============================================================================
-- IWS+ 库存 P1/P5 业务任务与冻结输入独立验收
-- ============================================================================
-- 只读验收 wms_business_task；不执行 DDL，不写业务行。
-- 目标库：@target_schema；默认 innolux-iws，可由
--   @iws_inventory_target_schema 覆盖，并与 DATABASE() 字节一致。
-- ============================================================================

SET NAMES utf8mb4;

SET @target_schema = IFNULL(@iws_inventory_target_schema, 'innolux-iws');
SET @expected_mysql_version = '8.0.45';
SET @target_table = 'wms_business_task';
SET @schema_ok = (
    DATABASE() IS NOT NULL
    AND CAST(DATABASE() AS BINARY) = CAST(@target_schema AS BINARY)
);
SET @version_ok = (
    CAST(VERSION() AS BINARY) = CAST(@expected_mysql_version AS BINARY)
    AND VERSION() NOT LIKE '%MariaDB%'
);
SET @table_count = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY)
      AND TABLE_TYPE = 'BASE TABLE'
);
SET @column_count = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY)
);
SET @index_rows = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY)
);
SET @check_count = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CAST(CONSTRAINT_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY)
      AND CONSTRAINT_TYPE = 'CHECK'
);
SET @unique_count = (
    SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY)
      AND NON_UNIQUE = 0
);
SET @own_fk_count = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CAST(CONSTRAINT_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY)
      AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @trigger_count = (
    SELECT COUNT(*) FROM information_schema.TRIGGERS
    WHERE CAST(EVENT_OBJECT_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND CAST(EVENT_OBJECT_TABLE AS BINARY) = CAST(@target_table AS BINARY)
);
SET @verify_ok = IF(
    @schema_ok = 1 AND @version_ok = 1
    AND @table_count = 1 AND @column_count = 19
    AND @index_rows = 7 AND @check_count = 5 AND @unique_count = 3
    AND @own_fk_count = 0 AND @trigger_count = 0,
    1, 0
);

SELECT
    @target_schema AS `目标库`,
    DATABASE() AS `当前库`,
    VERSION() AS `MySQL版本`,
    @table_count AS `目标表数`,
    @column_count AS `列数`,
    @index_rows AS `索引列行数`,
    @check_count AS `CHECK数`,
    @unique_count AS `唯一索引数`,
    @own_fk_count AS `本表外键数`,
    @trigger_count AS `触发器数`,
    IF(@verify_ok = 1, '业务任务结构验收通过', '业务任务结构验收失败') AS `验收结果`;
SELECT IF(@verify_ok = 1, 1, CAST('invalid business task verify' AS JSON)) AS `业务任务验收硬门禁`;
