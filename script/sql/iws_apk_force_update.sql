/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 应用版本管理
 * @Version      : V1.0
 * @Description  : 幂等新增 APK 版本强制更新策略字段；
 * @ModifyRecord : V1.0 新建；
 */

-- 目标：MySQL 8.0；单一目标库参数见下一行。
-- 执行：mysql --database=innolux-iws --default-character-set=utf8mb4 --batch --raw < iws_apk_force_update.sql
-- 同一连接顺序执行，不添加 --force。执行器必须断言输出中的“门禁结果”为 1；失败时 DDL 受门控跳过。

SET @apk_force_schema = 'innolux-iws';
SET @apk_force_schema_q = CONCAT('`', REPLACE(@apk_force_schema, '`', '``'), '`');

SET @apk_force_target_ok = (
    SELECT COUNT(*) = 1 FROM information_schema.SCHEMATA
    WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@apk_force_schema AS BINARY)
);
SET @apk_force_database_matches = (
    DATABASE() IS NOT NULL
    AND CAST(DATABASE() AS BINARY) = CAST(@apk_force_schema AS BINARY)
);
SET @apk_force_version_ok = (VERSION() LIKE '8.0.%');
SET @apk_force_base_ok = IF(
    @apk_force_target_ok = 1 AND @apk_force_database_matches = 1 AND @apk_force_version_ok = 1,
    1, 0
);
SET @apk_force_table_ok = (
    SELECT COUNT(*) = 1 FROM information_schema.TABLES
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@apk_force_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('sys_apk_release' AS BINARY)
      AND CAST(TABLE_TYPE AS BINARY) = CAST('BASE TABLE' AS BINARY)
);
SET @apk_force_column_count = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@apk_force_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('sys_apk_release' AS BINARY)
      AND CAST(COLUMN_NAME AS BINARY) = CAST('force_update' AS BINARY)
);
SET @apk_force_column_ok = (
    SELECT COUNT(*) = 1 FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@apk_force_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('sys_apk_release' AS BINARY)
      AND CAST(COLUMN_NAME AS BINARY) = CAST('force_update' AS BINARY)
      AND CAST(COLUMN_TYPE AS BINARY) = CAST('tinyint(1)' AS BINARY)
      AND CAST(IS_NULLABLE AS BINARY) = CAST('NO' AS BINARY)
      AND CAST(COLUMN_DEFAULT AS BINARY) = CAST('1' AS BINARY)
      AND CAST(EXTRA AS BINARY) = CAST('' AS BINARY)
      AND CHARACTER_SET_NAME IS NULL
      AND COLLATION_NAME IS NULL
);

DROP TEMPORARY TABLE IF EXISTS tmp_iws_apk_force_conflicts;
CREATE TEMPORARY TABLE tmp_iws_apk_force_conflicts (
    object_key varchar(128) NOT NULL,
    reason varchar(255) NOT NULL,
    KEY idx_tmp_iws_apk_force_conflict (object_key)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO tmp_iws_apk_force_conflicts (object_key, reason)
SELECT 'sys_apk_release', '目标表不存在或不是基础表'
WHERE @apk_force_base_ok = 1 AND @apk_force_table_ok = 0;
INSERT INTO tmp_iws_apk_force_conflicts (object_key, reason)
SELECT 'sys_apk_release.force_update', '同名字段已存在但定义不是 tinyint(1) NOT NULL DEFAULT 1'
WHERE @apk_force_table_ok = 1 AND @apk_force_column_count > 0 AND @apk_force_column_ok = 0;
INSERT INTO tmp_iws_apk_force_conflicts (object_key, reason)
SELECT 'sys_apk_release.force_update', CONCAT('存在大小写/近似字段：', c.COLUMN_NAME)
FROM information_schema.COLUMNS c
WHERE CAST(c.TABLE_SCHEMA AS BINARY) = CAST(@apk_force_schema AS BINARY)
  AND CAST(c.TABLE_NAME AS BINARY) = CAST('sys_apk_release' AS BINARY)
  AND CAST(LOWER(c.COLUMN_NAME) AS BINARY) = CAST('force_update' AS BINARY)
  AND CAST(c.COLUMN_NAME AS BINARY) <> CAST('force_update' AS BINARY);

SELECT COUNT(*) INTO @apk_force_preflight_conflicts FROM tmp_iws_apk_force_conflicts;
SET @apk_force_preflight_ok = IF(
    @apk_force_base_ok = 1 AND @apk_force_table_ok = 1 AND @apk_force_preflight_conflicts = 0,
    1, 0
);

SELECT
    @apk_force_target_ok AS '目标库存在',
    @apk_force_database_matches AS '当前连接库一致',
    @apk_force_version_ok AS 'MySQL8版本',
    @apk_force_table_ok AS 'APK表存在',
    @apk_force_column_count AS '字段现状数',
    IF(@apk_force_preflight_ok = 1, '前置校验通过', '前置校验失败，禁止写入') AS '执行说明',
    @apk_force_schema AS '目标库';
SELECT object_key AS '对象', reason AS '冲突说明'
FROM tmp_iws_apk_force_conflicts ORDER BY object_key, reason;
SELECT
    @apk_force_preflight_ok AS '门禁结果',
    IF(@apk_force_preflight_ok = 1, '允许执行', '前置校验失败，禁止写入') AS '执行说明';

SET @apk_force_add_sql = IF(
    @apk_force_preflight_ok = 1 AND @apk_force_column_count = 0,
    CONCAT(
        'ALTER TABLE ', @apk_force_schema_q, '.`sys_apk_release` ',
        'ADD COLUMN `force_update` tinyint(1) NOT NULL DEFAULT 1 AFTER `status`'
    ),
    'DO 0'
);
PREPARE apk_force_add_stmt FROM @apk_force_add_sql;
EXECUTE apk_force_add_stmt;
DEALLOCATE PREPARE apk_force_add_stmt;

SET @apk_force_post_column_ok = (
    SELECT COUNT(*) = 1 FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@apk_force_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('sys_apk_release' AS BINARY)
      AND CAST(COLUMN_NAME AS BINARY) = CAST('force_update' AS BINARY)
      AND CAST(COLUMN_TYPE AS BINARY) = CAST('tinyint(1)' AS BINARY)
      AND CAST(IS_NULLABLE AS BINARY) = CAST('NO' AS BINARY)
      AND CAST(COLUMN_DEFAULT AS BINARY) = CAST('1' AS BINARY)
      AND CAST(EXTRA AS BINARY) = CAST('' AS BINARY)
      AND CHARACTER_SET_NAME IS NULL
      AND COLLATION_NAME IS NULL
);
SET @apk_force_post_ok = IF(
    @apk_force_preflight_ok = 1 AND @apk_force_post_column_ok = 1,
    1, 0
);

SELECT
    @apk_force_schema AS '目标库',
    @apk_force_post_column_ok AS '字段定义符合',
    @apk_force_post_ok AS '门禁结果',
    IF(@apk_force_post_ok = 1, 'APK强制更新字段升级完成', '后置验收失败，请检查字段定义') AS '执行结果';
