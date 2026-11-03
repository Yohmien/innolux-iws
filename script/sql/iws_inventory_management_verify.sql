-- ============================================================================
-- IWS+ 在库管理公司最小闭环只读验收（INV-MVP-01）
-- ============================================================================
-- 目标数据库：MySQL 8.0+
-- 执行性质：只读；不建表、不改数据、不写菜单、不创建临时表或存储程序。
-- 适用范围：
--   已执行 iws_inventory_management_upgrade.sql 的旧库，或已执行
--   iws_init_from_empty.sql 的新空库。
-- 输出：
--   逐项中文结果；结构、数据或菜单不符合时最终门禁返回非零错误。
-- 执行：
--   mysql --protocol=TCP --host=<主机> --port=<端口> --user=<只读账号> \
--     --default-character-set=utf8mb4 --database=<目标库> --batch --raw \
--     --execute="source script/sql/iws_inventory_management_verify.sql"
-- 参数：
--   目标库集中在下方，执行前只改一处。
-- ============================================================================

SET NAMES utf8mb4;
SET @saved_group_concat_max_len = @@SESSION.group_concat_max_len;
SET SESSION group_concat_max_len = 1048576;

SET @target_schema = 'innolux-iws';

-- ----------------------------------------------------------------------------
-- 一、目标库与基础对象
-- ----------------------------------------------------------------------------
SET @schema_ok = (
    DATABASE() IS NOT NULL
    AND CAST(DATABASE() AS BINARY) = CAST(@target_schema AS BINARY)
);
SET @version_ok = (VERSION() LIKE '8.%');
SET @target_table_count = (
    SELECT COUNT(*)
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_TYPE = 'BASE TABLE'
      AND TABLE_NAME IN (
          'wms_company',
          'wms_company_management_scope',
          'wms_company_management_grant',
          'wms_object_audit',
          'wms_command_attempt'
      )
);
SET @sys_menu_exists_ok = (
    SELECT COUNT(*) = 1
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sys_menu'
      AND TABLE_TYPE = 'BASE TABLE'
);
SET @sys_menu_engine_ok = (
    SELECT COUNT(*) = 1
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sys_menu'
      AND TABLE_TYPE = 'BASE TABLE'
      AND ENGINE = 'InnoDB'
);
-- 完整 PRIMARY 必须恰为单列 menu_id；复合主键即使首列是 menu_id 也必须拒绝。
SET @sys_menu_pk_ok = (
    SELECT COUNT(*) = 1
       AND MIN(SEQ_IN_INDEX) = 1
       AND MAX(SEQ_IN_INDEX) = 1
       AND MAX(COLUMN_NAME) = 'menu_id'
       AND MAX(NON_UNIQUE) = 0
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sys_menu'
      AND INDEX_NAME = 'PRIMARY'
);
SET @sys_menu_types_ok = (
    SELECT COUNT(*) = 22
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sys_menu'
      AND (
          (COLUMN_NAME = 'menu_id' AND LOWER(COLUMN_TYPE) = 'bigint' AND IS_NULLABLE = 'NO')
          OR (COLUMN_NAME = 'menu_name' AND LOWER(COLUMN_TYPE) = 'varchar(50)' AND IS_NULLABLE = 'NO')
          OR (COLUMN_NAME = 'parent_id' AND LOWER(COLUMN_TYPE) = 'bigint')
          OR (COLUMN_NAME = 'order_num' AND LOWER(COLUMN_TYPE) = 'int')
          OR (COLUMN_NAME = 'path' AND LOWER(COLUMN_TYPE) = 'varchar(200)')
          OR (COLUMN_NAME = 'component' AND LOWER(COLUMN_TYPE) = 'varchar(255)')
          OR (COLUMN_NAME = 'query_param' AND LOWER(COLUMN_TYPE) = 'varchar(255)')
          OR (COLUMN_NAME = 'is_frame' AND LOWER(COLUMN_TYPE) = 'char(1)')
          OR (COLUMN_NAME = 'is_cache' AND LOWER(COLUMN_TYPE) = 'char(1)')
          OR (COLUMN_NAME = 'menu_type' AND LOWER(COLUMN_TYPE) = 'char(1)')
          OR (COLUMN_NAME = 'visible' AND LOWER(COLUMN_TYPE) = 'char(1)')
          OR (COLUMN_NAME = 'status' AND LOWER(COLUMN_TYPE) = 'char(1)')
          OR (COLUMN_NAME = 'perms' AND LOWER(COLUMN_TYPE) = 'varchar(100)')
          OR (COLUMN_NAME = 'icon' AND LOWER(COLUMN_TYPE) = 'varchar(100)')
          OR (COLUMN_NAME = 'active_menu' AND LOWER(COLUMN_TYPE) = 'varchar(255)')
          OR (COLUMN_NAME = 'ext' AND LOWER(COLUMN_TYPE) = 'varchar(2000)')
          OR (COLUMN_NAME = 'create_dept' AND LOWER(COLUMN_TYPE) = 'bigint')
          OR (COLUMN_NAME = 'create_by' AND LOWER(COLUMN_TYPE) = 'bigint')
          OR (COLUMN_NAME = 'create_time' AND LOWER(COLUMN_TYPE) = 'datetime')
          OR (COLUMN_NAME = 'update_by' AND LOWER(COLUMN_TYPE) = 'bigint')
          OR (COLUMN_NAME = 'update_time' AND LOWER(COLUMN_TYPE) = 'datetime')
          OR (COLUMN_NAME = 'remark' AND LOWER(COLUMN_TYPE) = 'varchar(500)')
      )
);
SET @base_ok = (
    @schema_ok = 1
    AND @version_ok = 1
    AND @target_table_count = 5
    AND @sys_menu_exists_ok = 1
    AND @sys_menu_engine_ok = 1
    AND @sys_menu_pk_ok = 1
    AND @sys_menu_types_ok = 1
);
SELECT
    @target_schema AS `目标库参数`,
    DATABASE() AS `当前库`,
    VERSION() AS `数据库版本`,
    @target_table_count AS `目标表数量`,
    @sys_menu_engine_ok AS `sys_menu InnoDB`,
    @sys_menu_pk_ok AS `sys_menu主键恰为单列menu_id`,
    @sys_menu_types_ok AS `sys_menu关键列类型`,
    IF(@base_ok = 1, '通过', '失败：目标库、版本、5 张目标表或 sys_menu 结构不满足') AS `基础结果`;
