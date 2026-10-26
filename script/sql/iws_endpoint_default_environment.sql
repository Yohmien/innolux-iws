/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 存量库补齐默认环境 TEST 与 IWS 系统；目标 MySQL 8.0，可重复执行；
 * @ModifyRecord : V1.0 新建；
 */

-- 目标：MySQL 8.0；单一目标库参数及默认环境参数见下方集中定义。
-- 执行：先执行 iws_plan_event_endpoint_schema.sql；本脚本要求 endpoint 环境表、系统表与唯一控制行已存在。
-- 幂等：环境码已存在时不写库，重复执行不推进目录版本；已存在的自定义环境保留原值。
-- 说明：补齐默认环境和 IWS 系统，不创建地址或凭据，不写审计行。

SET @upgrade_schema = 'innolux_iws';
SET @default_environment_code = 'TEST';
SET @default_environment_name = '测试环境';
SET @default_environment_description = '空库初始化默认环境，供首次启动使用；接入正式环境后可将本行停用';
SET @default_system_code = 'IWS';
SET @default_system_name = 'IWS+';
SET @default_system_description = '空库初始化默认系统，供 APK 等计划事件发布使用';

SET @upgrade_schema_q = CONCAT('`', REPLACE(@upgrade_schema, '`', '``'), '`');

SET @de_target_ok = (
    SELECT COUNT(*) = 1
    FROM information_schema.SCHEMATA
    WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@upgrade_schema AS BINARY)
);
SET @de_database_matches = (
    DATABASE() IS NOT NULL
    AND CAST(DATABASE() AS BINARY) = CAST(@upgrade_schema AS BINARY)
);
SET @de_version_ok = (VERSION() LIKE '8.0.%');
SET @de_tables_ok = (
    SELECT COUNT(*) = 4
    FROM information_schema.TABLES
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@upgrade_schema AS BINARY)
      AND TABLE_NAME IN ('iws_endpoint_environment', 'iws_endpoint_system',
        'iws_endpoint_catalog_control', 'iws_endpoint_audit')
);

SET @de_control_sql = CONCAT(
    'SELECT COUNT(*), COALESCE(MAX(`revision`), -1)',
    ' INTO @de_control_count, @de_control_revision FROM ', @upgrade_schema_q,
    '.`iws_endpoint_catalog_control` WHERE `singleton_key` = ''CATALOG'''
);
SET @de_ddl = IF(@de_target_ok = 1 AND @de_database_matches = 1 AND @de_version_ok = 1
    AND @de_tables_ok = 1, @de_control_sql, 'DO 0');
PREPARE de_stmt FROM @de_ddl;
EXECUTE de_stmt;
DEALLOCATE PREPARE de_stmt;

SET @de_env_sql = CONCAT(
    'SELECT COUNT(*), COALESCE(MAX(`enabled`), 0), COALESCE(MAX(`revision`), -1)',
    ' INTO @de_env_count, @de_env_enabled, @de_env_revision FROM ', @upgrade_schema_q,
    '.`iws_endpoint_environment` WHERE `environment_code` = ', QUOTE(@default_environment_code)
);
SET @de_ddl = IF(@de_target_ok = 1 AND @de_database_matches = 1 AND @de_version_ok = 1
    AND @de_tables_ok = 1, @de_env_sql, 'DO 0');
PREPARE de_stmt FROM @de_ddl;
EXECUTE de_stmt;
DEALLOCATE PREPARE de_stmt;

SET @de_system_sql = CONCAT(
    'SELECT COUNT(*), COALESCE(MAX(`enabled`), 0), COALESCE(MAX(`revision`), -1)',
    ' INTO @de_system_count, @de_system_enabled, @de_system_revision FROM ', @upgrade_schema_q,
    '.`iws_endpoint_system` WHERE `system_code` = ', QUOTE(@default_system_code)
);
SET @de_ddl = IF(@de_target_ok = 1 AND @de_database_matches = 1 AND @de_version_ok = 1
    AND @de_tables_ok = 1, @de_system_sql, 'DO 0');
PREPARE de_stmt FROM @de_ddl;
EXECUTE de_stmt;
DEALLOCATE PREPARE de_stmt;

SET @de_precheck_ok = (
    @de_target_ok = 1
    AND @de_database_matches = 1
    AND @de_version_ok = 1
    AND @de_tables_ok = 1
    AND COALESCE(@de_control_count, 0) = 1
    AND COALESCE(@de_control_revision, -1) >= 0
);
SET @de_existing_ok = (
    (COALESCE(@de_env_count, 0) = 0
      OR (COALESCE(@de_env_count, 0) = 1 AND COALESCE(@de_env_enabled, 0) = 1))
    AND (COALESCE(@de_system_count, 0) = 0
      OR (COALESCE(@de_system_count, 0) = 1 AND COALESCE(@de_system_enabled, 0) = 1))
);
SET @de_config_ok = IF(@de_precheck_ok = 1 AND @de_existing_ok = 1, 1, 0);

