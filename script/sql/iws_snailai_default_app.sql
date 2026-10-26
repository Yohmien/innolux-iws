-- ============================================================================
-- IWS+ SnailAI 默认应用初始化与升级
-- ============================================================================
-- 目标数据库：MySQL 8.0
-- 用途：为已初始化的数据库补齐与主服务默认配置一致的 SnailAI 应用（app_id=1）。
-- 执行性质：可重复执行；已存在完全一致的默认应用时不做变更。
-- 执行：由同一会话顺序执行；支持 mysql 客户端与可执行 PREPARE 的 SQL 客户端。
-- 安全：所有检查、写入和验收均受唯一目标库参数约束；前置、冲突或后置校验
--       失败时以无效 JSON 报错，并以 DO 0 门控后续写入。执行器必须遇错停止，
--       禁止 --force 或 GUI 续错。
-- 说明：本脚本只在缺失时新增默认应用；不会覆盖既有应用的自定义 token、名称、
--       描述、路由策略或状态。默认 token 与主服务 dev/prod 默认配置一致，
--       属于开发接入模板值，生产部署应同时更换应用记录和主服务配置。
-- ============================================================================

SET NAMES utf8mb4;

-- 唯一目标库配置：只修改下一行；目标库必须已存在且与当前连接库字节一致。
SET @snailai_app_schema = 'innolux-iws';
SET @snailai_app_schema_q = CONCAT('`', REPLACE(@snailai_app_schema, '`', '``'), '`');

-- 默认应用目标值：与 iws-admin dev/prod 的 snail-ai 默认配置一致。
SET @snailai_app_id = '1';
SET @snailai_default_app_name = 'IWS+';
SET @snailai_default_app_description = 'IWS+ 主应用，供主服务 SnailAI 客户端接入';
SET @snailai_default_app_token = 'SAI_566a6bfbc26e4998b4841cc927d50c5d';
SET @snailai_default_app_route = 'LEAST_LOAD';
SET @snailai_default_app_status = 1;

SET @snailai_target_ok = (
    SELECT COUNT(*) = 1
    FROM information_schema.SCHEMATA
    WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@snailai_app_schema AS BINARY)
);
SET @snailai_database_matches = (
    DATABASE() IS NOT NULL
    AND CAST(DATABASE() AS BINARY) = CAST(@snailai_app_schema AS BINARY)
);
SET @snailai_version_ok = (VERSION() LIKE '8.0.%');
SET @snailai_table_ok = (
    SELECT COUNT(*) = 1
    FROM information_schema.TABLES
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@snailai_app_schema AS BINARY)
      AND TABLE_NAME = 'sai_app'
      AND TABLE_TYPE = 'BASE TABLE'
);
SET @snailai_columns_ok = (
    SELECT COUNT(*) = 6
       AND SUM(COLUMN_NAME = 'app_id' AND DATA_TYPE = 'varchar' AND IS_NULLABLE = 'NO') = 1
       AND SUM(COLUMN_NAME = 'app_name' AND DATA_TYPE = 'varchar' AND IS_NULLABLE = 'NO') = 1
       AND SUM(COLUMN_NAME = 'token' AND DATA_TYPE = 'varchar' AND IS_NULLABLE = 'NO') = 1
    FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@snailai_app_schema AS BINARY)
      AND TABLE_NAME = 'sai_app'
      AND COLUMN_NAME IN ('app_id', 'app_name', 'description', 'token', 'route_strategy', 'status')
);
SET @snailai_unique_ok = (
    SELECT COUNT(*) >= 1
    FROM (
        SELECT INDEX_NAME
        FROM information_schema.STATISTICS
        WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@snailai_app_schema AS BINARY)
          AND TABLE_NAME = 'sai_app'
          AND NON_UNIQUE = 0
        GROUP BY INDEX_NAME
        HAVING COUNT(*) = 1 AND MAX(COLUMN_NAME) = 'app_id'
    ) uk
);
SET @snailai_precheck_ok = (
    @snailai_target_ok = 1
    AND @snailai_database_matches = 1
    AND @snailai_version_ok = 1
    AND @snailai_table_ok = 1
    AND @snailai_columns_ok = 1
    AND @snailai_unique_ok = 1
);