SELECT JSON_EXTRACT(IF(@base_ok = 1, '{}', 'invalid'), '$') AS `基础门禁`;

-- ----------------------------------------------------------------------------
-- 二、表选项
-- ----------------------------------------------------------------------------
SET @tbl_mismatch = (
    SELECT COUNT(*)
    FROM (
        SELECT 'wms_company' AS table_name, 'InnoDB' AS engine,
               'utf8mb4_0900_ai_ci' AS table_collation, 'WMS公司主档' AS table_comment
        UNION ALL SELECT 'wms_company_management_scope', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS公司管理范围头'
        UNION ALL SELECT 'wms_company_management_grant', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS公司管理角色授权明细'
        UNION ALL SELECT 'wms_object_audit', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS对象变更审计，只追加'
        UNION ALL SELECT 'wms_command_attempt', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS非计划受控命令尝试'
    ) e
    LEFT JOIN information_schema.TABLES t
        ON t.TABLE_SCHEMA = DATABASE()
       AND t.TABLE_NAME = e.table_name
    WHERE t.TABLE_NAME IS NULL
       OR t.TABLE_TYPE <> 'BASE TABLE'
       OR t.ENGINE <> e.engine
       OR t.TABLE_COLLATION <> e.table_collation
       OR t.TABLE_COMMENT <> e.table_comment
);
SELECT '目标表选项' AS `校验项`, IF(@tbl_mismatch = 0, '通过', '失败') AS `结果`,
       @tbl_mismatch AS `不一致表数`;
SELECT JSON_EXTRACT(IF(@tbl_mismatch = 0, '{}', 'invalid'), '$') AS `表选项门禁`;

