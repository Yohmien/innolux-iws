-- ============================================================================
-- IWS+ 库存业务类型与计划挂载目录
-- ============================================================================
-- 目标数据库：MySQL 8.0（本切片按 8.0.45 隔离验证）
-- 目标库参数：@target_schema，集中定义于下方；发布时必须与连接 DATABASE() 一致
-- 目标对象：
--   wms_business_type
--   wms_business_plan_mount
-- 依据：docs/inventory-p1-contract-20261027.md §5.6、§9.3、§10；INV-P1-02
-- 执行性质：
--   仅新增两张目录表；不新增业务类型或挂载种子，不创建 MOVE/FREEZE 业务，
--   不修改既有调度、计划事件、公司、空间或审计表，不调用 attach。
-- 复用：
--   plan_type_code 和 schedule_code 由 wms_business_plan_mount 只读解析到
--   iws_business_schedule_plan_type 与 iws_business_schedule；
--   Mapper 读取的 9 个源列必须存在且类型匹配，包括 plan_type_name。
-- 元数据可见性：
--   离线部署/验收账号必须直接持有全局 SELECT、TRIGGER，且
--   partial_revokes=OFF；schema/table/角色授权不替代本契约。该门禁同时约束
--   前置检查、第二张表的依赖写入和最终结构验收。
-- 幂等：
--   首次执行创建缺失表；完成态重跑只跳过；已有表按完整列、索引（唯一性、
--   顺序、列/表达式、前缀、类型、升降序、可见性、注释）、表选项、CHECK、
--   本表外键、外部指入外键和触发器逐项核对。任何冲突均在 DDL 前失败，
--   不删除、不重建、不覆盖。同名 VIEW 或其他非 BASE TABLE 对象同样在首个
--   DDL 前拒绝。
-- 执行：
--   mysql --protocol=TCP --host=<主机> --port=<端口> --user=<账号> \
--     --default-character-set=utf8mb4 --database=innolux-iws --batch --raw \
--     --execute="source script/sql/iws_inventory_type_catalog.sql"
--   不得添加 --force；首个 SQL 错误必须停止并保留现场。
-- 参数：
--   目标库、发布标识、目录合同版本和 MySQL 分支集中在下方；执行前只修改
--   @target_schema，其他参数必须与批准的契约清单同版。
-- ============================================================================

SET NAMES utf8mb4;

SET @target_schema = 'innolux-iws';
SET @catalog_release_id = 'INV-P1-02-CAT-20261028-R2';
SET @catalog_contract_version = '1';
SET @expected_mysql_version_prefix = '8.0.';
SET @target_schema_q = CONCAT('`', REPLACE(@target_schema, '`', '``'), '`');
SET @target_ok = IF(@target_schema IS NOT NULL AND LENGTH(TRIM(@target_schema)) > 0, 1, 0);
SET @schema_ok = (
    DATABASE() IS NOT NULL
    AND CAST(DATABASE() AS BINARY) = CAST(@target_schema AS BINARY)
);
SET @schema_exists = (
    SELECT COUNT(*) = 1
    FROM information_schema.SCHEMATA
    WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@target_schema AS BINARY)
);
SET @version_ok = (
    VERSION() LIKE CONCAT(@expected_mysql_version_prefix, '%')
    AND VERSION() NOT LIKE '%MariaDB%'
);

-- 离线部署/验收账号契约（不是应用账号）：必须直接持有全局 SELECT、TRIGGER，
-- 且 partial_revokes=OFF，才能证明跨库入向外键和目标表触发器元数据完整可见。
-- schema/table grants、角色（包括已激活角色）不替代本契约；脚本不激活角色、
-- 不读取 mysql 授权表，不输出凭据，不向其他 schema 写入。
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

-- 同名对象：BASE TABLE 才可复用；VIEW/系统视图等非表对象必须在首个 DDL 前拒绝。
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
SET @bt_exists = IF(@bt_object_type = 'BASE TABLE', 1, 0);
SET @mount_exists = IF(@mount_object_type = 'BASE TABLE', 1, 0);
SET @catalog_object_conflict_count = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @target_schema
      AND TABLE_NAME IN ('wms_business_type', 'wms_business_plan_mount')
      AND TABLE_TYPE <> 'BASE TABLE'
);