SELECT
    @snailai_app_schema AS `目标库`,
    @snailai_target_ok AS `目标库存在`,
    @snailai_database_matches AS `当前连接库一致`,
    @snailai_version_ok AS `MySQL8版本`,
    @snailai_table_ok AS `应用表存在`,
    @snailai_columns_ok AS `必要列完整`,
    @snailai_unique_ok AS `app_id唯一键存在`,
    IF(@snailai_precheck_ok = 1, '前置校验通过', '前置校验失败，禁止写入') AS `执行说明`;

SELECT JSON_EXTRACT(IF(@snailai_precheck_ok = 1, '{}', 'invalid'), '$') AS `前置校验`;

SET @snailai_gate = @snailai_precheck_ok;
SET @snailai_existing_count = NULL;
SET @snailai_conflict_fields = NULL;
SET @snailai_final_count = NULL;
SET @snailai_final_conflicts = NULL;

-- 读取既有应用；前置失败或客户端续错时执行无副作用的 DO 0。
SET @snailai_sql = IF(@snailai_gate = 1,
    CONCAT(
        'SELECT COUNT(*) INTO @snailai_existing_count FROM ',
        @snailai_app_schema_q, '.`sai_app` WHERE `app_id` = ?'
    ),
    'DO ?');
PREPARE snailai_stmt FROM @snailai_sql;
EXECUTE snailai_stmt USING @snailai_app_id;
DEALLOCATE PREPARE snailai_stmt;

-- 仅当 app_id=1 恰好存在一行时比较全部受管字段；文本按字节比较。
SET @snailai_sql = IF(@snailai_gate = 1 AND @snailai_existing_count = 1,
    CONCAT(
        'SELECT CONCAT_WS('','',',
        'IF(NOT (CAST(`app_name` AS BINARY) <=> CAST(? AS BINARY)), ''app_name'', NULL),',
        'IF(NOT (CAST(`description` AS BINARY) <=> CAST(? AS BINARY)), ''description'', NULL),',
        'IF(NOT (CAST(`token` AS BINARY) <=> CAST(? AS BINARY)), ''token'', NULL),',
        'IF(NOT (CAST(`route_strategy` AS BINARY) <=> CAST(? AS BINARY)), ''route_strategy'', NULL),',
        'IF(NOT (`status` <=> ?), ''status'', NULL)',
        ') INTO @snailai_conflict_fields FROM ',
        @snailai_app_schema_q, '.`sai_app` WHERE `app_id` = ?'
    ),
    'DO ?, ?, ?, ?, ?, ?');
PREPARE snailai_stmt FROM @snailai_sql;
EXECUTE snailai_stmt USING
    @snailai_default_app_name,
    @snailai_default_app_description,
    @snailai_default_app_token,
    @snailai_default_app_route,
    @snailai_default_app_status,
    @snailai_app_id;
DEALLOCATE PREPARE snailai_stmt;

SET @snailai_conflict_ok = IF(
    @snailai_gate = 1
    AND @snailai_existing_count IN (0, 1)
    AND NOT (
        @snailai_existing_count = 1
        AND @snailai_conflict_fields IS NOT NULL
        AND CHAR_LENGTH(@snailai_conflict_fields) > 0
    ),
    1,
    0
);
SET @snailai_write_gate = @snailai_conflict_ok;

SELECT
    @snailai_app_schema AS `目标库`,
    COALESCE(@snailai_existing_count, -1) AS `执行前应用行数`,
    @snailai_conflict_fields AS `冲突字段`,
    CASE
        WHEN @snailai_gate = 0 THEN '前置校验失败，禁止写入'
        WHEN @snailai_existing_count = 0 THEN '未找到默认应用，将新增'
        WHEN @snailai_conflict_ok = 1 THEN '默认应用已存在且完全一致，无需变更'
        ELSE '默认应用已存在但字段不一致，禁止覆盖'
    END AS `执行说明`;