-- ----------------------------------------------------------------------------
-- 三、列定义（名称、顺序、类型、空值、默认值、字符集）
-- 预览脚本原先不比较列注释，所以这里的预期关系不增加注释列；
-- 默认值使用 NULL-safe 二进制比较，区分 SQL NULL、真实字符串 '<NULL>'
-- 及任何字母大小写差异。
-- ----------------------------------------------------------------------------
SET @col_mismatch = (
    SELECT COUNT(*)
    FROM (
        SELECT 'wms_company' AS table_name, 1 AS ordinal_position, 'company_id' AS column_name,
               'bigint' AS column_type, 'NO' AS is_nullable, NULL AS column_default, NULL AS collation_name
        UNION ALL SELECT 'wms_company', 2, 'company_code', 'varchar(64)', 'NO', NULL, 'utf8mb4_bin'
        UNION ALL SELECT 'wms_company', 3, 'company_name', 'varchar(100)', 'NO', NULL, 'utf8mb4_0900_ai_ci'
        UNION ALL SELECT 'wms_company', 4, 'enabled', 'tinyint', 'NO', '0', NULL
        UNION ALL SELECT 'wms_company', 5, 'lifecycle_status', 'varchar(16)', 'NO', 'ACTIVE', 'utf8mb4_bin'
        UNION ALL SELECT 'wms_company', 6, 'revision', 'int', 'NO', '1', NULL
        UNION ALL SELECT 'wms_company', 7, 'remark', 'varchar(500)', 'YES', NULL, 'utf8mb4_0900_ai_ci'
        UNION ALL SELECT 'wms_company', 8, 'create_dept', 'bigint', 'YES', NULL, NULL
        UNION ALL SELECT 'wms_company', 9, 'create_by', 'bigint', 'YES', NULL, NULL
        UNION ALL SELECT 'wms_company', 10, 'create_time', 'datetime(3)', 'NO', 'CURRENT_TIMESTAMP(3)', NULL
        UNION ALL SELECT 'wms_company', 11, 'update_by', 'bigint', 'YES', NULL, NULL
        UNION ALL SELECT 'wms_company', 12, 'update_time', 'datetime(3)', 'YES', NULL, NULL

        UNION ALL SELECT 'wms_company_management_scope', 1, 'company_id', 'bigint', 'NO', NULL, NULL
        UNION ALL SELECT 'wms_company_management_scope', 2, 'scope_revision', 'int', 'NO', '0', NULL
        UNION ALL SELECT 'wms_company_management_scope', 3, 'updated_by', 'bigint', 'YES', NULL, NULL
        UNION ALL SELECT 'wms_company_management_scope', 4, 'updated_at', 'datetime(3)', 'YES', NULL, NULL

        UNION ALL SELECT 'wms_company_management_grant', 1, 'grant_id', 'bigint', 'NO', NULL, NULL
        UNION ALL SELECT 'wms_company_management_grant', 2, 'company_id', 'bigint', 'NO', NULL, NULL
        UNION ALL SELECT 'wms_company_management_grant', 3, 'role_id', 'bigint', 'NO', NULL, NULL
        UNION ALL SELECT 'wms_company_management_grant', 4, 'created_by', 'bigint', 'YES', NULL, NULL
        UNION ALL SELECT 'wms_company_management_grant', 5, 'created_at', 'datetime(3)', 'NO', 'CURRENT_TIMESTAMP(3)', NULL

        UNION ALL SELECT 'wms_object_audit', 1, 'audit_id', 'bigint', 'NO', NULL, NULL
        UNION ALL SELECT 'wms_object_audit', 2, 'object_type', 'varchar(64)', 'NO', NULL, 'utf8mb4_bin'
        UNION ALL SELECT 'wms_object_audit', 3, 'object_id', 'bigint', 'NO', NULL, NULL
        UNION ALL SELECT 'wms_object_audit', 4, 'action', 'varchar(64)', 'NO', NULL, 'utf8mb4_bin'
        UNION ALL SELECT 'wms_object_audit', 5, 'command_key', 'varchar(64)', 'NO', NULL, 'utf8mb4_bin'
        UNION ALL SELECT 'wms_object_audit', 6, 'before_summary', 'varchar(2000)', 'YES', NULL, 'utf8mb4_0900_ai_ci'
        UNION ALL SELECT 'wms_object_audit', 7, 'after_summary', 'varchar(2000)', 'YES', NULL, 'utf8mb4_0900_ai_ci'
        UNION ALL SELECT 'wms_object_audit', 8, 'actor_user_id', 'bigint', 'YES', NULL, NULL
        UNION ALL SELECT 'wms_object_audit', 9, 'actor_name', 'varchar(64)', 'YES', NULL, 'utf8mb4_0900_ai_ci'
        UNION ALL SELECT 'wms_object_audit', 10, 'initiator_type', 'varchar(32)', 'YES', NULL, 'utf8mb4_bin'
        UNION ALL SELECT 'wms_object_audit', 11, 'initiator_id', 'varchar(128)', 'YES', NULL, 'utf8mb4_0900_ai_ci'
        UNION ALL SELECT 'wms_object_audit', 12, 'initiator_name', 'varchar(64)', 'YES', NULL, 'utf8mb4_0900_ai_ci'
        UNION ALL SELECT 'wms_object_audit', 13, 'dept_id', 'bigint', 'YES', NULL, NULL
        UNION ALL SELECT 'wms_object_audit', 14, 'dept_name', 'varchar(128)', 'YES', NULL, 'utf8mb4_0900_ai_ci'
        UNION ALL SELECT 'wms_object_audit', 15, 'execution_subject', 'varchar(64)', 'YES', NULL, 'utf8mb4_0900_ai_ci'
        UNION ALL SELECT 'wms_object_audit', 16, 'execution_subject_type', 'varchar(32)', 'YES', NULL, 'utf8mb4_bin'
        UNION ALL SELECT 'wms_object_audit', 17, 'execution_account', 'varchar(64)', 'YES', NULL, 'utf8mb4_0900_ai_ci'
        UNION ALL SELECT 'wms_object_audit', 18, 'result', 'varchar(16)', 'NO', NULL, 'utf8mb4_bin'
        UNION ALL SELECT 'wms_object_audit', 19, 'evidence_json', 'varchar(2000)', 'YES', NULL, 'utf8mb4_0900_ai_ci'
        UNION ALL SELECT 'wms_object_audit', 20, 'occurred_at', 'datetime(3)', 'NO', NULL, NULL
        UNION ALL SELECT 'wms_object_audit', 21, 'recorded_at', 'datetime(3)', 'NO', 'CURRENT_TIMESTAMP(3)', NULL

        UNION ALL SELECT 'wms_command_attempt', 1, 'attempt_id', 'bigint', 'NO', NULL, NULL
        UNION ALL SELECT 'wms_command_attempt', 2, 'command_key', 'varchar(64)', 'NO', NULL, 'utf8mb4_bin'
        UNION ALL SELECT 'wms_command_attempt', 3, 'action', 'varchar(64)', 'NO', NULL, 'utf8mb4_bin'
        UNION ALL SELECT 'wms_command_attempt', 4, 'object_type', 'varchar(64)', 'NO', NULL, 'utf8mb4_bin'
        UNION ALL SELECT 'wms_command_attempt', 5, 'object_id', 'bigint', 'YES', NULL, NULL
        UNION ALL SELECT 'wms_command_attempt', 6, 'input_digest', 'char(64)', 'NO', NULL, 'utf8mb4_0900_ai_ci'
        UNION ALL SELECT 'wms_command_attempt', 7, 'input_summary', 'varchar(2000)', 'YES', NULL, 'utf8mb4_0900_ai_ci'
        UNION ALL SELECT 'wms_command_attempt', 8, 'status', 'varchar(16)', 'NO', NULL, 'utf8mb4_bin'
        UNION ALL SELECT 'wms_command_attempt', 9, 'error_code', 'varchar(64)', 'YES', NULL, 'utf8mb4_bin'
        UNION ALL SELECT 'wms_command_attempt', 10, 'error_message', 'varchar(500)', 'YES', NULL, 'utf8mb4_0900_ai_ci'
        UNION ALL SELECT 'wms_command_attempt', 11, 'fact_type', 'varchar(64)', 'YES', NULL, 'utf8mb4_bin'
        UNION ALL SELECT 'wms_command_attempt', 12, 'fact_id', 'varchar(128)', 'YES', NULL, 'utf8mb4_0900_ai_ci'
        UNION ALL SELECT 'wms_command_attempt', 13, 'actor_user_id', 'bigint', 'YES', NULL, NULL
        UNION ALL SELECT 'wms_command_attempt', 14, 'actor_name', 'varchar(64)', 'YES', NULL, 'utf8mb4_0900_ai_ci'
        UNION ALL SELECT 'wms_command_attempt', 15, 'dept_id', 'bigint', 'YES', NULL, NULL
        UNION ALL SELECT 'wms_command_attempt', 16, 'dept_name', 'varchar(128)', 'YES', NULL, 'utf8mb4_0900_ai_ci'
        UNION ALL SELECT 'wms_command_attempt', 17, 'initiator_name', 'varchar(64)', 'YES', NULL, 'utf8mb4_0900_ai_ci'
        UNION ALL SELECT 'wms_command_attempt', 18, 'execution_subject', 'varchar(64)', 'YES', NULL, 'utf8mb4_0900_ai_ci'
        UNION ALL SELECT 'wms_command_attempt', 19, 'execution_subject_type', 'varchar(32)', 'YES', NULL, 'utf8mb4_bin'
        UNION ALL SELECT 'wms_command_attempt', 20, 'execution_account', 'varchar(64)', 'YES', NULL, 'utf8mb4_0900_ai_ci'
        UNION ALL SELECT 'wms_command_attempt', 21, 'occurred_at', 'datetime(3)', 'NO', NULL, NULL
        UNION ALL SELECT 'wms_command_attempt', 22, 'recorded_at', 'datetime(3)', 'NO', 'CURRENT_TIMESTAMP(3)', NULL
    ) e
    LEFT JOIN information_schema.COLUMNS c
        ON c.TABLE_SCHEMA = DATABASE()
       AND c.TABLE_NAME = e.table_name
       AND c.COLUMN_NAME = e.column_name
    WHERE c.COLUMN_NAME IS NULL
       OR c.ORDINAL_POSITION <> e.ordinal_position
       OR LOWER(c.COLUMN_TYPE) <> LOWER(e.column_type)
       OR c.IS_NULLABLE <> e.is_nullable
       OR NOT (CAST(c.COLUMN_DEFAULT AS BINARY) <=> CAST(e.column_default AS BINARY))
       OR NOT (CAST(c.COLLATION_NAME AS BINARY) <=> CAST(e.collation_name AS BINARY))
);
SET @col_count_mismatch = (
    SELECT COUNT(*)
    FROM (
        SELECT 'wms_company' AS table_name, 12 AS expected_count
        UNION ALL SELECT 'wms_company_management_scope', 4
        UNION ALL SELECT 'wms_company_management_grant', 5
        UNION ALL SELECT 'wms_object_audit', 21
        UNION ALL SELECT 'wms_command_attempt', 22
    ) e
    LEFT JOIN (
        SELECT TABLE_NAME, COUNT(*) AS actual_count
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME IN (
              'wms_company',
              'wms_company_management_scope',
              'wms_company_management_grant',
              'wms_object_audit',
              'wms_command_attempt'
          )
        GROUP BY TABLE_NAME
    ) a ON a.TABLE_NAME = e.table_name
    WHERE a.actual_count IS NULL
       OR a.actual_count <> e.expected_count
);
SET @on_update_mismatch = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_company'
      AND COLUMN_NAME = 'update_time'
      AND LOWER(EXTRA) NOT LIKE '%on update current_timestamp%'
);
SET @comment_mismatch = (
    SELECT COUNT(*)
    FROM (
        SELECT 'wms_company' AS table_name,
               '3bb40b2d497115591326384fbc2a0da0969bc8c33c222638897ce6876c1875dd' AS expected_hash
        UNION ALL SELECT 'wms_company_management_scope',
               '7870182e8b13b78ac244f19af876c23b98b6eb32e3f695c6ff7ce3961716a4f9'
        UNION ALL SELECT 'wms_company_management_grant',
               '8aa33d98efe4f4a73157c1c72e57ca9c9b57a7dea954b51e6e45fc616f85051f'
        UNION ALL SELECT 'wms_object_audit',
               '15d4f37463cf044ac305f57d6be51565179c423aba5a37f461ec605f4446022f'
        UNION ALL SELECT 'wms_command_attempt',
               '594458034bbf4c834cceb1bb7f94cdb97d57aba42a2016096a9f81c004c95d69'
    ) e
    LEFT JOIN (
        SELECT TABLE_NAME,
               SHA2(CONCAT('[',
                   GROUP_CONCAT(
                       CAST(JSON_ARRAY(ORDINAL_POSITION, COLUMN_NAME, COLUMN_COMMENT)
                            AS CHAR CHARACTER SET utf8mb4)
                       ORDER BY ORDINAL_POSITION SEPARATOR ','
                   ),
               ']'), 256) AS actual_hash
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME IN (
              'wms_company', 'wms_company_management_scope',
              'wms_company_management_grant', 'wms_object_audit',
              'wms_command_attempt'
          )
        GROUP BY TABLE_NAME
    ) a ON a.TABLE_NAME = e.table_name
    WHERE a.actual_hash IS NULL
       OR CAST(a.actual_hash AS BINARY) <> CAST(e.expected_hash AS BINARY)
);
SET @col_extra_mismatch = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME IN (
          'wms_company',
          'wms_company_management_scope',
          'wms_company_management_grant',
          'wms_object_audit',
          'wms_command_attempt'
      )
      AND (
          LOWER(EXTRA) LIKE '%auto_increment%'
          OR LOWER(EXTRA) LIKE '%virtual generated%'
          OR LOWER(EXTRA) LIKE '%stored generated%'
      )
);
SELECT '目标表列定义' AS `校验项`,
       IF(@col_mismatch = 0 AND @col_count_mismatch = 0 AND @on_update_mismatch = 0
          AND @col_extra_mismatch = 0 AND @comment_mismatch = 0, '通过', '失败') AS `结果`,
       @col_mismatch AS `不一致列数`,
       @col_count_mismatch AS `列数不一致表数`,
       @on_update_mismatch AS `更新时间自动维护不符数`,
       @col_extra_mismatch AS `自增或生成列不符数`,
       @comment_mismatch AS `注释签名不一致表数`;
