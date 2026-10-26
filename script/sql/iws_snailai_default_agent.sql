-- ============================================================================
-- IWS+ SnailAI 默认 AI 助手初始化与升级
-- ============================================================================
-- 目标数据库：MySQL 8.0
-- 用途：为已初始化的数据库补齐默认 AI 助手（sai_agent.id=1，IWS+ 助手），
--       并为 app_id='1' 且尚未订阅任何智能体的 OpenAPI 用户回填默认助手订阅。
-- 前置：sai_app 必须已有启用的 app_id='1'；缺失时先执行 iws_snailai_default_app.sql。
-- 执行性质：可重复执行；默认助手已存在且名称一致、用户订阅已满足时不做变更。
-- 执行：由同一会话顺序执行；支持 mysql 客户端与可执行 PREPARE 的 SQL 客户端。
-- 安全：所有检查、写入和验收均受唯一目标库参数约束；前置、冲突或后置校验
--       失败时以无效 JSON 报错，并以 DO 门控后续写入。执行器必须遇错停止，
--       禁止 --force 或 GUI 续错。
-- 说明：默认助手显式绑定对话模型 id=1，首次使用只需在 AI 控制台为
--       该模型补充 API Key。脚本不创建触发器，不覆盖默认助手已有记录
--       （包括 chat_model_id、instruction、greeting 等编辑），也不改动已有其他订阅。
-- ============================================================================

SET NAMES utf8mb4;

-- 唯一目标库配置：只修改下一行；目标库必须已存在且与当前连接库字节一致。
SET @snailai_agent_schema = 'innolux-iws';
SET @snailai_agent_schema_q = CONCAT('`', REPLACE(@snailai_agent_schema, '`', '``'), '`');

-- 默认助手目标值：与 iws_init_from_empty.sql 的默认 AI 助手种子一致。
SET @snailai_default_agent_id = 1;
SET @snailai_default_agent_name = 'IWS+ 助手';
SET @snailai_default_agent_description = 'IWS+ 默认通用助手，首次使用只需为默认对话模型补充 API Key。';
SET @snailai_default_agent_instruction = '你是 IWS+ 的默认通用助手。请用简体中文准确、简洁地回答用户问题，必要时给出操作步骤和示例。你不直接执行仓储、生产或外部系统中的真实操作；涉及实际变更时，请提示用户通过对应业务页面确认和处理。';
SET @snailai_default_agent_greeting = '你好，我是 IWS+ 助手，可以为你解答系统使用和日常工作中的问题。';
SET @snailai_default_agent_preset = '["IWS+ 可以帮我处理哪些工作？","怎么查看指定仓库的库存？","计划事件平台怎么使用？"]';