SELECT
    @de_target_ok AS `目标库存在`,
    @de_database_matches AS `当前连接库一致`,
    @de_version_ok AS `MySQL8版本`,
    @de_tables_ok AS `endpoint表就绪`,
    COALESCE(@de_control_count, 0) AS `目录控制行`,
    COALESCE(@de_env_count, 0) AS `默认环境行数`,
    COALESCE(@de_env_enabled, 0) AS `默认环境启用`,
    COALESCE(@de_system_count, 0) AS `IWS系统行数`,
    COALESCE(@de_system_enabled, 0) AS `IWS系统启用`,
    CASE
        WHEN @de_precheck_ok = 0 THEN '前置校验失败，禁止写入'
        WHEN @de_existing_ok = 0 THEN '既有默认环境或 IWS 系统已停用，禁止写入'
        ELSE '前置校验通过'
    END AS `执行说明`;

-- 执行器应在此停止；继续执行的客户端也会因无效 JSON 报错，后续写入仍受门控。
SELECT JSON_EXTRACT(IF(@de_config_ok = 1, '{}', 'invalid'), '$') AS `前置结果`;

SET @de_env_insert = IF(@de_config_ok = 1 AND COALESCE(@de_env_count, 0) = 0, 1, 0);
SET @de_ddl = IF(@de_env_insert = 1, CONCAT(
    'INSERT INTO ', @upgrade_schema_q, '.`iws_endpoint_environment`',
    ' (`environment_code`, `display_name`, `description`, `enabled`, `revision`,',
    ' `create_by`, `create_time`, `update_by`, `update_time`)',
    ' VALUES (', QUOTE(@default_environment_code), ', ', QUOTE(@default_environment_name), ', ',
    QUOTE(@default_environment_description), ', 1, 0, NULL, CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP)'
), 'DO 0');
PREPARE de_stmt FROM @de_ddl;
EXECUTE de_stmt;
DEALLOCATE PREPARE de_stmt;

SET @de_system_insert = IF(@de_config_ok = 1 AND COALESCE(@de_system_count, 0) = 0, 1, 0);

SET @de_ddl = IF(@de_system_insert = 1, CONCAT(
    'INSERT INTO ', @upgrade_schema_q, '.`iws_endpoint_system`',
    ' (`system_code`, `display_name`, `description`, `enabled`, `revision`,',
    ' `create_by`, `create_time`, `update_by`, `update_time`)',
    ' VALUES (', QUOTE(@default_system_code), ', ', QUOTE(@default_system_name), ', ',
    QUOTE(@default_system_description), ', 1, 0, NULL, CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP)'
), 'DO 0');
PREPARE de_stmt FROM @de_ddl;
EXECUTE de_stmt;
DEALLOCATE PREPARE de_stmt;

-- 目录版本随任一新对象推进一次，与页面新增一致；均已存在时不动。
SET @de_ddl = IF(@de_env_insert = 1 OR @de_system_insert = 1, CONCAT(
    'UPDATE ', @upgrade_schema_q, '.`iws_endpoint_catalog_control`',
    ' SET `revision` = `revision` + 1, `update_time` = CURRENT_TIMESTAMP',
    ' WHERE `singleton_key` = ''CATALOG'''
), 'DO 0');
PREPARE de_stmt FROM @de_ddl;
EXECUTE de_stmt;
DEALLOCATE PREPARE de_stmt;

SET @de_post_sql = CONCAT(
    'SELECT COUNT(*), COALESCE(MAX(`display_name`), ''''), COALESCE(MAX(`enabled`), 0),',
    ' COALESCE(MAX(`revision`), -1)',
    ' INTO @de_final_count, @de_final_name, @de_final_enabled, @de_final_env_revision FROM ',
    @upgrade_schema_q,
    '.`iws_endpoint_environment` WHERE `environment_code` = ', QUOTE(@default_environment_code)
);
SET @de_ddl = IF(@de_config_ok = 1, @de_post_sql, 'DO 0');
PREPARE de_stmt FROM @de_ddl;
EXECUTE de_stmt;
DEALLOCATE PREPARE de_stmt;

SET @de_revision_sql = CONCAT(
    'SELECT COALESCE(MAX(`revision`), -1) INTO @de_final_revision FROM ', @upgrade_schema_q,
    '.`iws_endpoint_catalog_control` WHERE `singleton_key` = ''CATALOG'''
);
SET @de_ddl = IF(@de_config_ok = 1, @de_revision_sql, 'DO 0');
PREPARE de_stmt FROM @de_ddl;
EXECUTE de_stmt;
DEALLOCATE PREPARE de_stmt;