SELECT JSON_EXTRACT(IF(@col_mismatch = 0 AND @col_count_mismatch = 0
                       AND @on_update_mismatch = 0 AND @col_extra_mismatch = 0
                       AND @comment_mismatch = 0,
                       '{}', 'invalid'), '$') AS `列定义门禁`;

-- ----------------------------------------------------------------------------
-- 四、索引（名称、唯一性、顺序、列、类型）
-- ----------------------------------------------------------------------------
SET @idx_mismatch = (
    SELECT COUNT(*)
    FROM (
        SELECT 'wms_company' AS table_name, 'PRIMARY' AS index_name, 0 AS non_unique, 1 AS seq_in_index, 'company_id' AS column_name
        UNION ALL SELECT 'wms_company', 'uk_wms_company_code', 0, 1, 'company_code'
        UNION ALL SELECT 'wms_company', 'idx_wms_company_lifecycle', 1, 1, 'lifecycle_status'
        UNION ALL SELECT 'wms_company', 'idx_wms_company_lifecycle', 1, 2, 'enabled'
        UNION ALL SELECT 'wms_company_management_scope', 'PRIMARY', 0, 1, 'company_id'
        UNION ALL SELECT 'wms_company_management_grant', 'PRIMARY', 0, 1, 'grant_id'
        UNION ALL SELECT 'wms_company_management_grant', 'uk_wms_company_grant', 0, 1, 'company_id'
        UNION ALL SELECT 'wms_company_management_grant', 'uk_wms_company_grant', 0, 2, 'role_id'
        UNION ALL SELECT 'wms_company_management_grant', 'idx_wms_company_grant_role', 1, 1, 'role_id'
        UNION ALL SELECT 'wms_company_management_grant', 'idx_wms_company_grant_role', 1, 2, 'company_id'
        UNION ALL SELECT 'wms_object_audit', 'PRIMARY', 0, 1, 'audit_id'
        UNION ALL SELECT 'wms_object_audit', 'idx_wms_object_audit_object', 1, 1, 'object_type'
        UNION ALL SELECT 'wms_object_audit', 'idx_wms_object_audit_object', 1, 2, 'object_id'
        UNION ALL SELECT 'wms_object_audit', 'idx_wms_object_audit_object', 1, 3, 'occurred_at'
        UNION ALL SELECT 'wms_object_audit', 'idx_wms_object_audit_command_key', 1, 1, 'command_key'
        UNION ALL SELECT 'wms_command_attempt', 'PRIMARY', 0, 1, 'attempt_id'
        UNION ALL SELECT 'wms_command_attempt', 'uk_wms_command_attempt_command', 0, 1, 'command_key'
        UNION ALL SELECT 'wms_command_attempt', 'idx_wms_command_attempt_object', 1, 1, 'object_type'
        UNION ALL SELECT 'wms_command_attempt', 'idx_wms_command_attempt_object', 1, 2, 'object_id'
        UNION ALL SELECT 'wms_command_attempt', 'idx_wms_command_attempt_object', 1, 3, 'occurred_at'
        UNION ALL SELECT 'wms_command_attempt', 'idx_wms_command_attempt_status', 1, 1, 'status'
        UNION ALL SELECT 'wms_command_attempt', 'idx_wms_command_attempt_status', 1, 2, 'occurred_at'
    ) e
    LEFT JOIN information_schema.STATISTICS s
        ON s.TABLE_SCHEMA = DATABASE()
       AND s.TABLE_NAME = e.table_name
       AND s.INDEX_NAME = e.index_name
       AND s.SEQ_IN_INDEX = e.seq_in_index
    WHERE s.INDEX_NAME IS NULL
       OR s.NON_UNIQUE <> e.non_unique
       OR NOT (CAST(s.COLUMN_NAME AS BINARY) <=> CAST(e.column_name AS BINARY))
       OR s.INDEX_TYPE <> 'BTREE'
       OR s.SUB_PART IS NOT NULL
       OR s.EXPRESSION IS NOT NULL
       OR s.IS_VISIBLE <> 'YES'
       OR NOT (CAST(s.COLLATION AS BINARY) <=> CAST('A' AS BINARY))
);
SET @idx_count_mismatch = (
    SELECT COUNT(*)
    FROM (
        SELECT 'wms_company' AS table_name, 3 AS expected_count
        UNION ALL SELECT 'wms_company_management_scope', 1
        UNION ALL SELECT 'wms_company_management_grant', 3
        UNION ALL SELECT 'wms_object_audit', 3
        UNION ALL SELECT 'wms_command_attempt', 4
    ) e
    LEFT JOIN (
        SELECT TABLE_NAME, COUNT(DISTINCT INDEX_NAME) AS actual_count
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME IN (
              'wms_company',
              'wms_company_management_scope',
              'wms_company_management_grant',
              'wms_object_audit',
              'wms_command_attempt'
          )
        GROUP BY TABLE_NAME
    ) a ON a.TABLE_NAME = e.table_name
    WHERE a.actual_count IS NULL
       OR a.actual_count <> e.expected_count
);
SET @idx_part_mismatch = (
    SELECT COUNT(*)
    FROM (
        SELECT table_name, index_name, COUNT(*) AS expected_parts,
               MAX(seq_in_index) AS expected_last
        FROM (
            SELECT 'wms_company' AS table_name, 'PRIMARY' AS index_name, 1 AS seq_in_index
            UNION ALL SELECT 'wms_company', 'uk_wms_company_code', 1
            UNION ALL SELECT 'wms_company', 'idx_wms_company_lifecycle', 1
            UNION ALL SELECT 'wms_company', 'idx_wms_company_lifecycle', 2
            UNION ALL SELECT 'wms_company_management_scope', 'PRIMARY', 1
            UNION ALL SELECT 'wms_company_management_grant', 'PRIMARY', 1
            UNION ALL SELECT 'wms_company_management_grant', 'uk_wms_company_grant', 1
            UNION ALL SELECT 'wms_company_management_grant', 'uk_wms_company_grant', 2
            UNION ALL SELECT 'wms_company_management_grant', 'idx_wms_company_grant_role', 1
            UNION ALL SELECT 'wms_company_management_grant', 'idx_wms_company_grant_role', 2
            UNION ALL SELECT 'wms_object_audit', 'PRIMARY', 1
            UNION ALL SELECT 'wms_object_audit', 'idx_wms_object_audit_object', 1
            UNION ALL SELECT 'wms_object_audit', 'idx_wms_object_audit_object', 2
            UNION ALL SELECT 'wms_object_audit', 'idx_wms_object_audit_object', 3
            UNION ALL SELECT 'wms_object_audit', 'idx_wms_object_audit_command_key', 1
            UNION ALL SELECT 'wms_command_attempt', 'PRIMARY', 1
            UNION ALL SELECT 'wms_command_attempt', 'uk_wms_command_attempt_command', 1
            UNION ALL SELECT 'wms_command_attempt', 'idx_wms_command_attempt_object', 1
            UNION ALL SELECT 'wms_command_attempt', 'idx_wms_command_attempt_object', 2
            UNION ALL SELECT 'wms_command_attempt', 'idx_wms_command_attempt_object', 3
            UNION ALL SELECT 'wms_command_attempt', 'idx_wms_command_attempt_status', 1
            UNION ALL SELECT 'wms_command_attempt', 'idx_wms_command_attempt_status', 2
        ) x
        GROUP BY table_name, index_name
    ) e
    LEFT JOIN (
        SELECT TABLE_NAME, INDEX_NAME, COUNT(*) AS actual_parts,
               MAX(SEQ_IN_INDEX) AS actual_last
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME IN (
              'wms_company',
              'wms_company_management_scope',
              'wms_company_management_grant',
              'wms_object_audit',
              'wms_command_attempt'
          )
        GROUP BY TABLE_NAME, INDEX_NAME
    ) a ON a.TABLE_NAME = e.table_name
       AND a.INDEX_NAME = e.index_name
    WHERE a.INDEX_NAME IS NULL
       OR a.actual_parts <> e.expected_parts
       OR a.actual_last <> e.expected_last
);
SET @idx_extra_count = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS s
    LEFT JOIN (
        SELECT 'wms_company' AS table_name, 'PRIMARY' AS index_name, 1 AS seq_in_index
        UNION ALL SELECT 'wms_company', 'uk_wms_company_code', 1
        UNION ALL SELECT 'wms_company', 'idx_wms_company_lifecycle', 1
        UNION ALL SELECT 'wms_company', 'idx_wms_company_lifecycle', 2
        UNION ALL SELECT 'wms_company_management_scope', 'PRIMARY', 1
        UNION ALL SELECT 'wms_company_management_grant', 'PRIMARY', 1
        UNION ALL SELECT 'wms_company_management_grant', 'uk_wms_company_grant', 1
        UNION ALL SELECT 'wms_company_management_grant', 'uk_wms_company_grant', 2
        UNION ALL SELECT 'wms_company_management_grant', 'idx_wms_company_grant_role', 1
        UNION ALL SELECT 'wms_company_management_grant', 'idx_wms_company_grant_role', 2
        UNION ALL SELECT 'wms_object_audit', 'PRIMARY', 1
        UNION ALL SELECT 'wms_object_audit', 'idx_wms_object_audit_object', 1
        UNION ALL SELECT 'wms_object_audit', 'idx_wms_object_audit_object', 2
        UNION ALL SELECT 'wms_object_audit', 'idx_wms_object_audit_object', 3
        UNION ALL SELECT 'wms_object_audit', 'idx_wms_object_audit_command_key', 1
        UNION ALL SELECT 'wms_command_attempt', 'PRIMARY', 1
        UNION ALL SELECT 'wms_command_attempt', 'uk_wms_command_attempt_command', 1
        UNION ALL SELECT 'wms_command_attempt', 'idx_wms_command_attempt_object', 1
        UNION ALL SELECT 'wms_command_attempt', 'idx_wms_command_attempt_object', 2
        UNION ALL SELECT 'wms_command_attempt', 'idx_wms_command_attempt_object', 3
        UNION ALL SELECT 'wms_command_attempt', 'idx_wms_command_attempt_status', 1
        UNION ALL SELECT 'wms_command_attempt', 'idx_wms_command_attempt_status', 2
    ) e
        ON e.table_name = s.TABLE_NAME
       AND e.index_name = s.INDEX_NAME
       AND e.seq_in_index = s.SEQ_IN_INDEX
    WHERE s.TABLE_SCHEMA = DATABASE()
      AND s.TABLE_NAME IN (
          'wms_company',
          'wms_company_management_scope',
          'wms_company_management_grant',
          'wms_object_audit',
          'wms_command_attempt'
      )
      AND e.table_name IS NULL
);
SELECT '目标表索引' AS `校验项`,
       IF(@idx_mismatch = 0 AND @idx_count_mismatch = 0
          AND @idx_part_mismatch = 0 AND @idx_extra_count = 0, '通过', '失败') AS `结果`,
       @idx_mismatch AS `不一致索引列数`,
       @idx_count_mismatch AS `索引数不一致表数`,
       @idx_part_mismatch AS `索引列数不一致数`,
       @idx_extra_count AS `多余索引列数`;