-- 目标库、版本与结构前置检查。
SET @snailai_target_ok = (
    SELECT COUNT(*) = 1
    FROM information_schema.SCHEMATA
    WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@snailai_agent_schema AS BINARY)
);
SET @snailai_database_matches = (
    DATABASE() IS NOT NULL
    AND CAST(DATABASE() AS BINARY) = CAST(@snailai_agent_schema AS BINARY)
);
SET @snailai_version_ok = (VERSION() LIKE '8.0.%');
SET @snailai_tables_ok = (
    SELECT COUNT(*) = 5
    FROM information_schema.TABLES
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@snailai_agent_schema AS BINARY)
      AND TABLE_NAME IN ('sai_agent', 'sai_model_config', 'sai_user_agent', 'sai_openapi_user', 'sai_app')
      AND TABLE_TYPE = 'BASE TABLE'
);
SET @snailai_agent_columns_ok = (
    SELECT COUNT(DISTINCT COLUMN_NAME) = 24
    FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@snailai_agent_schema AS BINARY)
      AND TABLE_NAME = 'sai_agent'
      AND COLUMN_NAME IN (
          'id', 'name', 'description', 'avatar', 'instruction', 'greeting',
          'preset_questions', 'chat_model_id', 'memory_enabled', 'mcp_enabled',
          'skill_enabled', 'web_search_enabled', 'rag_enabled', 'rag_ids',
          'rag_call_mode', 'short_term_memory_size', 'creator_id', 'is_featured',
          'view_count', 'status', 'config', 'app_id', 'create_dt', 'update_dt'
      )
);
SET @snailai_model_columns_ok = (
    SELECT COUNT(DISTINCT COLUMN_NAME) = 5
    FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@snailai_agent_schema AS BINARY)
      AND TABLE_NAME = 'sai_model_config'
      AND COLUMN_NAME IN ('id', 'model_type', 'scope', 'is_default', 'is_enabled')
);
SET @snailai_subscription_columns_ok = (
    SELECT COUNT(DISTINCT COLUMN_NAME) = 3
    FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@snailai_agent_schema AS BINARY)
      AND TABLE_NAME = 'sai_user_agent'
      AND COLUMN_NAME IN ('id', 'user_id', 'agent_id')
);
SET @snailai_openapi_columns_ok = (
    SELECT COUNT(DISTINCT COLUMN_NAME) = 3
    FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@snailai_agent_schema AS BINARY)
      AND TABLE_NAME = 'sai_openapi_user'
      AND COLUMN_NAME IN ('id', 'app_id', 'platform_user_id')
);
SET @snailai_app_columns_ok = (
    SELECT COUNT(DISTINCT COLUMN_NAME) = 2
    FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@snailai_agent_schema AS BINARY)
      AND TABLE_NAME = 'sai_app'
      AND COLUMN_NAME IN ('app_id', 'status')
);
SET @snailai_columns_ok = (
    @snailai_agent_columns_ok = 1
    AND @snailai_model_columns_ok = 1
    AND @snailai_subscription_columns_ok = 1
    AND @snailai_openapi_columns_ok = 1
    AND @snailai_app_columns_ok = 1
);
SET @snailai_struct_ok = (
    @snailai_target_ok = 1
    AND @snailai_database_matches = 1
    AND @snailai_version_ok = 1
    AND @snailai_tables_ok = 1
    AND @snailai_columns_ok = 1
);

-- 结构与库门控通过后，再读取默认应用与默认对话模型，避免缺表时报错。
SET @snailai_app_count = 0;
SET @snailai_default_model_count = 0;
SET @snailai_chat_model_id_ok = 0;

SET @snailai_sql = IF(@snailai_struct_ok = 1,
    CONCAT(
        'SELECT COUNT(*) INTO @snailai_app_count FROM ', @snailai_agent_schema_q,
        '.sai_app WHERE app_id = ''1'' AND status = 1'
    ),
    'DO 0');
PREPARE snailai_stmt FROM @snailai_sql;
EXECUTE snailai_stmt;
DEALLOCATE PREPARE snailai_stmt;

SET @snailai_sql = IF(@snailai_struct_ok = 1,
    CONCAT(
        'SELECT COUNT(*) INTO @snailai_default_model_count FROM ', @snailai_agent_schema_q,
        '.sai_model_config WHERE model_type = ''CHAT'' AND scope = ''GLOBAL''',
        ' AND is_default = 1 AND is_enabled = 1'
    ),
    'DO 0');
PREPARE snailai_stmt FROM @snailai_sql;
EXECUTE snailai_stmt;
DEALLOCATE PREPARE snailai_stmt;

SET @snailai_sql = IF(@snailai_struct_ok = 1,
    CONCAT(
        'SELECT COUNT(*) INTO @snailai_chat_model_id_ok FROM ', @snailai_agent_schema_q,
        '.sai_model_config WHERE id = 1 AND model_type = ''CHAT'' AND scope = ''GLOBAL''',
        ' AND is_enabled = 1'
    ),
    'DO 0');
PREPARE snailai_stmt FROM @snailai_sql;
EXECUTE snailai_stmt;
DEALLOCATE PREPARE snailai_stmt;

SET @snailai_precheck_ok = (
    @snailai_struct_ok = 1
    AND @snailai_app_count = 1
    AND @snailai_chat_model_id_ok = 1
);