SET @de_system_post_sql = CONCAT(
    'SELECT COUNT(*), COALESCE(MAX(`display_name`), ''''), COALESCE(MAX(`enabled`), 0),',
    ' COALESCE(MAX(`revision`), -1)',
    ' INTO @de_final_system_count, @de_final_system_name, @de_final_system_enabled,',
    ' @de_final_system_revision FROM ', @upgrade_schema_q,
    '.`iws_endpoint_system` WHERE `system_code` = ', QUOTE(@default_system_code)
);
SET @de_ddl = IF(@de_config_ok = 1, @de_system_post_sql, 'DO 0');
PREPARE de_stmt FROM @de_ddl;
EXECUTE de_stmt;
DEALLOCATE PREPARE de_stmt;

SET @de_expected_env_revision = IF(@de_env_insert = 1, 0, COALESCE(@de_env_revision, -1));
SET @de_expected_system_revision = IF(@de_system_insert = 1, 0, COALESCE(@de_system_revision, -1));
SET @de_expected_revision = COALESCE(@de_control_revision, -1)
    + IF(@de_env_insert = 1 OR @de_system_insert = 1, 1, 0);

SET @de_final_ok = IF(@de_config_ok = 1 AND COALESCE(@de_final_count, 0) = 1
    AND COALESCE(@de_final_enabled, 0) = 1
    AND COALESCE(@de_final_system_count, 0) = 1
    AND COALESCE(@de_final_system_enabled, 0) = 1
    AND COALESCE(@de_final_env_revision, -1) = @de_expected_env_revision
    AND COALESCE(@de_final_system_revision, -1) = @de_expected_system_revision
    AND COALESCE(@de_final_revision, -1) = @de_expected_revision, 1, 0);

SELECT
    CASE
        WHEN @de_config_ok = 0 THEN '前置校验失败，未写入'
        WHEN @de_final_ok = 0 THEN '写入后验收失败，请检查目录版本与既有配置'
        WHEN @de_env_insert = 1 AND @de_system_insert = 1 THEN '默认环境与 IWS 系统已新增'
        WHEN @de_env_insert = 1 THEN '默认环境已新增，IWS 系统已存在'
        WHEN @de_system_insert = 1 THEN 'IWS 系统已新增，默认环境已存在'
        ELSE '默认环境与 IWS 系统已存在且启用，未变更'
    END AS `执行结果`,
    @upgrade_schema AS `目标库`,
    @default_environment_code AS `环境码`,
    COALESCE(@de_final_name, '') AS `环境名称`,
    COALESCE(@de_final_enabled, 0) AS `启用状态`,
    COALESCE(@de_final_env_revision, -1) AS `环境版本`,
    COALESCE(@de_final_system_name, '') AS `系统名称`,
    COALESCE(@de_final_system_enabled, 0) AS `系统启用状态`,
    COALESCE(@de_final_system_revision, -1) AS `系统版本`,
    COALESCE(@de_final_revision, -1) AS `目录版本`;

SELECT JSON_EXTRACT(IF(@de_final_ok = 1, '{}', 'invalid'), '$') AS `最终校验`;

-- 后置验收查询：供执行人独立复核。
SET @de_env_detail_sql = CONCAT(
    'SELECT `environment_code` AS `环境码`, `display_name` AS `环境名称`,',
    ' `enabled` AS `启用状态`, `revision` AS `环境版本`, `create_time` AS `创建时间`',
    ' FROM ', @upgrade_schema_q, '.`iws_endpoint_environment`',
    ' ORDER BY `environment_code`'
);
SET @de_ddl = IF(@de_config_ok = 1, @de_env_detail_sql, 'DO 0');
PREPARE de_stmt FROM @de_ddl;
EXECUTE de_stmt;
DEALLOCATE PREPARE de_stmt;

SET @de_system_detail_sql = CONCAT(
    'SELECT `system_code` AS `系统码`, `display_name` AS `系统名称`,',
    ' `enabled` AS `启用状态`, `revision` AS `系统版本`, `create_time` AS `创建时间`',
    ' FROM ', @upgrade_schema_q, '.`iws_endpoint_system`',
    ' ORDER BY `system_code`'
);
SET @de_ddl = IF(@de_config_ok = 1, @de_system_detail_sql, 'DO 0');
PREPARE de_stmt FROM @de_ddl;
EXECUTE de_stmt;
DEALLOCATE PREPARE de_stmt;