SELECT JSON_EXTRACT(IF(@idx_mismatch = 0 AND @idx_count_mismatch = 0
                       AND @idx_part_mismatch = 0 AND @idx_extra_count = 0,
                       '{}', 'invalid'), '$') AS `索引门禁`;

-- ----------------------------------------------------------------------------
-- 五、数据守恒与逻辑关联
-- ----------------------------------------------------------------------------
SET @company_invalid_count = (
    SELECT COUNT(*)
    FROM wms_company
    WHERE lifecycle_status NOT IN ('ACTIVE', 'RETIRING', 'RETIRED')
       OR enabled NOT IN (0, 1)
       OR revision < 1
       OR company_code = ''
);
SET @scope_invalid_count = (
    SELECT
        (SELECT COUNT(*)
         FROM wms_company c
         LEFT JOIN wms_company_management_scope s ON s.company_id = c.company_id
         WHERE s.company_id IS NULL)
      + (SELECT COUNT(*)
         FROM wms_company_management_scope s
         LEFT JOIN wms_company c ON c.company_id = s.company_id
         WHERE c.company_id IS NULL)
      + (SELECT COUNT(*) FROM wms_company_management_scope WHERE scope_revision < 0)
);
SET @grant_invalid_count = (
    SELECT
        (SELECT COUNT(*)
         FROM wms_company_management_grant g
         LEFT JOIN wms_company c ON c.company_id = g.company_id
         WHERE c.company_id IS NULL)
      + (SELECT COUNT(*) FROM wms_company_management_grant WHERE role_id IS NULL OR role_id <= 0)
);
SET @audit_invalid_count = (
    SELECT COUNT(*)
    FROM wms_object_audit
    WHERE result NOT IN ('SUCCESS', 'REJECTED', 'FAILED', 'UNKNOWN')
       OR command_key = ''
       OR object_type = ''
       OR execution_subject_type = ''
       OR execution_account = ''
);
SET @attempt_invalid_count = (
    SELECT COUNT(*)
    FROM wms_command_attempt
    WHERE status NOT IN ('ACCEPTED', 'PROCESSING', 'APPLIED', 'REJECTED', 'FAILED', 'UNKNOWN')
       OR command_key = ''
       OR action = ''
       OR CHAR_LENGTH(input_digest) <> 64
       OR execution_subject_type = ''
       OR execution_account = ''
);
SET @attempt_missing_fact_count = (
    SELECT COUNT(*)
    FROM wms_command_attempt
    WHERE status = 'APPLIED' AND (fact_type IS NULL OR fact_id IS NULL)
);
SET @grant_orphan_role_count = (
    SELECT COUNT(*)
    FROM wms_company_management_grant g
    LEFT JOIN sys_role r ON r.role_id = g.role_id
    WHERE r.role_id IS NULL
);
SET @data_ok = (
    @company_invalid_count = 0
    AND @scope_invalid_count = 0
    AND @grant_invalid_count = 0
    AND @audit_invalid_count = 0
    AND @attempt_invalid_count = 0
);
SELECT '公司、范围、授权、审计与尝试数据' AS `校验项`,
       IF(@data_ok = 1, '通过', '失败') AS `结果`,
       @company_invalid_count AS `公司非法行数`,
       @scope_invalid_count AS `范围头缺失或非法行数`,
       @grant_invalid_count AS `授权孤儿或非法行数`,
       @audit_invalid_count AS `审计非法行数`,
       @attempt_invalid_count AS `尝试非法行数`,
       @attempt_missing_fact_count AS `已生效但缺事实引用（提示）`,
       @grant_orphan_role_count AS `引用已删除角色（提示）`;