SELECT
    @snailai_agent_schema AS `目标库`,
    @snailai_target_ok AS `目标库存在`,
    @snailai_database_matches AS `当前连接库一致`,
    @snailai_version_ok AS `MySQL8版本`,
    @snailai_tables_ok AS `必要表存在`,
    @snailai_columns_ok AS `必要列完整`,
    @snailai_app_count AS `默认应用行数`,
    @snailai_default_model_count AS `系统默认CHAT模型数`,
    @snailai_chat_model_id_ok AS `绑定模型id=1符合`,
    CASE
        WHEN @snailai_struct_ok = 0 THEN '必要表或列不符合，禁止写入'
        WHEN @snailai_app_count <> 1 THEN '默认应用缺失或未启用，请先执行 iws_snailai_default_app.sql'
        WHEN @snailai_chat_model_id_ok <> 1 THEN '模型 id=1 不是启用的 GLOBAL CHAT 模型，请先核对模型配置'
        ELSE '前置校验通过'
    END AS `执行说明`;

SELECT JSON_EXTRACT(IF(@snailai_precheck_ok = 1, '{}', 'invalid'), '$') AS `前置校验`;

SET @snailai_gate = @snailai_precheck_ok;
SET @snailai_existing_agent_count = 0;
SET @snailai_existing_agent_name = NULL;
SET @snailai_before_agent_rows = 0;
SET @snailai_before_openapi_users = 0;
SET @snailai_before_subscribed_users = 0;
SET @snailai_before_subscription_rows = 0;
SET @snailai_backfilled_rows = 0;
SET @snailai_final_agent_rows = 0;
SET @snailai_final_default_agent_count = 0;
SET @snailai_final_openapi_users = 0;
SET @snailai_final_uncovered_users = 0;
SET @snailai_final_subscription_rows = 0;

-- 读取 sai_agent.id=1 是否存在及其名称；前置失败或客户端续错时执行无副作用的 DO 0。
SET @snailai_sql = IF(@snailai_gate = 1,
    CONCAT(
        'SELECT COUNT(*) INTO @snailai_existing_agent_count FROM ', @snailai_agent_schema_q,
        '.sai_agent WHERE id = ?'
    ),
    'DO ?');
PREPARE snailai_stmt FROM @snailai_sql;
EXECUTE snailai_stmt USING @snailai_default_agent_id;
DEALLOCATE PREPARE snailai_stmt;

SET @snailai_sql = IF(@snailai_gate = 1,
    CONCAT(
        'SELECT name INTO @snailai_existing_agent_name FROM ', @snailai_agent_schema_q,
        '.sai_agent WHERE id = ?'
    ),
    'DO ?');
PREPARE snailai_stmt FROM @snailai_sql;
EXECUTE snailai_stmt USING @snailai_default_agent_id;
DEALLOCATE PREPARE snailai_stmt;

-- 读取执行前的行数，用于结果对比。
SET @snailai_sql = IF(@snailai_gate = 1,
    CONCAT('SELECT COUNT(*) INTO @snailai_before_agent_rows FROM ', @snailai_agent_schema_q, '.sai_agent'),
    'DO 0');
PREPARE snailai_stmt FROM @snailai_sql;
EXECUTE snailai_stmt;
DEALLOCATE PREPARE snailai_stmt;

SET @snailai_sql = IF(@snailai_gate = 1,
    CONCAT(
        'SELECT COUNT(*) INTO @snailai_before_openapi_users FROM ', @snailai_agent_schema_q,
        '.sai_openapi_user WHERE app_id = ''1'''
    ),
    'DO 0');
PREPARE snailai_stmt FROM @snailai_sql;
EXECUTE snailai_stmt;
DEALLOCATE PREPARE snailai_stmt;

SET @snailai_sql = IF(@snailai_gate = 1,
    CONCAT(
        'SELECT COUNT(*) INTO @snailai_before_subscribed_users FROM ', @snailai_agent_schema_q,
        '.sai_openapi_user u WHERE u.app_id = ''1''',
        ' AND EXISTS (SELECT 1 FROM ', @snailai_agent_schema_q,
        '.sai_user_agent ua WHERE ua.user_id = u.platform_user_id)'
    ),
    'DO 0');
PREPARE snailai_stmt FROM @snailai_sql;
EXECUTE snailai_stmt;
DEALLOCATE PREPARE snailai_stmt;

SET @snailai_sql = IF(@snailai_gate = 1,
    CONCAT('SELECT COUNT(*) INTO @snailai_before_subscription_rows FROM ', @snailai_agent_schema_q, '.sai_user_agent'),
    'DO 0');
