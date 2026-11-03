-- ============================================================================
-- IWS+ 库存业务类型与计划挂载目录只读验收
-- ============================================================================
-- 目标数据库：MySQL 8.0（本切片按 8.0.45 隔离验证）
-- 目标库参数：@target_schema，集中定义于下方；验收时必须与连接 DATABASE() 一致
-- 性质：只读元数据与目录一致性核验；不创建、修改或删除任何对象和业务行。
-- 依据：docs/inventory-p1-contract-20261027.md §5.6、§9.3；INV-P1-02
-- 元数据可见性：
--   离线部署/验收账号必须直接持有全局 SELECT、TRIGGER，且 partial_revokes=OFF；
--   schema/table/角色授权不替代本契约。该门禁进入最终验收门禁。
-- 检查：
--   同名对象必须为 BASE TABLE；列、索引（唯一性、顺序、列/表达式、前缀、类型、
--   升降序、可见性、注释）、表选项、CHECK、本表外键、外部指入外键和触发器
--   必须与目录契约一致；Mapper 读取的 9 个源列必须存在且类型匹配。
-- 执行：
--   mysql --protocol=TCP --host=<主机> --port=<端口> --user=<账号> \
--     --default-character-set=utf8mb4 --database=innolux-iws --batch --raw \
--     --execute="source script/sql/iws_inventory_type_catalog_verify.sql"
-- ============================================================================

SET NAMES utf8mb4;
SET @target_schema = 'innolux-iws';
SET @catalog_contract_version = '1';
SET @expected_mysql_version_prefix = '8.0.';
SET @target_ok = IF(@target_schema IS NOT NULL AND LENGTH(TRIM(@target_schema)) > 0, 1, 0);
SET @schema_ok = (
    DATABASE() IS NOT NULL
    AND CAST(DATABASE() AS BINARY) = CAST(@target_schema AS BINARY)
);
SET @schema_exists = (
    SELECT COUNT(*) = 1 FROM information_schema.SCHEMATA
    WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@target_schema AS BINARY)
);
SET @version_ok = (
    VERSION() LIKE CONCAT(@expected_mysql_version_prefix, '%')
    AND VERSION() NOT LIKE '%MariaDB%'
);

-- 离线部署/验收账号契约（不是应用账号）：必须直接持有全局 SELECT、TRIGGER，
-- 且 partial_revokes=OFF，才能证明跨库入向外键和目标表触发器元数据完整可见。
SET @metadata_grantee = CONCAT(
    QUOTE(LEFT(CURRENT_USER(),
        CHAR_LENGTH(CURRENT_USER()) - CHAR_LENGTH(SUBSTRING_INDEX(CURRENT_USER(), '@', -1)) - 1)),
    '@', QUOTE(SUBSTRING_INDEX(CURRENT_USER(), '@', -1))
);
SET @metadata_visibility_ok = (
    @@GLOBAL.partial_revokes = 0
    AND (SELECT COUNT(DISTINCT PRIVILEGE_TYPE) = 2
         FROM information_schema.USER_PRIVILEGES
         WHERE CAST(GRANTEE AS BINARY) = CAST(@metadata_grantee AS BINARY)
           AND PRIVILEGE_TYPE IN ('SELECT', 'TRIGGER'))
);
SELECT IF(@metadata_visibility_ok = 1, '通过',
    '失败：离线部署验收账号须直接拥有全局 SELECT、TRIGGER，且 partial_revokes=OFF；schema/table/角色授权不受支持')
    AS `元数据可见权限门禁`;

SET @bt_object_type = (
    SELECT TABLE_TYPE FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @target_schema AND TABLE_NAME = 'wms_business_type'
    LIMIT 1
);
SET @mount_object_type = (
    SELECT TABLE_TYPE FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @target_schema AND TABLE_NAME = 'wms_business_plan_mount'
    LIMIT 1
);
SET @catalog_object_conflict_count = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @target_schema
      AND TABLE_NAME IN ('wms_business_type', 'wms_business_plan_mount')
      AND TABLE_TYPE <> 'BASE TABLE'
);