SELECT JSON_EXTRACT(IF(@data_ok = 1, '{}', 'invalid'), '$') AS `数据门禁`;

-- ----------------------------------------------------------------------------
-- 六、公司最小闭环菜单与权限
-- ----------------------------------------------------------------------------
-- 既有在库管理目录基线：只校验，不更新。
SET @inventory_parent_missing = (
    SELECT COUNT(*)
    FROM (
        SELECT 1761400000000020004 AS menu_id, '在库管理' AS menu_name, 0 AS parent_id,
               13 AS order_num, 'iws-inventory' AS path, NULL AS component,
               '' AS query_param, 'N' AS is_frame, 'Y' AS is_cache, 'M' AS menu_type,
               '0' AS visible, '0' AS status, '' AS perms, 'list' AS icon,
               '' AS active_menu, '' AS ext
    ) e
    LEFT JOIN sys_menu m ON m.menu_id = e.menu_id
    WHERE m.menu_id IS NULL
);
SET @inventory_parent_conflict = (
    SELECT COUNT(*)
    FROM (
        SELECT 1761400000000020004 AS menu_id, '在库管理' AS menu_name, 0 AS parent_id,
               13 AS order_num, 'iws-inventory' AS path, NULL AS component,
               '' AS query_param, 'N' AS is_frame, 'Y' AS is_cache, 'M' AS menu_type,
               '0' AS visible, '0' AS status, '' AS perms, 'list' AS icon,
               '' AS active_menu, '' AS ext
    ) e
    JOIN sys_menu m ON m.menu_id = e.menu_id
    WHERE CAST(IFNULL(m.menu_name, '') AS BINARY) <> CAST(e.menu_name AS BINARY)
       OR IFNULL(m.parent_id, -1) <> e.parent_id
       OR IFNULL(m.order_num, -1) <> e.order_num
       OR CAST(IFNULL(m.path, '') AS BINARY) <> CAST(e.path AS BINARY)
       OR NOT (CAST(m.component AS BINARY) <=> CAST(e.component AS BINARY))
       OR CAST(IFNULL(m.query_param, '') AS BINARY) <> CAST(e.query_param AS BINARY)
       OR CAST(IFNULL(m.is_frame, '') AS BINARY) <> CAST(e.is_frame AS BINARY)
       OR CAST(IFNULL(m.is_cache, '') AS BINARY) <> CAST(e.is_cache AS BINARY)
       OR CAST(IFNULL(m.menu_type, '') AS BINARY) <> CAST(e.menu_type AS BINARY)
       OR CAST(IFNULL(m.visible, '') AS BINARY) <> CAST(e.visible AS BINARY)
       OR CAST(IFNULL(m.status, '') AS BINARY) <> CAST(e.status AS BINARY)
       OR CAST(IFNULL(m.perms, '') AS BINARY) <> CAST(e.perms AS BINARY)
       OR CAST(IFNULL(m.icon, '') AS BINARY) <> CAST(e.icon AS BINARY)
       OR CAST(IFNULL(m.active_menu, '') AS BINARY) <> CAST(e.active_menu AS BINARY)
       OR CAST(IFNULL(m.ext, '') AS BINARY) <> CAST(e.ext AS BINARY)
);
SET @inventory_root_dup_count = (
    SELECT COUNT(*)
    FROM sys_menu
    WHERE IFNULL(parent_id, -1) = 0
      AND (CAST(IFNULL(menu_name, '') AS BINARY) = CAST('在库管理' AS BINARY)
           OR CAST(IFNULL(path, '') AS BINARY) = CAST('iws-inventory' AS BINARY))
      AND menu_id <> 1761400000000020004
);
SET @menu_mismatch = (
    SELECT COUNT(*)
    FROM (
        SELECT 1761400000000050100 AS menu_id, '公司管理' AS menu_name, 1761400000000020004 AS parent_id,
               1 AS order_num, 'company' AS path, 'wms/inventory/company/index' AS component,
               'inventory:company:query' AS perms, 'C' AS menu_type, '0' AS visible
        UNION ALL SELECT 1761400000000051001, '公司新增', 1761400000000050100, 1,
                         '', NULL, 'inventory:company:create', 'F', '0'
        UNION ALL SELECT 1761400000000051002, '公司修改', 1761400000000050100, 2,
                         '', NULL, 'inventory:company:update', 'F', '0'
        UNION ALL SELECT 1761400000000051003, '公司启用', 1761400000000050100, 3,
                         '', NULL, 'inventory:company:enable', 'F', '0'
        UNION ALL SELECT 1761400000000051004, '公司停用', 1761400000000050100, 4,
                         '', NULL, 'inventory:company:disable', 'F', '0'
        UNION ALL SELECT 1761400000000051005, '跨公司范围', 1761400000000050100, 5,
                         '', NULL, 'inventory:company:scope:all', 'F', '0'
        UNION ALL SELECT 1761400000000051006, '管理范围授权', 1761400000000050100, 6,
                         '', NULL, 'inventory:company:scope:manage', 'F', '0'
    ) e
    LEFT JOIN sys_menu m ON m.menu_id = e.menu_id
    WHERE m.menu_id IS NULL
       OR CAST(IFNULL(m.menu_name, '') AS BINARY) <> CAST(e.menu_name AS BINARY)
       OR IFNULL(m.parent_id, -1) <> e.parent_id
       OR IFNULL(m.order_num, -1) <> e.order_num
       OR CAST(IFNULL(m.path, '') AS BINARY) <> CAST(e.path AS BINARY)
       OR NOT (CAST(m.component AS BINARY) <=> CAST(e.component AS BINARY))
       OR CAST(IFNULL(m.perms, '') AS BINARY) <> CAST(e.perms AS BINARY)
       OR CAST(IFNULL(m.menu_type, '') AS BINARY) <> CAST(e.menu_type AS BINARY)
       OR CAST(IFNULL(m.visible, '') AS BINARY) <> CAST(e.visible AS BINARY)
       OR CAST(IFNULL(m.status, '') AS BINARY) <> CAST('0' AS BINARY)
);
SET @perm_dup_count = (
    SELECT COUNT(*)
    FROM (
        SELECT perms
        FROM sys_menu
        WHERE perms IN (
            'inventory:company:query',
            'inventory:company:create',
            'inventory:company:update',
            'inventory:company:enable',
            'inventory:company:disable',
            'inventory:company:scope:all',
            'inventory:company:scope:manage'
        )
        GROUP BY perms
        HAVING COUNT(*) <> 1
    ) d
);
SET @menu_path_conflict = (
    SELECT COUNT(*)
    FROM (
        SELECT 1761400000000050100 AS menu_id, 1761400000000020004 AS parent_id, 'company' AS path
    ) e
    JOIN sys_menu m
        ON IFNULL(m.parent_id, -1) = e.parent_id
       AND CAST(IFNULL(m.path, '') AS BINARY) = CAST(e.path AS BINARY)
       AND m.menu_id <> e.menu_id
);
SET @menu_perm_conflict = (
    SELECT COUNT(*)
    FROM (
        SELECT 1761400000000050100 AS menu_id, 'inventory:company:query' AS perms
        UNION ALL SELECT 1761400000000051001, 'inventory:company:create'
        UNION ALL SELECT 1761400000000051002, 'inventory:company:update'
        UNION ALL SELECT 1761400000000051003, 'inventory:company:enable'
        UNION ALL SELECT 1761400000000051004, 'inventory:company:disable'
        UNION ALL SELECT 1761400000000051005, 'inventory:company:scope:all'
        UNION ALL SELECT 1761400000000051006, 'inventory:company:scope:manage'
    ) e
    JOIN sys_menu m
        ON CAST(IFNULL(m.perms, '') AS BINARY) = CAST(e.perms AS BINARY)
       AND m.menu_id <> e.menu_id
);
SET @menu_ok = (
    @inventory_parent_missing = 0
    AND @inventory_parent_conflict = 0
    AND @inventory_root_dup_count = 0
    AND @menu_mismatch = 0
    AND @perm_dup_count = 0
    AND @menu_path_conflict = 0
    AND @menu_perm_conflict = 0
);
SELECT '在库管理父节点' AS `校验项`,
       IF(@inventory_parent_missing = 0 AND @inventory_parent_conflict = 0 AND @inventory_root_dup_count = 0, '通过', '失败') AS `结果`,
       @inventory_parent_missing AS `父节点缺失数`,
       @inventory_parent_conflict AS `父节点冲突数`,
       @inventory_root_dup_count AS `重复在库管理根数`;
