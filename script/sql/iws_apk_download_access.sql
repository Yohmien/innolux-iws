/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 应用版本管理
 * @Version      : V1.0
 * @Description  : 幂等新增 APK 下载计数、来源 IP 与访问信息字段；
 * @ModifyRecord : V1.0 新建；
 */

-- 目标：MySQL 8.0；单一目标库参数见下一行。
-- 执行：mysql --database=innolux-iws --default-character-set=utf8mb4 --batch --raw < iws_apk_download_access.sql
-- 同一连接顺序执行，不添加 --force。执行器必须断言输出中的“门禁结果”为 1；失败时 DDL 受门控跳过。

SET @ada_schema = 'innolux-iws';
SET @ada_schema_q = CONCAT('`', REPLACE(@ada_schema, '`', '``'), '`');

SET @ada_target_ok = (
    SELECT COUNT(*) = 1 FROM information_schema.SCHEMATA
    WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@ada_schema AS BINARY)
);
SET @ada_database_matches = (
    DATABASE() IS NOT NULL
    AND CAST(DATABASE() AS BINARY) = CAST(@ada_schema AS BINARY)
);
SET @ada_version_ok = (VERSION() LIKE '8.0.%');
SET @ada_base_ok = IF(
    @ada_target_ok = 1 AND @ada_database_matches = 1 AND @ada_version_ok = 1,
    1, 0
);

SET @ada_apk_table_ok = (
    SELECT COUNT(*) = 1 FROM information_schema.TABLES
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@ada_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('sys_apk_release' AS BINARY)
      AND CAST(TABLE_TYPE AS BINARY) = CAST('BASE TABLE' AS BINARY)
);
SET @ada_detail_table_ok = (
    SELECT COUNT(*) = 1 FROM information_schema.TABLES
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@ada_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('iws_process_request_detail' AS BINARY)
      AND CAST(TABLE_TYPE AS BINARY) = CAST('BASE TABLE' AS BINARY)
);

SET @ada_download_count_count = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@ada_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('sys_apk_release' AS BINARY)
      AND CAST(COLUMN_NAME AS BINARY) = CAST('download_count' AS BINARY)
);
SET @ada_download_count_ok = (
    SELECT COUNT(*) = 1 FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@ada_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('sys_apk_release' AS BINARY)
      AND CAST(COLUMN_NAME AS BINARY) = CAST('download_count' AS BINARY)
      AND CAST(COLUMN_TYPE AS BINARY) = CAST('bigint' AS BINARY)
      AND CAST(IS_NULLABLE AS BINARY) = CAST('NO' AS BINARY)
      AND CAST(COLUMN_DEFAULT AS BINARY) = CAST('0' AS BINARY)
      AND CAST(EXTRA AS BINARY) = CAST('' AS BINARY)
      AND CHARACTER_SET_NAME IS NULL
      AND COLLATION_NAME IS NULL
);

SET @ada_client_ip_count = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@ada_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('iws_process_request_detail' AS BINARY)
      AND CAST(COLUMN_NAME AS BINARY) = CAST('client_ip' AS BINARY)
);
SET @ada_client_ip_ok = (
    SELECT COUNT(*) = 1 FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@ada_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('iws_process_request_detail' AS BINARY)
      AND CAST(COLUMN_NAME AS BINARY) = CAST('client_ip' AS BINARY)
      AND CAST(COLUMN_TYPE AS BINARY) = CAST('varchar(64)' AS BINARY)
      AND CAST(IS_NULLABLE AS BINARY) = CAST('YES' AS BINARY)
      AND COLUMN_DEFAULT IS NULL
      AND CAST(EXTRA AS BINARY) = CAST('' AS BINARY)
      AND CAST(CHARACTER_SET_NAME AS BINARY) = CAST('utf8mb4' AS BINARY)
      AND CAST(COLLATION_NAME AS BINARY) = CAST('utf8mb4_general_ci' AS BINARY)
);

SET @ada_access_summary_count = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@ada_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('iws_process_request_detail' AS BINARY)
      AND CAST(COLUMN_NAME AS BINARY) = CAST('access_summary' AS BINARY)
);
SET @ada_access_summary_ok = (
    SELECT COUNT(*) = 1 FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@ada_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('iws_process_request_detail' AS BINARY)
      AND CAST(COLUMN_NAME AS BINARY) = CAST('access_summary' AS BINARY)
      AND CAST(COLUMN_TYPE AS BINARY) = CAST('varchar(500)' AS BINARY)
      AND CAST(IS_NULLABLE AS BINARY) = CAST('YES' AS BINARY)
      AND COLUMN_DEFAULT IS NULL
      AND CAST(EXTRA AS BINARY) = CAST('' AS BINARY)
      AND CAST(CHARACTER_SET_NAME AS BINARY) = CAST('utf8mb4' AS BINARY)
      AND CAST(COLLATION_NAME AS BINARY) = CAST('utf8mb4_general_ci' AS BINARY)
);