-- 源列必须覆盖 WmsInventoryCatalogMapper 实际读取的全部列（9 列）。
-- iws_business_schedule: id, business_code, enabled, del_flag
-- iws_business_schedule_plan_type: id, schedule_id, plan_type, plan_type_name, enabled
DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_expected_source_columns;
CREATE TEMPORARY TABLE tmp_iws_catalog_expected_source_columns (
    table_name  varchar(64)  NOT NULL,
    column_name varchar(64)  NOT NULL,
    column_type varchar(64)  NOT NULL,
    is_nullable varchar(3)   NOT NULL,
    PRIMARY KEY (table_name, column_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO tmp_iws_catalog_expected_source_columns
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
SET @schedule_source_columns = (
    SELECT COUNT(*)
    FROM tmp_iws_catalog_expected_source_columns e
    JOIN information_schema.COLUMNS a
      ON a.TABLE_SCHEMA = @target_schema
     AND a.TABLE_NAME = e.table_name
     AND a.COLUMN_NAME = e.column_name
    WHERE LOWER(a.COLUMN_TYPE) = LOWER(e.column_type)
      AND a.IS_NULLABLE = e.is_nullable
);
SET @schedule_source_column_count = (
    SELECT COUNT(*) FROM tmp_iws_catalog_expected_source_columns
);

DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_expected_columns;
CREATE TEMPORARY TABLE tmp_iws_catalog_expected_columns (
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

INSERT INTO tmp_iws_catalog_expected_columns
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

DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_expected_indexes;
CREATE TEMPORARY TABLE tmp_iws_catalog_expected_indexes (
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

INSERT INTO tmp_iws_catalog_expected_indexes
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

DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_actual_columns;
CREATE TEMPORARY TABLE tmp_iws_catalog_actual_columns AS
SELECT TABLE_NAME AS table_name, ORDINAL_POSITION AS ordinal_position,
       COLUMN_NAME AS column_name, COLUMN_TYPE AS column_type,
       IS_NULLABLE AS is_nullable, COLUMN_DEFAULT AS column_default,
       EXTRA AS extra, CHARACTER_SET_NAME AS character_set,
       COLLATION_NAME AS collation_name, COLUMN_COMMENT AS column_comment
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @target_schema
  AND TABLE_NAME IN ('wms_business_type', 'wms_business_plan_mount');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_actual_indexes;
CREATE TEMPORARY TABLE tmp_iws_catalog_actual_indexes AS
SELECT TABLE_NAME AS table_name, INDEX_NAME AS index_name, NON_UNIQUE AS non_unique,
       SEQ_IN_INDEX AS seq_in_index, COLUMN_NAME AS column_name,
       SUB_PART AS sub_part, INDEX_TYPE AS index_type,
       COLLATION AS index_collation, EXPRESSION AS index_expression,
       IS_VISIBLE AS is_visible, INDEX_COMMENT AS index_comment
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = @target_schema
  AND TABLE_NAME IN ('wms_business_type', 'wms_business_plan_mount');

SET @bt_expected_columns = (
    SELECT COUNT(*) FROM tmp_iws_catalog_expected_columns
    WHERE table_name = 'wms_business_type'
);
SET @bt_actual_columns = (
    SELECT COUNT(*) FROM tmp_iws_catalog_actual_columns
    WHERE table_name = 'wms_business_type'
);
-- 默认值按字节且 NULL 安全比较，保留 ACTIVE 等文字大小写；
-- 表达式使用目标 MySQL 元数据返回的规范形式 CURRENT_TIMESTAMP(3)。
SET @bt_matched_columns = (
    SELECT COUNT(*) FROM tmp_iws_catalog_expected_columns e
    JOIN tmp_iws_catalog_actual_columns a
      ON a.table_name = e.table_name
     AND a.ordinal_position = e.ordinal_position
     AND a.column_name = e.column_name
    WHERE e.table_name = 'wms_business_type'
      AND LOWER(e.column_type) = LOWER(a.column_type)
      AND e.is_nullable = a.is_nullable
      AND (CAST(e.column_default AS BINARY) <=> CAST(a.column_default AS BINARY))
      AND LOWER(e.extra) = LOWER(a.extra)
      AND COALESCE(e.character_set, '<NULL>') = COALESCE(a.character_set, '<NULL>')
      AND COALESCE(e.collation_name, '<NULL>') = COALESCE(a.collation_name, '<NULL>')
      AND e.column_comment = a.column_comment
);
SET @bt_column_mismatch = IF(
    @bt_exists = 1, @bt_expected_columns + @bt_actual_columns - 2 * @bt_matched_columns, 0
);

SET @mount_expected_columns = (
    SELECT COUNT(*) FROM tmp_iws_catalog_expected_columns
    WHERE table_name = 'wms_business_plan_mount'
);
SET @mount_actual_columns = (
    SELECT COUNT(*) FROM tmp_iws_catalog_actual_columns
    WHERE table_name = 'wms_business_plan_mount'
);
SET @mount_matched_columns = (
    SELECT COUNT(*) FROM tmp_iws_catalog_expected_columns e
    JOIN tmp_iws_catalog_actual_columns a
      ON a.table_name = e.table_name
     AND a.ordinal_position = e.ordinal_position
     AND a.column_name = e.column_name
    WHERE e.table_name = 'wms_business_plan_mount'
      AND LOWER(e.column_type) = LOWER(a.column_type)
      AND e.is_nullable = a.is_nullable
      AND (CAST(e.column_default AS BINARY) <=> CAST(a.column_default AS BINARY))
      AND LOWER(e.extra) = LOWER(a.extra)
      AND COALESCE(e.character_set, '<NULL>') = COALESCE(a.character_set, '<NULL>')
      AND COALESCE(e.collation_name, '<NULL>') = COALESCE(a.collation_name, '<NULL>')
      AND e.column_comment = a.column_comment
);
SET @mount_column_mismatch = IF(
    @mount_exists = 1, @mount_expected_columns + @mount_actual_columns
        - 2 * @mount_matched_columns, 0
);

SET @bt_expected_indexes = (
    SELECT COUNT(*) FROM tmp_iws_catalog_expected_indexes
    WHERE table_name = 'wms_business_type'
);
SET @bt_actual_indexes = (
    SELECT COUNT(*) FROM tmp_iws_catalog_actual_indexes
    WHERE table_name = 'wms_business_type'
);
SET @bt_matched_indexes = (
    SELECT COUNT(*) FROM tmp_iws_catalog_expected_indexes e
    JOIN tmp_iws_catalog_actual_indexes a
      ON a.table_name = e.table_name
     AND a.index_name = e.index_name
     AND a.seq_in_index = e.seq_in_index
    WHERE e.table_name = 'wms_business_type'
      AND a.non_unique = e.non_unique
      AND a.column_name = e.column_name
      AND COALESCE(a.sub_part, -1) = COALESCE(e.sub_part, -1)
      AND a.index_type = e.index_type
      AND COALESCE(a.index_collation, '<NULL>') = COALESCE(e.index_collation, '<NULL>')
      AND COALESCE(a.index_expression, '') = COALESCE(e.index_expression, '')
      AND a.is_visible = e.is_visible
      AND COALESCE(a.index_comment, '') = COALESCE(e.index_comment, '')
);
SET @bt_index_mismatch = IF(
    @bt_exists = 1, @bt_expected_indexes + @bt_actual_indexes - 2 * @bt_matched_indexes, 0
);

SET @mount_expected_indexes = (
    SELECT COUNT(*) FROM tmp_iws_catalog_expected_indexes
    WHERE table_name = 'wms_business_plan_mount'
);
SET @mount_actual_indexes = (
    SELECT COUNT(*) FROM tmp_iws_catalog_actual_indexes
    WHERE table_name = 'wms_business_plan_mount'
);
SET @mount_matched_indexes = (
    SELECT COUNT(*) FROM tmp_iws_catalog_expected_indexes e
    JOIN tmp_iws_catalog_actual_indexes a
      ON a.table_name = e.table_name
     AND a.index_name = e.index_name
     AND a.seq_in_index = e.seq_in_index
    WHERE e.table_name = 'wms_business_plan_mount'
      AND a.non_unique = e.non_unique
      AND a.column_name = e.column_name
      AND COALESCE(a.sub_part, -1) = COALESCE(e.sub_part, -1)
      AND a.index_type = e.index_type
      AND COALESCE(a.index_collation, '<NULL>') = COALESCE(e.index_collation, '<NULL>')
      AND COALESCE(a.index_expression, '') = COALESCE(e.index_expression, '')
      AND a.is_visible = e.is_visible
      AND COALESCE(a.index_comment, '') = COALESCE(e.index_comment, '')
);
SET @mount_index_mismatch = IF(
    @mount_exists = 1, @mount_expected_indexes + @mount_actual_indexes
        - 2 * @mount_matched_indexes, 0
);

SET @bt_table_mismatch = IF(@bt_exists = 1 AND NOT EXISTS (
    SELECT 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @target_schema
      AND TABLE_NAME = 'wms_business_type'
      AND TABLE_TYPE = 'BASE TABLE'
      AND ENGINE = 'InnoDB'
      AND TABLE_COLLATION = 'utf8mb4_0900_ai_ci'
      AND TABLE_COMMENT = 'WMS业务类型目录'
), 1, 0);

SET @mount_table_mismatch = IF(@mount_exists = 1 AND NOT EXISTS (
    SELECT 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @target_schema
      AND TABLE_NAME = 'wms_business_plan_mount'
      AND TABLE_TYPE = 'BASE TABLE'
      AND ENGINE = 'InnoDB'
      AND TABLE_COLLATION = 'utf8mb4_0900_ai_ci'
      AND TABLE_COMMENT = 'WMS业务类型计划挂载目录'
), 1, 0);

-- 目录两表不声明 CHECK、外键或触发器；外部（含跨库）指向目录表的外键同样
-- 视为未知依赖。全局 SELECT、TRIGGER 与 partial_revokes=OFF 才能完整看见
-- 触发器及跨库入向外键，因此元数据权限也进入同一门禁。
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

SET @preflight_ok = IF(
    @target_ok = 1
    AND @schema_ok = 1
    AND @schema_exists = 1
    AND @version_ok = 1
    AND @metadata_visibility_ok = 1
    AND @catalog_object_conflict_count = 0
    AND @schedule_exists = 1
    AND @plan_type_exists = 1
    AND @schedule_source_columns = @schedule_source_column_count
    AND @bt_column_mismatch = 0 AND @mount_column_mismatch = 0
    AND @bt_index_mismatch = 0 AND @mount_index_mismatch = 0
    AND @bt_table_mismatch = 0 AND @mount_table_mismatch = 0
    AND @catalog_check_count = 0
    AND @catalog_own_fk_count = 0
    AND @catalog_trigger_count = 0
    AND @catalog_inbound_fk_count = 0,
    1, 0
);

SELECT
    @catalog_release_id AS `发布标识`,
    @target_schema AS `目标库`,
    @metadata_visibility_ok AS `元数据权限通过`,
    @bt_exists AS `业务类型表已存在`,
    @mount_exists AS `挂载表已存在`,
    COALESCE(@bt_object_type, '<缺失>') AS `业务类型对象类型`,
    COALESCE(@mount_object_type, '<缺失>') AS `挂载对象类型`,
    @catalog_object_conflict_count AS `同名非表对象数`,
    @schedule_exists + @plan_type_exists AS `现有调度表数`,
    CONCAT(@schedule_source_columns, '/', @schedule_source_column_count) AS `调度源列匹配`,
    @bt_column_mismatch + @mount_column_mismatch AS `列签名冲突数`,
    @bt_index_mismatch + @mount_index_mismatch AS `索引签名冲突数`,
    @bt_table_mismatch + @mount_table_mismatch AS `表选项冲突数`,
    @catalog_check_count AS `CHECK数`,
    @catalog_own_fk_count AS `本表外键数`,
    @catalog_trigger_count AS `触发器数`,
    @catalog_inbound_fk_count AS `外部指入外键数`,
    IF(@preflight_ok = 1, '预检通过', '预检失败，禁止任何目录 DDL') AS `预检结果`;

SELECT JSON_EXTRACT(IF(@preflight_ok = 1, '{}', 'invalid'), '$') AS `目录预检门禁`;

SET @ddl_bt = IF(@preflight_ok = 1 AND @metadata_visibility_ok = 1
    AND @bt_exists = 0 AND @bt_object_type IS NULL, CONCAT(
    'CREATE TABLE ', @target_schema_q, '.`wms_business_type` (',
    '`business_type_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''业务类型编码'',',
    '`business_type_name` varchar(128) NOT NULL COMMENT ''业务类型名称'',',
    '`status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT ''ACTIVE'' COMMENT ''状态：ACTIVE/DISABLED'',',
    '`contract_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''业务合同版本'',',
    '`object_policy_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''对象策略键；本切面不注册策略'',',
    '`revision` int NOT NULL DEFAULT 1 COMMENT ''修订号'',',
    '`create_dept` bigint DEFAULT NULL COMMENT ''创建部门'',',
    '`create_by` bigint DEFAULT NULL COMMENT ''创建者'',',
    '`create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',',
    '`update_by` bigint DEFAULT NULL COMMENT ''更新者'',',
    '`update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',',
    'PRIMARY KEY (`business_type_code`),',
    'KEY `idx_wms_business_type_status` (`status`, `business_type_code`)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS业务类型目录'''
), 'DO 0');
PREPARE catalog_stmt FROM @ddl_bt;
EXECUTE catalog_stmt;
DEALLOCATE PREPARE catalog_stmt;
SET @bt_created = IF(@bt_exists = 0 AND EXISTS (
    SELECT 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @target_schema
      AND TABLE_NAME = 'wms_business_type'
      AND TABLE_TYPE = 'BASE TABLE'
), 1, 0);

SELECT JSON_EXTRACT(IF(@bt_exists = 1 OR @bt_created = 1, '{}', 'invalid'), '$')
    AS `业务类型表依赖写门禁`;

-- 第二张表依赖第一张表已存在且结构可用；元数据权限同样参与该依赖门禁。
SET @bt_dependency_ok = IF(
    @metadata_visibility_ok = 1
    AND EXISTS (
        SELECT 1 FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = @target_schema
          AND TABLE_NAME = 'wms_business_type'
          AND TABLE_TYPE = 'BASE TABLE'
    )
    AND (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = @target_schema
           AND TABLE_NAME = 'wms_business_type') = 11
    AND (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = @target_schema
           AND TABLE_NAME = 'wms_business_type') = 2,
    1, 0
);

SET @ddl_mount = IF(@preflight_ok = 1 AND @metadata_visibility_ok = 1
    AND @bt_dependency_ok = 1
    AND @mount_exists = 0 AND @mount_object_type IS NULL, CONCAT(
    'CREATE TABLE ', @target_schema_q, '.`wms_business_plan_mount` (',
    '`mount_id` bigint NOT NULL COMMENT ''挂载主键，系统生成'',',
    '`business_type_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''业务类型编码'',',
    '`plan_type_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''计划类型编码'',',
    '`schedule_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''调度业务编码，等于iws_business_schedule.business_code'',',
    '`contract_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''挂载合同版本'',',
    '`enabled` tinyint NOT NULL DEFAULT 1 COMMENT ''是否启用'',',
    '`revision` int NOT NULL DEFAULT 1 COMMENT ''修订号'',',
    '`create_dept` bigint DEFAULT NULL COMMENT ''创建部门'',',
    '`create_by` bigint DEFAULT NULL COMMENT ''创建者'',',
    '`create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',',
    '`update_by` bigint DEFAULT NULL COMMENT ''更新者'',',
    '`update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',',
    'PRIMARY KEY (`mount_id`),',
    'UNIQUE KEY `uk_wms_business_plan_mount` (`business_type_code`, `plan_type_code`, `schedule_code`),',
    'KEY `idx_wms_business_plan_mount_resolve` (`schedule_code`, `plan_type_code`, `enabled`)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS业务类型计划挂载目录'''
), 'DO 0');
PREPARE catalog_stmt FROM @ddl_mount;
EXECUTE catalog_stmt;
DEALLOCATE PREPARE catalog_stmt;
SET @mount_created = IF(@mount_exists = 0 AND EXISTS (
    SELECT 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @target_schema
      AND TABLE_NAME = 'wms_business_plan_mount'
      AND TABLE_TYPE = 'BASE TABLE'
), 1, 0);

DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_actual_columns;
CREATE TEMPORARY TABLE tmp_iws_catalog_actual_columns AS
SELECT TABLE_NAME AS table_name, ORDINAL_POSITION AS ordinal_position,
       COLUMN_NAME AS column_name, COLUMN_TYPE AS column_type,
       IS_NULLABLE AS is_nullable, COLUMN_DEFAULT AS column_default,
       EXTRA AS extra, CHARACTER_SET_NAME AS character_set,
       COLLATION_NAME AS collation_name, COLUMN_COMMENT AS column_comment
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @target_schema
  AND TABLE_NAME IN ('wms_business_type', 'wms_business_plan_mount');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_actual_indexes;
CREATE TEMPORARY TABLE tmp_iws_catalog_actual_indexes AS
SELECT TABLE_NAME AS table_name, INDEX_NAME AS index_name, NON_UNIQUE AS non_unique,
       SEQ_IN_INDEX AS seq_in_index, COLUMN_NAME AS column_name,
       SUB_PART AS sub_part, INDEX_TYPE AS index_type,
       COLLATION AS index_collation, EXPRESSION AS index_expression,
       IS_VISIBLE AS is_visible, INDEX_COMMENT AS index_comment
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = @target_schema
  AND TABLE_NAME IN ('wms_business_type', 'wms_business_plan_mount');

SET @bt_columns_post = (
    SELECT COUNT(*) FROM tmp_iws_catalog_expected_columns e
    JOIN tmp_iws_catalog_actual_columns a
      ON a.table_name = e.table_name
     AND a.ordinal_position = e.ordinal_position
     AND a.column_name = e.column_name
    WHERE e.table_name = 'wms_business_type'
      AND LOWER(e.column_type) = LOWER(a.column_type)
      AND e.is_nullable = a.is_nullable
      AND (CAST(e.column_default AS BINARY) <=> CAST(a.column_default AS BINARY))
      AND LOWER(e.extra) = LOWER(a.extra)
      AND COALESCE(e.character_set, '<NULL>') = COALESCE(a.character_set, '<NULL>')
      AND COALESCE(e.collation_name, '<NULL>') = COALESCE(a.collation_name, '<NULL>')
      AND e.column_comment = a.column_comment
);
SET @mount_columns_post = (
    SELECT COUNT(*) FROM tmp_iws_catalog_expected_columns e
    JOIN tmp_iws_catalog_actual_columns a
      ON a.table_name = e.table_name
     AND a.ordinal_position = e.ordinal_position
     AND a.column_name = e.column_name
    WHERE e.table_name = 'wms_business_plan_mount'
      AND LOWER(e.column_type) = LOWER(a.column_type)
      AND e.is_nullable = a.is_nullable
      AND (CAST(e.column_default AS BINARY) <=> CAST(a.column_default AS BINARY))
      AND LOWER(e.extra) = LOWER(a.extra)
      AND COALESCE(e.character_set, '<NULL>') = COALESCE(a.character_set, '<NULL>')
      AND COALESCE(e.collation_name, '<NULL>') = COALESCE(a.collation_name, '<NULL>')
      AND e.column_comment = a.column_comment
);
SET @bt_indexes_post = (
    SELECT COUNT(*) FROM tmp_iws_catalog_expected_indexes e
    JOIN tmp_iws_catalog_actual_indexes a
      ON a.table_name = e.table_name
     AND a.index_name = e.index_name
     AND a.seq_in_index = e.seq_in_index
    WHERE e.table_name = 'wms_business_type'
      AND a.non_unique = e.non_unique
      AND a.column_name = e.column_name
      AND COALESCE(a.sub_part, -1) = COALESCE(e.sub_part, -1)
      AND a.index_type = e.index_type
      AND COALESCE(a.index_collation, '<NULL>') = COALESCE(e.index_collation, '<NULL>')
      AND COALESCE(a.index_expression, '') = COALESCE(e.index_expression, '')
      AND a.is_visible = e.is_visible
      AND COALESCE(a.index_comment, '') = COALESCE(e.index_comment, '')
);
SET @mount_indexes_post = (
    SELECT COUNT(*) FROM tmp_iws_catalog_expected_indexes e
    JOIN tmp_iws_catalog_actual_indexes a
      ON a.table_name = e.table_name
     AND a.index_name = e.index_name
     AND a.seq_in_index = e.seq_in_index
    WHERE e.table_name = 'wms_business_plan_mount'
      AND a.non_unique = e.non_unique
      AND a.column_name = e.column_name
      AND COALESCE(a.sub_part, -1) = COALESCE(e.sub_part, -1)
      AND a.index_type = e.index_type
      AND COALESCE(a.index_collation, '<NULL>') = COALESCE(e.index_collation, '<NULL>')
      AND COALESCE(a.index_expression, '') = COALESCE(e.index_expression, '')
      AND a.is_visible = e.is_visible
      AND COALESCE(a.index_comment, '') = COALESCE(e.index_comment, '')
);

SET @bt_table_post = (
    SELECT COUNT(*) = 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @target_schema
      AND TABLE_NAME = 'wms_business_type'
      AND TABLE_TYPE = 'BASE TABLE'
      AND ENGINE = 'InnoDB'
      AND TABLE_COLLATION = 'utf8mb4_0900_ai_ci'
      AND TABLE_COMMENT = 'WMS业务类型目录'
);
SET @mount_table_post = (
    SELECT COUNT(*) = 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @target_schema
      AND TABLE_NAME = 'wms_business_plan_mount'
      AND TABLE_TYPE = 'BASE TABLE'
      AND ENGINE = 'InnoDB'
      AND TABLE_COLLATION = 'utf8mb4_0900_ai_ci'
      AND TABLE_COMMENT = 'WMS业务类型计划挂载目录'
);
SET @catalog_object_conflict_post = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @target_schema
      AND TABLE_NAME IN ('wms_business_type', 'wms_business_plan_mount')
      AND TABLE_TYPE <> 'BASE TABLE'
);
SET @schedule_source_columns_post = (
    SELECT COUNT(*)
    FROM tmp_iws_catalog_expected_source_columns e
    JOIN information_schema.COLUMNS a
      ON a.TABLE_SCHEMA = @target_schema
     AND a.TABLE_NAME = e.table_name
     AND a.COLUMN_NAME = e.column_name
    WHERE LOWER(a.COLUMN_TYPE) = LOWER(e.column_type)
      AND a.IS_NULLABLE = e.is_nullable
);
SET @catalog_check_post = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = @target_schema
      AND TABLE_NAME IN ('wms_business_type', 'wms_business_plan_mount')
      AND CONSTRAINT_TYPE = 'CHECK'
);
SET @catalog_own_fk_post = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = @target_schema
      AND TABLE_NAME IN ('wms_business_type', 'wms_business_plan_mount')
      AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @catalog_trigger_post = (
    SELECT COUNT(*) FROM information_schema.TRIGGERS
    WHERE EVENT_OBJECT_SCHEMA = @target_schema
      AND EVENT_OBJECT_TABLE IN ('wms_business_type', 'wms_business_plan_mount')
);
SET @catalog_inbound_fk_post = (
    SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
    WHERE UNIQUE_CONSTRAINT_SCHEMA = @target_schema
      AND REFERENCED_TABLE_NAME IN ('wms_business_type', 'wms_business_plan_mount')
);

SET @post_structure_ok = IF(
    @preflight_ok = 1
    AND @metadata_visibility_ok = 1
    AND @bt_table_post = 1 AND @mount_table_post = 1
    AND @catalog_object_conflict_post = 0
    AND @schedule_source_columns_post = @schedule_source_column_count
    AND @bt_columns_post = 11
    AND @mount_columns_post = 12
    AND @bt_indexes_post = 3
    AND @mount_indexes_post = 7
    AND @catalog_check_post = 0
    AND @catalog_own_fk_post = 0
    AND @catalog_trigger_post = 0
    AND @catalog_inbound_fk_post = 0,
    1, 0
);

SELECT
    @bt_created AS `本次新建业务类型表`,
    @mount_created AS `本次新建挂载表`,
    @bt_columns_post AS `业务类型表匹配列数`,
    @mount_columns_post AS `挂载表匹配列数`,
    @bt_indexes_post AS `业务类型表匹配索引列数`,
    @mount_indexes_post AS `挂载表匹配索引列数`,
    @schedule_source_columns_post AS `调度源列匹配数`,
    @catalog_check_post AS `CHECK数`,
    @catalog_own_fk_post AS `本表外键数`,
    @catalog_trigger_post AS `触发器数`,
    @catalog_inbound_fk_post AS `外部指入外键数`,
    IF(@post_structure_ok = 1, '目录结构完成', '目录结构验收失败') AS `结构结果`;

SELECT JSON_EXTRACT(IF(@post_structure_ok = 1, '{}', 'invalid'), '$') AS `目录结构完成门禁`;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_actual_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_actual_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_expected_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_expected_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_expected_source_columns;