SELECT JSON_EXTRACT(IF(@inventory_parent_missing = 0 AND @inventory_parent_conflict = 0 AND @inventory_root_dup_count = 0, '{}', 'invalid'), '$') AS `在库管理父节点门禁`;
SELECT '公司菜单与权限' AS `校验项`,
       IF(@menu_ok = 1, '通过', '失败') AS `结果`,
       @menu_mismatch AS `菜单不一致数`,
       @perm_dup_count AS `权限重复或缺失数`,
       @menu_path_conflict AS `路径冲突数`,
       @inventory_root_dup_count AS `重复在库管理根数`,
       @menu_perm_conflict AS `权限归属冲突数`;
SELECT JSON_EXTRACT(IF(@menu_ok = 1, '{}', 'invalid'), '$') AS `菜单权限门禁`;

-- ----------------------------------------------------------------------------
-- 七、验收摘要
-- ----------------------------------------------------------------------------
SET @company_count = (SELECT COUNT(*) FROM wms_company);
SET @scope_count = (SELECT COUNT(*) FROM wms_company_management_scope);
SET @grant_count = (SELECT COUNT(*) FROM wms_company_management_grant);
SET @audit_count = (SELECT COUNT(*) FROM wms_object_audit);
SET @attempt_count = (SELECT COUNT(*) FROM wms_command_attempt);
SET @final_ok = (
    @base_ok = 1
    AND @tbl_mismatch = 0
    AND @col_mismatch = 0
    AND @col_count_mismatch = 0
    AND @on_update_mismatch = 0
    AND @col_extra_mismatch = 0
    AND @comment_mismatch = 0
    AND @idx_mismatch = 0
    AND @idx_count_mismatch = 0
    AND @idx_part_mismatch = 0
    AND @idx_extra_count = 0
    AND @data_ok = 1
    AND @menu_ok = 1
);
SELECT
    @target_schema AS `目标库`,
    @company_count AS `公司行数`,
    @scope_count AS `管理范围头行数`,
    @grant_count AS `管理授权行数`,
    @audit_count AS `对象审计行数`,
    @attempt_count AS `命令尝试行数`,
    IF(@final_ok = 1, '公司最小闭环结构验收通过', '公司最小闭环结构验收失败') AS `执行结果`;

SELECT JSON_EXTRACT(IF(@final_ok = 1, '{}', 'invalid'), '$') AS `最终验收门禁`;

SET SESSION group_concat_max_len = @saved_group_concat_max_len;