DROP TEMPORARY TABLE IF EXISTS tmp_iws_apk_access_conflicts;
CREATE TEMPORARY TABLE tmp_iws_apk_access_conflicts (
    object_key varchar(128) NOT NULL,
    reason varchar(255) NOT NULL,
    KEY idx_tmp_iws_apk_access_conflict (object_key)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO tmp_iws_apk_access_conflicts (object_key, reason)
SELECT 'sys_apk_release', '目标表不存在或不是基础表'
WHERE @ada_base_ok = 1 AND @ada_apk_table_ok = 0;
INSERT INTO tmp_iws_apk_access_conflicts (object_key, reason)
SELECT 'iws_process_request_detail', '目标表不存在或不是基础表'
WHERE @ada_base_ok = 1 AND @ada_detail_table_ok = 0;
INSERT INTO tmp_iws_apk_access_conflicts (object_key, reason)
SELECT 'sys_apk_release.download_count', '同名字段已存在但定义不是 bigint NOT NULL DEFAULT 0'
WHERE @ada_apk_table_ok = 1 AND @ada_download_count_count > 0 AND @ada_download_count_ok = 0;
INSERT INTO tmp_iws_apk_access_conflicts (object_key, reason)
SELECT 'iws_process_request_detail.client_ip', '同名字段已存在但定义不是 varchar(64) NULL'
WHERE @ada_detail_table_ok = 1 AND @ada_client_ip_count > 0 AND @ada_client_ip_ok = 0;
INSERT INTO tmp_iws_apk_access_conflicts (object_key, reason)
SELECT 'iws_process_request_detail.access_summary', '同名字段已存在但定义不是 varchar(500) NULL'
WHERE @ada_detail_table_ok = 1 AND @ada_access_summary_count > 0 AND @ada_access_summary_ok = 0;

INSERT INTO tmp_iws_apk_access_conflicts (object_key, reason)
SELECT 'sys_apk_release.download_count', CONCAT('存在大小写/近似字段：', c.COLUMN_NAME)
FROM information_schema.COLUMNS c
WHERE CAST(c.TABLE_SCHEMA AS BINARY) = CAST(@ada_schema AS BINARY)
  AND CAST(c.TABLE_NAME AS BINARY) = CAST('sys_apk_release' AS BINARY)
  AND CAST(LOWER(c.COLUMN_NAME) AS BINARY) = CAST('download_count' AS BINARY)
  AND CAST(c.COLUMN_NAME AS BINARY) <> CAST('download_count' AS BINARY);
INSERT INTO tmp_iws_apk_access_conflicts (object_key, reason)
SELECT 'iws_process_request_detail.client_ip', CONCAT('存在大小写/近似字段：', c.COLUMN_NAME)
FROM information_schema.COLUMNS c
WHERE CAST(c.TABLE_SCHEMA AS BINARY) = CAST(@ada_schema AS BINARY)
  AND CAST(c.TABLE_NAME AS BINARY) = CAST('iws_process_request_detail' AS BINARY)
  AND CAST(LOWER(c.COLUMN_NAME) AS BINARY) = CAST('client_ip' AS BINARY)
  AND CAST(c.COLUMN_NAME AS BINARY) <> CAST('client_ip' AS BINARY);
INSERT INTO tmp_iws_apk_access_conflicts (object_key, reason)
SELECT 'iws_process_request_detail.access_summary', CONCAT('存在大小写/近似字段：', c.COLUMN_NAME)
FROM information_schema.COLUMNS c
WHERE CAST(c.TABLE_SCHEMA AS BINARY) = CAST(@ada_schema AS BINARY)
  AND CAST(c.TABLE_NAME AS BINARY) = CAST('iws_process_request_detail' AS BINARY)
  AND CAST(LOWER(c.COLUMN_NAME) AS BINARY) = CAST('access_summary' AS BINARY)
  AND CAST(c.COLUMN_NAME AS BINARY) <> CAST('access_summary' AS BINARY);

SELECT COUNT(*) INTO @ada_preflight_conflicts FROM tmp_iws_apk_access_conflicts;
SET @ada_preflight_ok = IF(
    @ada_base_ok = 1 AND @ada_apk_table_ok = 1 AND @ada_detail_table_ok = 1
    AND @ada_preflight_conflicts = 0,
    1, 0
);

SELECT
    @ada_target_ok AS '目标库存在',
    @ada_database_matches AS '当前连接库一致',
    @ada_version_ok AS 'MySQL8版本',
    @ada_apk_table_ok AS 'APK表存在',
    @ada_detail_table_ok AS '请求记录表存在',
    @ada_download_count_count AS '下载计数字段现状',
    @ada_client_ip_count AS '来源IP字段现状',
    @ada_access_summary_count AS '访问信息字段现状',
    IF(@ada_preflight_ok = 1, '前置校验通过', '前置校验失败，禁止写入') AS '执行说明',
    @ada_schema AS '目标库';
SELECT object_key AS '对象', reason AS '冲突说明'
FROM tmp_iws_apk_access_conflicts ORDER BY object_key, reason;
SELECT
    @ada_preflight_ok AS '门禁结果',
    IF(@ada_preflight_ok = 1, '允许执行', '前置校验失败，禁止写入') AS '执行说明';

SET @ada_ddl = IF(
    @ada_preflight_ok = 1 AND @ada_download_count_count = 0,
    CONCAT(
        'ALTER TABLE ', @ada_schema_q, '.`sys_apk_release` ',
        'ADD COLUMN `download_count` bigint NOT NULL DEFAULT 0 AFTER `update_time`'
    ),
    'DO 0'
);
PREPARE ada_stmt FROM @ada_ddl;
EXECUTE ada_stmt;
DEALLOCATE PREPARE ada_stmt;

SET @ada_ddl = IF(
    @ada_preflight_ok = 1 AND @ada_client_ip_count = 0,
    CONCAT(
        'ALTER TABLE ', @ada_schema_q, '.`iws_process_request_detail` ',
        'ADD COLUMN `client_ip` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL AFTER `update_time`'
    ),
    'DO 0'
);
PREPARE ada_stmt FROM @ada_ddl;
EXECUTE ada_stmt;
DEALLOCATE PREPARE ada_stmt;

SET @ada_ddl = IF(
    @ada_preflight_ok = 1 AND @ada_access_summary_count = 0,
    CONCAT(
        'ALTER TABLE ', @ada_schema_q, '.`iws_process_request_detail` ',
        'ADD COLUMN `access_summary` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL AFTER `client_ip`'
    ),
    'DO 0'
);
PREPARE ada_stmt FROM @ada_ddl;
EXECUTE ada_stmt;
DEALLOCATE PREPARE ada_stmt;

SET @ada_post_download_count_ok = (
    SELECT COUNT(*) = 1 FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@ada_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('sys_apk_release' AS BINARY)
      AND CAST(COLUMN_NAME AS BINARY) = CAST('download_count' AS BINARY)
      AND CAST(COLUMN_TYPE AS BINARY) = CAST('bigint' AS BINARY)
      AND CAST(IS_NULLABLE AS BINARY) = CAST('NO' AS BINARY)
      AND CAST(COLUMN_DEFAULT AS BINARY) = CAST('0' AS BINARY)
      AND CAST(EXTRA AS BINARY) = CAST('' AS BINARY)
      AND CHARACTER_SET_NAME IS NULL
      AND COLLATION_NAME IS NULL
);
SET @ada_post_client_ip_ok = (
    SELECT COUNT(*) = 1 FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@ada_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('iws_process_request_detail' AS BINARY)
      AND CAST(COLUMN_NAME AS BINARY) = CAST('client_ip' AS BINARY)
      AND CAST(COLUMN_TYPE AS BINARY) = CAST('varchar(64)' AS BINARY)
      AND CAST(IS_NULLABLE AS BINARY) = CAST('YES' AS BINARY)
      AND COLUMN_DEFAULT IS NULL
      AND CAST(EXTRA AS BINARY) = CAST('' AS BINARY)
      AND CAST(CHARACTER_SET_NAME AS BINARY) = CAST('utf8mb4' AS BINARY)
      AND CAST(COLLATION_NAME AS BINARY) = CAST('utf8mb4_general_ci' AS BINARY)
);
SET @ada_post_access_summary_ok = (
    SELECT COUNT(*) = 1 FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@ada_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('iws_process_request_detail' AS BINARY)
      AND CAST(COLUMN_NAME AS BINARY) = CAST('access_summary' AS BINARY)
      AND CAST(COLUMN_TYPE AS BINARY) = CAST('varchar(500)' AS BINARY)
      AND CAST(IS_NULLABLE AS BINARY) = CAST('YES' AS BINARY)
      AND COLUMN_DEFAULT IS NULL
      AND CAST(EXTRA AS BINARY) = CAST('' AS BINARY)
      AND CAST(CHARACTER_SET_NAME AS BINARY) = CAST('utf8mb4' AS BINARY)
      AND CAST(COLLATION_NAME AS BINARY) = CAST('utf8mb4_general_ci' AS BINARY)
);
SET @ada_post_ok = IF(
    @ada_preflight_ok = 1 AND @ada_post_download_count_ok = 1
    AND @ada_post_client_ip_ok = 1 AND @ada_post_access_summary_ok = 1,
    1, 0
);

SELECT
    @ada_schema AS '目标库',
    @ada_post_download_count_ok AS '下载计数字段符合',
    @ada_post_client_ip_ok AS '来源IP字段符合',
    @ada_post_access_summary_ok AS '访问信息字段符合',
    @ada_post_ok AS '门禁结果',
    IF(@ada_post_ok = 1, 'APK下载访问字段升级完成', '后置验收失败，请检查字段定义') AS '执行结果';