SELECT JSON_EXTRACT(IF(@snailai_conflict_ok = 1, '{}', 'invalid'), '$') AS `应用冲突校验`;

-- 仅在默认应用缺失时新增；主键 id 由自增生成，不参与业务键判断。
SET @snailai_sql = IF(@snailai_write_gate = 1 AND @snailai_existing_count = 0,
    CONCAT(
        'INSERT INTO ', @snailai_app_schema_q,
        '.`sai_app` (`app_id`, `app_name`, `description`, `token`, `route_strategy`, `status`)',
        ' VALUES (?, ?, ?, ?, ?, ?)'
    ),
    'DO ?, ?, ?, ?, ?, ?');
PREPARE snailai_stmt FROM @snailai_sql;
EXECUTE snailai_stmt USING
    @snailai_app_id,
    @snailai_default_app_name,
    @snailai_default_app_description,
    @snailai_default_app_token,
    @snailai_default_app_route,
    @snailai_default_app_status;
DEALLOCATE PREPARE snailai_stmt;

-- 后置验收：按业务键确认恰好一行，且全部受管字段与目标一致。
SET @snailai_sql = IF(@snailai_gate = 1,
    CONCAT(
        'SELECT COUNT(*) INTO @snailai_final_count FROM ',
        @snailai_app_schema_q, '.`sai_app` WHERE `app_id` = ?'
    ),
    'DO ?');
PREPARE snailai_stmt FROM @snailai_sql;
EXECUTE snailai_stmt USING @snailai_app_id;
DEALLOCATE PREPARE snailai_stmt;

SET @snailai_sql = IF(@snailai_gate = 1,
    CONCAT(
        'SELECT CONCAT_WS('','',',
        'IF(NOT (CAST(`app_name` AS BINARY) <=> CAST(? AS BINARY)), ''app_name'', NULL),',
        'IF(NOT (CAST(`description` AS BINARY) <=> CAST(? AS BINARY)), ''description'', NULL),',
        'IF(NOT (CAST(`token` AS BINARY) <=> CAST(? AS BINARY)), ''token'', NULL),',
        'IF(NOT (CAST(`route_strategy` AS BINARY) <=> CAST(? AS BINARY)), ''route_strategy'', NULL),',
        'IF(NOT (`status` <=> ?), ''status'', NULL)',
        ') INTO @snailai_final_conflicts FROM ',
        @snailai_app_schema_q, '.`sai_app` WHERE `app_id` = ?'
    ),
    'DO ?, ?, ?, ?, ?, ?');
PREPARE snailai_stmt FROM @snailai_sql;
EXECUTE snailai_stmt USING
    @snailai_default_app_name,
    @snailai_default_app_description,
    @snailai_default_app_token,
    @snailai_default_app_route,
    @snailai_default_app_status,
    @snailai_app_id;
DEALLOCATE PREPARE snailai_stmt;

SET @snailai_final_ok = (
    @snailai_gate = 1
    AND @snailai_final_count = 1
    AND @snailai_final_conflicts IS NOT NULL
    AND CHAR_LENGTH(@snailai_final_conflicts) = 0
);

SELECT
    @snailai_app_schema AS `目标库`,
    @snailai_existing_count AS `执行前应用行数`,
    @snailai_final_count AS `执行后应用行数`,
    @snailai_final_conflicts AS `最终冲突字段`,
    IF(@snailai_final_ok = 1, '默认应用初始化/升级完成', '默认应用验收失败，请检查上述不变量') AS `执行结果`;

SELECT JSON_EXTRACT(IF(@snailai_final_ok = 1, '{}', 'invalid'), '$') AS `最终校验`;