PREPARE snailai_stmt FROM @snailai_sql;
EXECUTE snailai_stmt;
DEALLOCATE PREPARE snailai_stmt;

SET @snailai_before_candidates = @snailai_before_openapi_users - @snailai_before_subscribed_users;

-- id=1 已被其他名称占用时明确失败；名称一致时保留用户已有编辑。
SET @snailai_conflict_ok = IF(
    @snailai_gate = 1
    AND @snailai_existing_agent_count IN (0, 1)
    AND NOT (
        @snailai_existing_agent_count = 1
        AND NOT (CAST(@snailai_existing_agent_name AS BINARY) <=> CAST(@snailai_default_agent_name AS BINARY))
    ),
    1,
    0
);

SELECT
    @snailai_agent_schema AS `目标库`,
    @snailai_before_agent_rows AS `执行前智能体行数`,
    @snailai_before_openapi_users AS `执行前OpenAPI用户数`,
    @snailai_before_subscribed_users AS `执行前已订阅用户数`,
    @snailai_before_candidates AS `待回填用户数`,
    CASE
        WHEN @snailai_gate = 0 THEN NULL
        WHEN @snailai_existing_agent_count = 0 THEN '无'
        WHEN @snailai_conflict_ok = 1 THEN '无'
        ELSE CONCAT('id=1 已被其他名称占用: ', COALESCE(@snailai_existing_agent_name, ''))
    END AS `冲突说明`,
    CASE
        WHEN @snailai_gate = 0 THEN '前置校验失败，禁止写入'
        WHEN @snailai_existing_agent_count = 0 THEN '未找到默认助手，将新增'
        WHEN @snailai_conflict_ok = 1 THEN '默认助手已存在，保留现有配置'
        ELSE '默认助手 id=1 已被其他名称占用，禁止覆盖'
    END AS `执行说明`;

SELECT JSON_EXTRACT(IF(@snailai_conflict_ok = 1, '{}', 'invalid'), '$') AS `助手冲突校验`;

-- 仅在默认助手缺失时新增并绑定对话模型 id=1；已存在同名称记录时不覆盖聊天模型、instruction、greeting 等编辑。
SET @snailai_sql = IF(@snailai_conflict_ok = 1 AND @snailai_existing_agent_count = 0,
    CONCAT(
        'INSERT INTO ', @snailai_agent_schema_q,
        '.sai_agent (id, name, description, avatar, instruction, greeting, preset_questions,',
        ' chat_model_id, memory_enabled, mcp_enabled, skill_enabled, web_search_enabled,',
        ' rag_enabled, rag_ids, rag_call_mode, short_term_memory_size, creator_id,',
        ' is_featured, view_count, status, config, app_id, create_dt, update_dt)',
        ' VALUES (?, ?, ?, NULL, ?, ?, ?, 1, 1, 0, 0, 0, 0, NULL, 1, 20, 1, 1, 0, 1, NULL, ''1'', NOW(), NOW())'
    ),
    'DO ?, ?, ?, ?, ?, ?');
PREPARE snailai_stmt FROM @snailai_sql;
EXECUTE snailai_stmt USING
    @snailai_default_agent_id,
    @snailai_default_agent_name,
    @snailai_default_agent_description,
    @snailai_default_agent_instruction,
    @snailai_default_agent_greeting,
    @snailai_default_agent_preset;
DEALLOCATE PREPARE snailai_stmt;

-- 为 app_id='1' 且没有任何订阅的 OpenAPI 用户回填默认助手订阅；已有其他订阅的用户不动。
SET @snailai_sql = IF(@snailai_conflict_ok = 1,
    CONCAT(
        'INSERT INTO ', @snailai_agent_schema_q,
        '.sai_user_agent (user_id, agent_id, create_dt)',
        ' SELECT u.platform_user_id, ?, NOW() FROM ', @snailai_agent_schema_q, '.sai_openapi_user u',
        ' WHERE u.app_id = ''1''',
        ' AND NOT EXISTS (SELECT 1 FROM ', @snailai_agent_schema_q,
        '.sai_user_agent ua WHERE ua.user_id = u.platform_user_id)'
    ),
    'DO ?');
