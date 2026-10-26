/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 幂等确保默认环境存在并启用 IWS 系统
 * @ModifyRecord : V1.0 新建
 */

-- 目标：MySQL 8.0；仅修改下方集中参数。
-- 执行：mysql --database=innolux-iws --default-character-set=utf8mb4 --batch --raw < iws_endpoint_default_iws_system.sql
-- 执行器必须断言输出中的“门禁结果”为 1；失败时受门控跳过写入。

SET NAMES utf8mb4;

-- 唯一目标库和默认目录参数：按部署环境修改。
SET @upgrade_schema = 'innolux-iws';
SET @default_environment_code = 'TEST';
SET @system_code = 'IWS';
SET @system_name = 'IWS+';
SET @system_description = '空库初始化默认系统，供 APK 等计划事件发布使用';

SET @upgrade_schema_q = CONCAT('`', REPLACE(@upgrade_schema, '`', '``'), '`');

SET @target_ok = (
    SELECT COUNT(*) = 1
    FROM information_schema.SCHEMATA
    WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@upgrade_schema AS BINARY)
);
SET @database_matches = (
    DATABASE() IS NOT NULL
    AND CAST(DATABASE() AS BINARY) = CAST(@upgrade_schema AS BINARY)
);
SET @version_ok = (VERSION() LIKE '8.0.%');
SET @tables_ok = (
    SELECT COUNT(*) = 3
    FROM information_schema.TABLES
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@upgrade_schema AS BINARY)
      AND TABLE_NAME IN ('iws_endpoint_environment', 'iws_endpoint_system',
        'iws_endpoint_catalog_control')
);

SET @control_sql = CONCAT(
    'SELECT COUNT(*), COALESCE(MAX(`revision`), -1)',
    ' INTO @control_count, @control_revision FROM ', @upgrade_schema_q,
    '.`iws_endpoint_catalog_control` WHERE `singleton_key` = ''CATALOG'''
);
SET @ddl = IF(@target_ok = 1 AND @database_matches = 1 AND @version_ok = 1
    AND @tables_ok = 1, @control_sql, 'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @environment_sql = CONCAT(
    'SELECT COUNT(*), COALESCE(MAX(`enabled`), 0)',
    ' INTO @environment_count, @environment_enabled FROM ', @upgrade_schema_q,
    '.`iws_endpoint_environment` WHERE `environment_code` = ',
    QUOTE(@default_environment_code)
);
SET @ddl = IF(@target_ok = 1 AND @database_matches = 1 AND @version_ok = 1
    AND @tables_ok = 1, @environment_sql, 'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @system_sql = CONCAT(
    'SELECT COUNT(*), COALESCE(MAX(`enabled`), 0), COALESCE(MAX(`revision`), -1)',
    ' INTO @system_count, @system_enabled, @system_revision FROM ', @upgrade_schema_q,
    '.`iws_endpoint_system` WHERE `system_code` = ', QUOTE(@system_code)
);
SET @ddl = IF(@target_ok = 1 AND @database_matches = 1 AND @version_ok = 1
    AND @tables_ok = 1, @system_sql, 'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @precheck_ok = (
    @target_ok = 1
    AND @database_matches = 1
    AND @version_ok = 1
    AND @tables_ok = 1
    AND COALESCE(@control_count, 0) = 1
    AND COALESCE(@control_revision, -1) >= 0
);
SET @environment_ok = (
    COALESCE(@environment_count, 0) = 1
    AND COALESCE(@environment_enabled, 0) = 1
);

SELECT
    @target_ok AS `目标库存在`,
    @database_matches AS `当前连接库一致`,
    @version_ok AS `MySQL8版本`,
    @tables_ok AS `endpoint表就绪`,
    COALESCE(@control_count, 0) AS `目录控制行`,
    COALESCE(@environment_count, 0) AS `默认环境行数`,
    COALESCE(@environment_enabled, 0) AS `默认环境启用`,
    COALESCE(@system_count, 0) AS `IWS系统行数`,
    COALESCE(@system_enabled, 0) AS `IWS系统启用`,
    IF(@precheck_ok = 1 AND @environment_ok = 1, '前置校验通过',
      IF(@precheck_ok = 0, '前置校验失败，禁止写入',
        CONCAT('默认环境 ', @default_environment_code, ' 不存在或未启用，请先补齐并启用该环境')))
      AS `执行说明`;

SELECT JSON_EXTRACT(IF(@precheck_ok = 1 AND @environment_ok = 1, '{}', 'invalid'), '$')
    AS `前置结果`;