-- 源列必须覆盖 WmsInventoryCatalogMapper 实际读取的全部列（9 列）。
DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_verify_expected_source_columns;
CREATE TEMPORARY TABLE tmp_iws_catalog_verify_expected_source_columns (
    table_name  varchar(64)  NOT NULL,
    column_name varchar(64)  NOT NULL,
    column_type varchar(64)  NOT NULL,
    is_nullable varchar(3)   NOT NULL,
    PRIMARY KEY (table_name, column_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO tmp_iws_catalog_verify_expected_source_columns
    (table_name, column_name, column_type, is_nullable)
VALUES
    ('iws_business_schedule', 'id', 'bigint', 'NO'),
    ('iws_business_schedule', 'business_code', 'varchar(64)', 'NO'),
    ('iws_business_schedule', 'enabled', 'tinyint(1)', 'NO'),
    ('iws_business_schedule', 'del_flag', 'char(1)', 'NO'),
    ('iws_business_schedule_plan_type', 'id', 'bigint', 'NO'),
    ('iws_business_schedule_plan_type', 'schedule_id', 'bigint', 'NO'),
    ('iws_business_schedule_plan_type', 'plan_type', 'varchar(64)', 'NO'),
    ('iws_business_schedule_plan_type', 'plan_type_name', 'varchar(128)', 'YES'),
    ('iws_business_schedule_plan_type', 'enabled', 'tinyint(1)', 'NO');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_verify_expected_columns;
CREATE TEMPORARY TABLE tmp_iws_catalog_verify_expected_columns (
    table_name      varchar(64)  NOT NULL,
    ordinal_position int         NOT NULL,
    column_name     varchar(64)  NOT NULL,
    column_type     varchar(128) NOT NULL,
    is_nullable     varchar(3)   NOT NULL,
    column_default  varchar(256) NULL,
    extra           varchar(128) NOT NULL,
    character_set   varchar(32)  NULL,
    collation_name  varchar(64)  NULL,
    column_comment  varchar(1024) NOT NULL,
    PRIMARY KEY (table_name, ordinal_position)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO tmp_iws_catalog_verify_expected_columns
    (table_name, ordinal_position, column_name, column_type, is_nullable, column_default,
     extra, character_set, collation_name, column_comment)
VALUES
    ('wms_business_type', 1, 'business_type_code', 'varchar(64)', 'NO', NULL, '',
     'utf8mb4', 'utf8mb4_0900_bin', '业务类型编码'),
    ('wms_business_type', 2, 'business_type_name', 'varchar(128)', 'NO', NULL, '',
     'utf8mb4', 'utf8mb4_0900_ai_ci', '业务类型名称'),
    ('wms_business_type', 3, 'status', 'varchar(16)', 'NO', 'ACTIVE', '',
     'utf8mb4', 'utf8mb4_0900_bin', '状态：ACTIVE/DISABLED'),
    ('wms_business_type', 4, 'contract_version', 'varchar(32)', 'NO', NULL, '',
     'utf8mb4', 'utf8mb4_0900_bin', '业务合同版本'),
    ('wms_business_type', 5, 'object_policy_key', 'varchar(64)', 'NO', NULL, '',
     'utf8mb4', 'utf8mb4_0900_bin', '对象策略键；本切面不注册策略'),
    ('wms_business_type', 6, 'revision', 'int', 'NO', '1', '',
     NULL, NULL, '修订号'),
    ('wms_business_type', 7, 'create_dept', 'bigint', 'YES', NULL, '',
     NULL, NULL, '创建部门'),
    ('wms_business_type', 8, 'create_by', 'bigint', 'YES', NULL, '',
     NULL, NULL, '创建者'),
    ('wms_business_type', 9, 'create_time', 'datetime(3)', 'NO', 'CURRENT_TIMESTAMP(3)',
     'DEFAULT_GENERATED', NULL, NULL, '创建时间'),
    ('wms_business_type', 10, 'update_by', 'bigint', 'YES', NULL, '',
     NULL, NULL, '更新者'),
    ('wms_business_type', 11, 'update_time', 'datetime(3)', 'YES', NULL,
     'on update CURRENT_TIMESTAMP(3)', NULL, NULL, '更新时间'),
    ('wms_business_plan_mount', 1, 'mount_id', 'bigint', 'NO', NULL, '',
     NULL, NULL, '挂载主键，系统生成'),
    ('wms_business_plan_mount', 2, 'business_type_code', 'varchar(64)', 'NO', NULL, '',
     'utf8mb4', 'utf8mb4_0900_bin', '业务类型编码'),
    ('wms_business_plan_mount', 3, 'plan_type_code', 'varchar(64)', 'NO', NULL, '',
     'utf8mb4', 'utf8mb4_0900_bin', '计划类型编码'),
    ('wms_business_plan_mount', 4, 'schedule_code', 'varchar(64)', 'NO', NULL, '',
     'utf8mb4', 'utf8mb4_0900_bin', '调度业务编码，等于iws_business_schedule.business_code'),
    ('wms_business_plan_mount', 5, 'contract_version', 'varchar(32)', 'NO', NULL, '',
     'utf8mb4', 'utf8mb4_0900_bin', '挂载合同版本'),
    ('wms_business_plan_mount', 6, 'enabled', 'tinyint', 'NO', '1', '',
     NULL, NULL, '是否启用'),
    ('wms_business_plan_mount', 7, 'revision', 'int', 'NO', '1', '',
     NULL, NULL, '修订号'),
    ('wms_business_plan_mount', 8, 'create_dept', 'bigint', 'YES', NULL, '',
     NULL, NULL, '创建部门'),
    ('wms_business_plan_mount', 9, 'create_by', 'bigint', 'YES', NULL, '',
     NULL, NULL, '创建者'),
    ('wms_business_plan_mount', 10, 'create_time', 'datetime(3)', 'NO', 'CURRENT_TIMESTAMP(3)',
     'DEFAULT_GENERATED', NULL, NULL, '创建时间'),
    ('wms_business_plan_mount', 11, 'update_by', 'bigint', 'YES', NULL, '',
     NULL, NULL, '更新者'),
    ('wms_business_plan_mount', 12, 'update_time', 'datetime(3)', 'YES', NULL,
     'on update CURRENT_TIMESTAMP(3)', NULL, NULL, '更新时间');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_verify_expected_indexes;
CREATE TEMPORARY TABLE tmp_iws_catalog_verify_expected_indexes (
    table_name       varchar(64)   NOT NULL,
    index_name       varchar(64)   NOT NULL,
    non_unique       int           NOT NULL,
    seq_in_index     int           NOT NULL,
    column_name      varchar(64)   NULL,
    sub_part         bigint        NULL,
    index_type       varchar(16)   NOT NULL,
    index_collation  varchar(1)    NULL,
    index_expression varchar(2048) NULL,
    is_visible       varchar(3)    NOT NULL,
    index_comment    varchar(1024) NOT NULL,
    PRIMARY KEY (table_name, index_name, seq_in_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO tmp_iws_catalog_verify_expected_indexes
    (table_name, index_name, non_unique, seq_in_index, column_name, sub_part, index_type,
     index_collation, index_expression, is_visible, index_comment)
VALUES
    ('wms_business_type', 'PRIMARY', 0, 1, 'business_type_code', NULL, 'BTREE', 'A', NULL, 'YES', ''),
    ('wms_business_type', 'idx_wms_business_type_status', 1, 1, 'status', NULL, 'BTREE', 'A', NULL, 'YES', ''),
    ('wms_business_type', 'idx_wms_business_type_status', 1, 2, 'business_type_code', NULL, 'BTREE', 'A', NULL, 'YES', ''),
    ('wms_business_plan_mount', 'PRIMARY', 0, 1, 'mount_id', NULL, 'BTREE', 'A', NULL, 'YES', ''),
    ('wms_business_plan_mount', 'uk_wms_business_plan_mount', 0, 1, 'business_type_code', NULL, 'BTREE', 'A', NULL, 'YES', ''),
    ('wms_business_plan_mount', 'uk_wms_business_plan_mount', 0, 2, 'plan_type_code', NULL, 'BTREE', 'A', NULL, 'YES', ''),
    ('wms_business_plan_mount', 'uk_wms_business_plan_mount', 0, 3, 'schedule_code', NULL, 'BTREE', 'A', NULL, 'YES', ''),
    ('wms_business_plan_mount', 'idx_wms_business_plan_mount_resolve', 1, 1, 'schedule_code', NULL, 'BTREE', 'A', NULL, 'YES', ''),
    ('wms_business_plan_mount', 'idx_wms_business_plan_mount_resolve', 1, 2, 'plan_type_code', NULL, 'BTREE', 'A', NULL, 'YES', ''),
    ('wms_business_plan_mount', 'idx_wms_business_plan_mount_resolve', 1, 3, 'enabled', NULL, 'BTREE', 'A', NULL, 'YES', '');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_verify_actual_columns;
CREATE TEMPORARY TABLE tmp_iws_catalog_verify_actual_columns AS
SELECT TABLE_NAME AS table_name, ORDINAL_POSITION AS ordinal_position,
       COLUMN_NAME AS column_name, COLUMN_TYPE AS column_type,
       IS_NULLABLE AS is_nullable, COLUMN_DEFAULT AS column_default,
       EXTRA AS extra, CHARACTER_SET_NAME AS character_set,
       COLLATION_NAME AS collation_name, COLUMN_COMMENT AS column_comment
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @target_schema
  AND TABLE_NAME IN ('wms_business_type', 'wms_business_plan_mount');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_verify_actual_indexes;
CREATE TEMPORARY TABLE tmp_iws_catalog_verify_actual_indexes AS
SELECT TABLE_NAME AS table_name, INDEX_NAME AS index_name, NON_UNIQUE AS non_unique,
       SEQ_IN_INDEX AS seq_in_index, COLUMN_NAME AS column_name,
       SUB_PART AS sub_part, INDEX_TYPE AS index_type,
       COLLATION AS index_collation, EXPRESSION AS index_expression,
       IS_VISIBLE AS is_visible, INDEX_COMMENT AS index_comment
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = @target_schema
  AND TABLE_NAME IN ('wms_business_type', 'wms_business_plan_mount');

SET @bt_column_count_expected = (
    SELECT COUNT(*) FROM tmp_iws_catalog_verify_expected_columns
    WHERE table_name = 'wms_business_type'
);
SET @bt_column_count_actual = (
    SELECT COUNT(*) FROM tmp_iws_catalog_verify_actual_columns
    WHERE table_name = 'wms_business_type'
);
-- 完整主键行集合逐字段匹配，不拼接签名；行数与匹配数同时相等才通过。
SET @bt_columns_matched = (
    SELECT COUNT(*) FROM tmp_iws_catalog_verify_expected_columns e
    JOIN tmp_iws_catalog_verify_actual_columns a
      ON a.table_name = e.table_name
     AND a.ordinal_position = e.ordinal_position
     AND a.column_name = e.column_name
    WHERE e.table_name = 'wms_business_type'
      AND LOWER(e.column_type) = LOWER(a.column_type)
      AND e.is_nullable = a.is_nullable
      AND (CAST(e.column_default AS BINARY) <=> CAST(a.column_default AS BINARY))
      AND LOWER(e.extra) = LOWER(a.extra)
      AND (CAST(e.character_set AS BINARY) <=> CAST(a.character_set AS BINARY))
      AND (CAST(e.collation_name AS BINARY) <=> CAST(a.collation_name AS BINARY))
      AND (CAST(e.column_comment AS BINARY) <=> CAST(a.column_comment AS BINARY))
);
SET @bt_columns_ok = IF(
    @bt_column_count_expected = @bt_column_count_actual
    AND @bt_columns_matched = @bt_column_count_actual, 1, 0
);

SET @mount_column_count_expected = (
    SELECT COUNT(*) FROM tmp_iws_catalog_verify_expected_columns
    WHERE table_name = 'wms_business_plan_mount'
);
SET @mount_column_count_actual = (
    SELECT COUNT(*) FROM tmp_iws_catalog_verify_actual_columns
    WHERE table_name = 'wms_business_plan_mount'
);
SET @mount_columns_matched = (
    SELECT COUNT(*) FROM tmp_iws_catalog_verify_expected_columns e
    JOIN tmp_iws_catalog_verify_actual_columns a
      ON a.table_name = e.table_name
     AND a.ordinal_position = e.ordinal_position
     AND a.column_name = e.column_name
    WHERE e.table_name = 'wms_business_plan_mount'
      AND LOWER(e.column_type) = LOWER(a.column_type)
      AND e.is_nullable = a.is_nullable
      AND (CAST(e.column_default AS BINARY) <=> CAST(a.column_default AS BINARY))
      AND LOWER(e.extra) = LOWER(a.extra)
      AND (CAST(e.character_set AS BINARY) <=> CAST(a.character_set AS BINARY))
      AND (CAST(e.collation_name AS BINARY) <=> CAST(a.collation_name AS BINARY))
      AND (CAST(e.column_comment AS BINARY) <=> CAST(a.column_comment AS BINARY))
);
SET @mount_columns_ok = IF(
    @mount_column_count_expected = @mount_column_count_actual
    AND @mount_columns_matched = @mount_column_count_actual, 1, 0
);

SET @bt_index_count_expected = (
    SELECT COUNT(*) FROM tmp_iws_catalog_verify_expected_indexes
    WHERE table_name = 'wms_business_type'
);
SET @bt_index_count_actual = (
    SELECT COUNT(*) FROM tmp_iws_catalog_verify_actual_indexes
    WHERE table_name = 'wms_business_type'
);
SET @bt_indexes_matched = (
    SELECT COUNT(*) FROM tmp_iws_catalog_verify_expected_indexes e
    JOIN tmp_iws_catalog_verify_actual_indexes a
      ON a.table_name = e.table_name
     AND a.index_name = e.index_name
     AND a.seq_in_index = e.seq_in_index
    WHERE e.table_name = 'wms_business_type'
      AND a.non_unique = e.non_unique
      AND (CAST(a.column_name AS BINARY) <=> CAST(e.column_name AS BINARY))
      AND a.sub_part <=> e.sub_part
      AND a.index_type = e.index_type
      AND (CAST(a.index_collation AS BINARY) <=> CAST(e.index_collation AS BINARY))
      AND (CAST(a.index_expression AS BINARY) <=> CAST(e.index_expression AS BINARY))
      AND a.is_visible = e.is_visible
      AND (CAST(a.index_comment AS BINARY) <=> CAST(e.index_comment AS BINARY))
);
SET @bt_indexes_ok = IF(
    @bt_index_count_expected = @bt_index_count_actual
    AND @bt_indexes_matched = @bt_index_count_actual, 1, 0
);

SET @mount_index_count_expected = (
    SELECT COUNT(*) FROM tmp_iws_catalog_verify_expected_indexes
    WHERE table_name = 'wms_business_plan_mount'
);
SET @mount_index_count_actual = (
    SELECT COUNT(*) FROM tmp_iws_catalog_verify_actual_indexes
    WHERE table_name = 'wms_business_plan_mount'
);
SET @mount_indexes_matched = (
    SELECT COUNT(*) FROM tmp_iws_catalog_verify_expected_indexes e
    JOIN tmp_iws_catalog_verify_actual_indexes a
      ON a.table_name = e.table_name
     AND a.index_name = e.index_name
     AND a.seq_in_index = e.seq_in_index
    WHERE e.table_name = 'wms_business_plan_mount'
      AND a.non_unique = e.non_unique
      AND (CAST(a.column_name AS BINARY) <=> CAST(e.column_name AS BINARY))
      AND a.sub_part <=> e.sub_part
      AND a.index_type = e.index_type
      AND (CAST(a.index_collation AS BINARY) <=> CAST(e.index_collation AS BINARY))
      AND (CAST(a.index_expression AS BINARY) <=> CAST(e.index_expression AS BINARY))
      AND a.is_visible = e.is_visible
      AND (CAST(a.index_comment AS BINARY) <=> CAST(e.index_comment AS BINARY))
);
SET @mount_indexes_ok = IF(
    @mount_index_count_expected = @mount_index_count_actual
    AND @mount_indexes_matched = @mount_index_count_actual, 1, 0
);

SET @bt_table_ok = (
    SELECT COUNT(*) = 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @target_schema
      AND TABLE_NAME = 'wms_business_type'
      AND TABLE_TYPE = 'BASE TABLE'
      AND ENGINE = 'InnoDB'
      AND TABLE_COLLATION = 'utf8mb4_0900_ai_ci'
      AND TABLE_COMMENT = 'WMS业务类型目录'
);
SET @mount_table_ok = (
    SELECT COUNT(*) = 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @target_schema
      AND TABLE_NAME = 'wms_business_plan_mount'
      AND TABLE_TYPE = 'BASE TABLE'
      AND ENGINE = 'InnoDB'
      AND TABLE_COLLATION = 'utf8mb4_0900_ai_ci'
      AND TABLE_COMMENT = 'WMS业务类型计划挂载目录'
);

SET @schedule_exists = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @target_schema
      AND TABLE_NAME = 'iws_business_schedule'
      AND TABLE_TYPE = 'BASE TABLE'
);
SET @plan_type_exists = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @target_schema
      AND TABLE_NAME = 'iws_business_schedule_plan_type'
      AND TABLE_TYPE = 'BASE TABLE'
);
SET @schedule_source_column_count = (
    SELECT COUNT(*) FROM tmp_iws_catalog_verify_expected_source_columns
);
SET @schedule_source_columns = (
    SELECT COUNT(*)
    FROM tmp_iws_catalog_verify_expected_source_columns e
    JOIN information_schema.COLUMNS a
      ON a.TABLE_SCHEMA = @target_schema
     AND a.TABLE_NAME = e.table_name
     AND a.COLUMN_NAME = e.column_name
    WHERE LOWER(a.COLUMN_TYPE) = LOWER(e.column_type)
      AND a.IS_NULLABLE = e.is_nullable
);

SET @catalog_check_count = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = @target_schema
      AND TABLE_NAME IN ('wms_business_type', 'wms_business_plan_mount')
      AND CONSTRAINT_TYPE = 'CHECK'
);
SET @catalog_own_fk_count = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = @target_schema
      AND TABLE_NAME IN ('wms_business_type', 'wms_business_plan_mount')
      AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @catalog_trigger_count = (
    SELECT COUNT(*) FROM information_schema.TRIGGERS
    WHERE EVENT_OBJECT_SCHEMA = @target_schema
      AND EVENT_OBJECT_TABLE IN ('wms_business_type', 'wms_business_plan_mount')
);
SET @catalog_inbound_fk_count = (
    SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
    WHERE UNIQUE_CONSTRAINT_SCHEMA = @target_schema
      AND REFERENCED_TABLE_NAME IN ('wms_business_type', 'wms_business_plan_mount')
);

SET @business_type_rows = 0;
SET @active_type_rows = 0;
SET @mount_rows = 0;
SET @active_mount_rows = 0;
SET @orphan_active_mount_rows = 0;
SET @active_unresolved_rows = 0;
SET @catalog_data_ready = IF(
    @bt_table_ok = 1 AND @mount_table_ok = 1
    AND @bt_columns_ok = 1 AND @mount_columns_ok = 1
    AND @schedule_source_columns = @schedule_source_column_count
    AND @schedule_exists = 1 AND @plan_type_exists = 1,
    1, 0
);

SET @data_sql = IF(@catalog_data_ready = 1,
    'SELECT COUNT(*) INTO @business_type_rows FROM wms_business_type',
    'SET @business_type_rows = 0');
PREPARE verify_stmt FROM @data_sql; EXECUTE verify_stmt; DEALLOCATE PREPARE verify_stmt;

SET @data_sql = IF(@catalog_data_ready = 1,
    'SELECT COUNT(*) INTO @mount_rows FROM wms_business_plan_mount',
    'SET @mount_rows = 0');
PREPARE verify_stmt FROM @data_sql; EXECUTE verify_stmt; DEALLOCATE PREPARE verify_stmt;

SET @data_sql = IF(@catalog_data_ready = 1,
    'SELECT COUNT(*) INTO @active_type_rows FROM wms_business_type WHERE status = ''ACTIVE''',
    'SET @active_type_rows = 0');
PREPARE verify_stmt FROM @data_sql; EXECUTE verify_stmt; DEALLOCATE PREPARE verify_stmt;

SET @data_sql = IF(@catalog_data_ready = 1,
    'SELECT COUNT(*) INTO @active_mount_rows FROM wms_business_plan_mount WHERE enabled = 1',
    'SET @active_mount_rows = 0');
PREPARE verify_stmt FROM @data_sql; EXECUTE verify_stmt; DEALLOCATE PREPARE verify_stmt;

SET @data_sql = IF(@catalog_data_ready = 1,
    CONCAT('SELECT COUNT(*) INTO @orphan_active_mount_rows ',
           'FROM wms_business_plan_mount m ',
           'LEFT JOIN wms_business_type bt ',
           'ON CAST(bt.business_type_code AS BINARY) = CAST(m.business_type_code AS BINARY) ',
           'WHERE m.enabled = 1 AND bt.business_type_code IS NULL'),
    'SET @orphan_active_mount_rows = 0');
PREPARE verify_stmt FROM @data_sql; EXECUTE verify_stmt; DEALLOCATE PREPARE verify_stmt;

SET @data_sql = IF(@catalog_data_ready = 1,
    CONCAT('SELECT COUNT(*) INTO @active_unresolved_rows ',
           'FROM wms_business_plan_mount m ',
           'JOIN wms_business_type bt ',
           'ON CAST(bt.business_type_code AS BINARY) = CAST(m.business_type_code AS BINARY) ',
           'LEFT JOIN iws_business_schedule s ',
           'ON CAST(s.business_code AS BINARY) = CAST(m.schedule_code AS BINARY) ',
           'LEFT JOIN iws_business_schedule_plan_type pt ',
           'ON pt.schedule_id = s.id ',
           'AND CAST(pt.plan_type AS BINARY) = CAST(m.plan_type_code AS BINARY) ',
           'WHERE bt.status = ''ACTIVE'' AND m.enabled = 1 AND (',
           'CAST(bt.contract_version AS BINARY) <> CAST(@catalog_contract_version AS BINARY) ',
           'OR CAST(m.contract_version AS BINARY) <> CAST(@catalog_contract_version AS BINARY) ',
           'OR CAST(m.contract_version AS BINARY) <> CAST(bt.contract_version AS BINARY) ',
           'OR s.id IS NULL OR s.enabled <> 1 OR s.del_flag <> ''0'' ',
           'OR pt.id IS NULL OR pt.enabled <> 1)'),
    'SET @active_unresolved_rows = 0');
PREPARE verify_stmt FROM @data_sql; EXECUTE verify_stmt; DEALLOCATE PREPARE verify_stmt;

SET @verify_ok = IF(
    @target_ok = 1
    AND @schema_ok = 1 AND @schema_exists = 1 AND @version_ok = 1
    AND @metadata_visibility_ok = 1
    AND @catalog_object_conflict_count = 0
    AND @bt_table_ok = 1 AND @mount_table_ok = 1
    AND @bt_columns_ok = 1 AND @mount_columns_ok = 1
    AND @bt_indexes_ok = 1 AND @mount_indexes_ok = 1
    AND @schedule_exists = 1 AND @plan_type_exists = 1
    AND @schedule_source_columns = @schedule_source_column_count
    AND @catalog_check_count = 0
    AND @catalog_own_fk_count = 0
    AND @catalog_trigger_count = 0
    AND @catalog_inbound_fk_count = 0
    AND @orphan_active_mount_rows = 0
    AND @active_unresolved_rows = 0,
    1, 0
);

SELECT
    @target_schema AS `目标库`,
    @metadata_visibility_ok AS `元数据权限通过`,
    COALESCE(@bt_object_type, '<缺失>') AS `业务类型对象类型`,
    COALESCE(@mount_object_type, '<缺失>') AS `挂载对象类型`,
    @catalog_object_conflict_count AS `同名非表对象数`,
    @bt_table_ok AS `业务类型表结构通过`,
    @mount_table_ok AS `挂载表结构通过`,
    @bt_columns_ok AS `业务类型列集合通过`,
    @mount_columns_ok AS `挂载列集合通过`,
    @bt_indexes_ok AS `业务类型索引集合通过`,
    @mount_indexes_ok AS `挂载索引集合通过`,
    @bt_column_count_expected + @bt_column_count_actual
        - 2 * @bt_columns_matched AS `业务类型列集合冲突数`,
    @mount_column_count_expected + @mount_column_count_actual
        - 2 * @mount_columns_matched AS `挂载列集合冲突数`,
    @bt_index_count_expected + @bt_index_count_actual
        - 2 * @bt_indexes_matched AS `业务类型索引集合冲突数`,
    @mount_index_count_expected + @mount_index_count_actual
        - 2 * @mount_indexes_matched AS `挂载索引集合冲突数`,
    CONCAT(@schedule_source_columns, '/', @schedule_source_column_count) AS `调度源列匹配`,
    @catalog_check_count AS `CHECK数`,
    @catalog_own_fk_count AS `本表外键数`,
    @catalog_trigger_count AS `触发器数`,
    @catalog_inbound_fk_count AS `外部指入外键数`,
    @orphan_active_mount_rows AS `启用挂载孤儿业务类型数`,
    @active_unresolved_rows AS `启用挂载未解析数`,
    IF(@verify_ok = 1, '目录结构验收通过', '目录结构验收失败') AS `验收结果`;

SELECT
    @business_type_rows AS `业务类型行数`,
    @active_type_rows AS `启用业务类型行数`,
    @mount_rows AS `计划挂载行数`,
    @active_mount_rows AS `启用计划挂载行数`,
    IF(@active_unresolved_rows = 0,
       '启用挂载均可解析；停用历史不参与新准入',
       '存在启用但无法解析到调度/计划类型/合同版本的挂载') AS `目录数据边界`;

SELECT JSON_EXTRACT(IF(@verify_ok = 1, '{}', 'invalid'), '$') AS `目录验收完成门禁`;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_verify_actual_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_verify_actual_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_verify_expected_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_verify_expected_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_verify_expected_source_columns;