PREPARE snailai_stmt FROM @snailai_sql;
EXECUTE snailai_stmt USING @snailai_default_agent_id;
SET @snailai_backfilled_rows = IF(@snailai_conflict_ok = 1, ROW_COUNT(), 0);
DEALLOCATE PREPARE snailai_stmt;

-- 后置验收：id=1 的默认助手恰好一行且名称一致，OpenAPI 用户不再存在无订阅用户。
SET @snailai_sql = IF(@snailai_gate = 1,
    CONCAT('SELECT COUNT(*) INTO @snailai_final_agent_rows FROM ', @snailai_agent_schema_q, '.sai_agent'),
    'DO 0');
PREPARE snailai_stmt FROM @snailai_sql;
EXECUTE snailai_stmt;
DEALLOCATE PREPARE snailai_stmt;

SET @snailai_sql = IF(@snailai_gate = 1,
    CONCAT(
        'SELECT COUNT(*) INTO @snailai_final_default_agent_count FROM ', @snailai_agent_schema_q,
        '.sai_agent WHERE id = ? AND name = ?'
    ),
    'DO ?, ?');
PREPARE snailai_stmt FROM @snailai_sql;
EXECUTE snailai_stmt USING @snailai_default_agent_id, @snailai_default_agent_name;
DEALLOCATE PREPARE snailai_stmt;

SET @snailai_sql = IF(@snailai_gate = 1,
    CONCAT(
        'SELECT COUNT(*) INTO @snailai_final_openapi_users FROM ', @snailai_agent_schema_q,
        '.sai_openapi_user WHERE app_id = ''1'''
    ),
    'DO 0');
PREPARE snailai_stmt FROM @snailai_sql;
EXECUTE snailai_stmt;
DEALLOCATE PREPARE snailai_stmt;

SET @snailai_sql = IF(@snailai_gate = 1,
    CONCAT(
        'SELECT COUNT(*) INTO @snailai_final_uncovered_users FROM ', @snailai_agent_schema_q,
        '.sai_openapi_user u WHERE u.app_id = ''1''',
        ' AND NOT EXISTS (SELECT 1 FROM ', @snailai_agent_schema_q,
        '.sai_user_agent ua WHERE ua.user_id = u.platform_user_id)'
    ),
    'DO 0');
PREPARE snailai_stmt FROM @snailai_sql;
EXECUTE snailai_stmt;
DEALLOCATE PREPARE snailai_stmt;

SET @snailai_sql = IF(@snailai_gate = 1,
    CONCAT('SELECT COUNT(*) INTO @snailai_final_subscription_rows FROM ', @snailai_agent_schema_q, '.sai_user_agent'),
    'DO 0');
PREPARE snailai_stmt FROM @snailai_sql;
EXECUTE snailai_stmt;
DEALLOCATE PREPARE snailai_stmt;

SET @snailai_final_ok = (
    @snailai_conflict_ok = 1
    AND @snailai_final_default_agent_count = 1
    AND @snailai_final_uncovered_users = 0
    AND @snailai_backfilled_rows = @snailai_before_candidates
);

SELECT
    @snailai_agent_schema AS `目标库`,
    @snailai_before_agent_rows AS `执行前智能体行数`,
    @snailai_final_agent_rows AS `执行后智能体行数`,
    @snailai_final_default_agent_count AS `执行后默认助手行数`,
    @snailai_before_openapi_users AS `执行前OpenAPI用户数`,
    @snailai_final_openapi_users AS `执行后OpenAPI用户数`,
    @snailai_before_candidates AS `待回填用户数`,
    @snailai_backfilled_rows AS `本次回填订阅行数`,
    @snailai_final_uncovered_users AS `执行后未订阅用户数`,
    CASE
        WHEN @snailai_gate = 0 THEN NULL
        WHEN @snailai_conflict_ok = 0 THEN CONCAT('id=1 已被其他名称占用: ', COALESCE(@snailai_existing_agent_name, ''))
        ELSE '无'
    END AS `冲突说明`,
    IF(@snailai_final_ok = 1, '默认助手初始化/升级完成', '默认助手验收失败，请检查上述不变量') AS `执行结果`;

SELECT JSON_EXTRACT(IF(@snailai_final_ok = 1, '{}', 'invalid'), '$') AS `最终校验`;