SET @system_changed = IF(
    @precheck_ok = 1 AND @environment_ok = 1
      AND (COALESCE(@system_count, 0) = 0 OR COALESCE(@system_enabled, 0) = 0),
    1, 0
);

SET @ddl = IF(@system_changed = 1 AND COALESCE(@system_count, 0) = 0, CONCAT(
    'INSERT INTO ', @upgrade_schema_q, '.`iws_endpoint_system`',
    ' (`system_code`, `display_name`, `description`, `enabled`, `revision`,',
    ' `create_by`, `create_time`, `update_by`, `update_time`)',
    ' VALUES (', QUOTE(@system_code), ', ', QUOTE(@system_name), ', ',
    QUOTE(@system_description), ', 1, 0, NULL, CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP)'
), IF(@system_changed = 1, CONCAT(
    'UPDATE ', @upgrade_schema_q, '.`iws_endpoint_system`',
    ' SET `enabled` = 1, `revision` = `revision` + 1,',
    ' `update_time` = CURRENT_TIMESTAMP WHERE `system_code` = ', QUOTE(@system_code)
), 'DO 0'));
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(@system_changed = 1, CONCAT(
    'UPDATE ', @upgrade_schema_q, '.`iws_endpoint_catalog_control`',
    ' SET `revision` = `revision` + 1, `update_time` = CURRENT_TIMESTAMP',
    ' WHERE `singleton_key` = ''CATALOG'''
), 'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @post_system_sql = CONCAT(
    'SELECT COUNT(*), COALESCE(MAX(`enabled`), 0), COALESCE(MAX(`revision`), -1)',
    ' INTO @final_system_count, @final_system_enabled, @final_system_revision FROM ', @upgrade_schema_q,
    '.`iws_endpoint_system` WHERE `system_code` = ', QUOTE(@system_code)
);
SET @ddl = IF(@precheck_ok = 1 AND @environment_ok = 1, @post_system_sql, 'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @post_control_sql = CONCAT(
    'SELECT COALESCE(MAX(`revision`), -1) INTO @final_catalog_revision FROM ',
    @upgrade_schema_q, '.`iws_endpoint_catalog_control`',
    ' WHERE `singleton_key` = ''CATALOG'''
);
SET @ddl = IF(@precheck_ok = 1 AND @environment_ok = 1, @post_control_sql, 'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @expected_system_revision = CASE
    WHEN @system_changed = 1 AND COALESCE(@system_count, 0) = 0 THEN 0
    WHEN @system_changed = 1 THEN COALESCE(@system_revision, -1) + 1
    ELSE COALESCE(@system_revision, -1)
END;
SET @expected_catalog_revision = COALESCE(@control_revision, -1) + COALESCE(@system_changed, 0);

SET @final_ok = IF(@precheck_ok = 1 AND @environment_ok = 1
    AND COALESCE(@final_system_count, 0) = 1
    AND COALESCE(@final_system_enabled, 0) = 1
    AND COALESCE(@final_system_revision, -1) = @expected_system_revision
    AND COALESCE(@final_catalog_revision, -1) = @expected_catalog_revision,
    1, 0);

SELECT
    IF(@final_ok = 1, '默认环境中的 IWS 系统已启用', 'IWS 系统配置未达到预期') AS `执行结果`,
    @upgrade_schema AS `目标库`,
    @default_environment_code AS `默认环境`,
    @system_code AS `系统码`,
    @final_system_count AS `IWS系统行数`,
    @final_system_enabled AS `IWS系统启用`,
    COALESCE(@final_system_revision, -1) AS `IWS系统版本`,
    COALESCE(@final_catalog_revision, -1) AS `目录版本`,
    IF(@final_ok = 1, 1, 0) AS `门禁结果`;

SELECT JSON_EXTRACT(IF(@final_ok = 1, '{}', 'invalid'), '$') AS `最终校验`;

SET @final_detail_sql = CONCAT(
    'SELECT `system_code` AS `系统码`, `display_name` AS `系统名称`,',
    ' `enabled` AS `启用状态`, `revision` AS `系统版本`, `create_time` AS `创建时间`',
    ' FROM ', @upgrade_schema_q, '.`iws_endpoint_system`',
    ' WHERE CAST(`system_code` AS BINARY) = CAST(', QUOTE(@system_code), ' AS BINARY)'
);
SET @ddl = IF(@precheck_ok = 1 AND @environment_ok = 1, @final_detail_sql, 'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
