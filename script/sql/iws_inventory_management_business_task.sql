-- ============================================================================
-- IWS+ 库存 P1/P5 业务任务与冻结输入结构
-- ============================================================================
-- 目标数据库：MySQL 8.0.45
-- 目标库：@target_schema；默认 innolux-iws，可由执行器先设置
--   @iws_inventory_target_schema 覆盖，并与 DATABASE() 字节一致
-- 目标对象：wms_business_task
-- 用途：
--   为已注册父业务保存稳定 taskKey、计划实例、输入摘要及完整冻结命令 JSON。
--   LocalActionInputProvider 按 plan_instance_id 读取该 JSON，不按页面当前数据重建。
-- 边界：
--   只新增结构，不创建 MOVE/ALLOCATION 业务行、计划、权限或菜单；
--   跨表 ID 为逻辑引用，不建物理外键；不修改 core/init 或其他 WMS 表。
-- 幂等：
--   表不存在时创建；已存在时必须逐表、逐列、逐索引、逐 CHECK 与本脚本预期一致。
--   漂移、同名非表对象、触发器、本表或外部指入外键均在任何 DDL 前失败。
-- ============================================================================

SET NAMES utf8mb4;

SET @target_schema = IFNULL(@iws_inventory_target_schema, 'innolux-iws');
SET @task_release_id = 'INV-P5-03-BUSINESS-TASK-20261031';
SET @expected_mysql_version = '8.0.45';
SET @target_table = 'wms_business_task';
SET @target_table_names = 'wms_business_task';

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
    CAST(VERSION() AS BINARY) = CAST(@expected_mysql_version AS BINARY)
    AND VERSION() NOT LIKE '%MariaDB%'
);

DROP TEMPORARY TABLE IF EXISTS tmp_iws_task_expected_tables;
CREATE TEMPORARY TABLE tmp_iws_task_expected_tables (
    table_name      varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    table_type      varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    engine          varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    table_collation varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    create_options  varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    table_comment   varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_task_expected_columns;
CREATE TEMPORARY TABLE tmp_iws_task_expected_columns (
    table_name            varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ordinal_position      int          NOT NULL,
    column_name           varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    column_type           varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    is_nullable           varchar(3)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    column_default        longtext     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    extra                 varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    character_set_name    varchar(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    collation_name        varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    generation_expression longtext     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    column_comment        varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name, ordinal_position),
    UNIQUE KEY uk_tmp_iws_task_expected_columns (table_name, column_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_task_expected_indexes;
CREATE TEMPORARY TABLE tmp_iws_task_expected_indexes (
    table_name       varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    index_name       varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    non_unique       int          NOT NULL,
    seq_in_index     int          NOT NULL,
    column_name      varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    sub_part         bigint       NULL,
    packed           varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    nullable         varchar(3)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    index_type       varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    index_collation  varchar(3)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    index_expression longtext     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    is_visible       varchar(3)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    index_comment    varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name, index_name, seq_in_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_task_expected_checks;
CREATE TEMPORARY TABLE tmp_iws_task_expected_checks (
    table_name      varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    constraint_name varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    check_clause    longtext    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    enforced        varchar(3)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name, constraint_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_iws_task_expected_tables
    (table_name, table_type, engine, table_collation, create_options, table_comment)
VALUES
('wms_business_task', 'BASE TABLE', 'InnoDB', 'utf8mb4_0900_ai_ci', '', 'WMS业务任务与冻结输入');

INSERT INTO tmp_iws_task_expected_columns
    (table_name, ordinal_position, column_name, column_type, is_nullable, column_default,
     extra, character_set_name, collation_name, generation_expression, column_comment)
VALUES
('wms_business_task', 1, 'task_id', 'bigint', 'NO', NULL, '', NULL, NULL, '', 'WMS业务任务主键，系统生成'),
('wms_business_task', 2, 'business_type', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_bin', '', '父业务类型稳定编码'),
('wms_business_task', 3, 'business_id', 'bigint', 'NO', NULL, '', NULL, NULL, '', '父业务内部ID'),
('wms_business_task', 4, 'line_id', 'bigint', 'YES', NULL, '', NULL, NULL, '', '父业务明细ID'),
('wms_business_task', 5, 'allocation_unit_id', 'bigint', 'NO', NULL, '', NULL, NULL, '', '分配单元ID'),
('wms_business_task', 6, 'round_no', 'int', 'NO', '0', '', NULL, NULL, '', '轮次号'),
('wms_business_task', 7, 'phase', 'varchar(32)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_bin', '', '阶段编码'),
('wms_business_task', 8, 'task_key', 'varchar(96)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_bin', '', '业务任务唯一键'),
('wms_business_task', 9, 'plan_type', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_bin', '', '计划类型编码'),
('wms_business_task', 10, 'plan_instance_id', 'bigint', 'NO', NULL, '', NULL, NULL, '', '计划实例ID'),
('wms_business_task', 11, 'input_hash', 'char(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_bin', '', '规范化输入SHA-256小写十六进制'),
('wms_business_task', 12, 'input_json', 'longtext', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_bin', '', '完整冻结业务命令JSON'),
('wms_business_task', 13, 'status', 'varchar(16)', 'NO', 'ACTIVE', '', 'utf8mb4', 'utf8mb4_0900_bin', '', '状态：ACTIVE/RUNNING/COMPLETED/FAILED/CANCELLED'),
('wms_business_task', 14, 'revision', 'int', 'NO', '1', '', NULL, NULL, '', '业务任务修订号'),
('wms_business_task', 15, 'create_dept', 'bigint', 'YES', NULL, '', NULL, NULL, '', '创建部门'),
('wms_business_task', 16, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL, '', '创建者'),
('wms_business_task', 17, 'create_time', 'datetime(3)', 'NO', 'CURRENT_TIMESTAMP(3)', 'DEFAULT_GENERATED', NULL, NULL, '', '创建时间'),
('wms_business_task', 18, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL, '', '更新者'),
('wms_business_task', 19, 'update_time', 'datetime(3)', 'YES', NULL, 'on update CURRENT_TIMESTAMP(3)', NULL, NULL, '', '更新时间');

INSERT INTO tmp_iws_task_expected_indexes
    (table_name, index_name, non_unique, seq_in_index, column_name, sub_part, packed,
     nullable, index_type, index_collation, index_expression, is_visible, index_comment)
VALUES
('wms_business_task', 'PRIMARY', 0, 1, 'task_id', NULL, NULL, '', 'BTREE', 'A', NULL, 'YES', ''),
('wms_business_task', 'uk_wms_business_task_key', 0, 1, 'business_type', NULL, NULL, '', 'BTREE', 'A', NULL, 'YES', ''),
('wms_business_task', 'uk_wms_business_task_key', 0, 2, 'task_key', NULL, NULL, '', 'BTREE', 'A', NULL, 'YES', ''),
('wms_business_task', 'uk_wms_business_task_instance', 0, 1, 'plan_instance_id', NULL, NULL, '', 'BTREE', 'A', NULL, 'YES', ''),
('wms_business_task', 'idx_wms_business_task_business', 1, 1, 'business_type', NULL, NULL, '', 'BTREE', 'A', NULL, 'YES', ''),
('wms_business_task', 'idx_wms_business_task_business', 1, 2, 'business_id', NULL, NULL, '', 'BTREE', 'A', NULL, 'YES', ''),
('wms_business_task', 'idx_wms_business_task_business', 1, 3, 'phase', NULL, NULL, '', 'BTREE', 'A', NULL, 'YES', '');

INSERT INTO tmp_iws_task_expected_checks
    (table_name, constraint_name, check_clause, enforced)
VALUES
('wms_business_task', 'ck_wms_business_task_round_no', '(`round_no` >= 0)', 'YES'),
('wms_business_task', 'ck_wms_business_task_revision', '(`revision` >= 1)', 'YES'),
('wms_business_task', 'ck_wms_business_task_input_hash', 'regexp_like(`input_hash`,_utf8mb4''^[0-9a-f]{64}$'',_utf8mb4''c'')', 'YES'),
('wms_business_task', 'ck_wms_business_task_input_json', 'json_valid(`input_json`)', 'YES'),
('wms_business_task', 'ck_wms_business_task_status', '(`status` in (_utf8mb4''ACTIVE'',_utf8mb4''RUNNING'',_utf8mb4''COMPLETED'',_utf8mb4''FAILED'',_utf8mb4''CANCELLED''))', 'YES');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_task_actual_tables;
CREATE TEMPORARY TABLE tmp_iws_task_actual_tables AS
SELECT TABLE_NAME AS table_name, TABLE_TYPE AS table_type, ENGINE AS engine,
       TABLE_COLLATION AS table_collation, CREATE_OPTIONS AS create_options,
       TABLE_COMMENT AS table_comment
FROM information_schema.TABLES
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND CAST(TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY);

DROP TEMPORARY TABLE IF EXISTS tmp_iws_task_actual_columns;
CREATE TEMPORARY TABLE tmp_iws_task_actual_columns AS
SELECT TABLE_NAME AS table_name, ORDINAL_POSITION AS ordinal_position,
       COLUMN_NAME AS column_name, COLUMN_TYPE AS column_type,
       IS_NULLABLE AS is_nullable, COLUMN_DEFAULT AS column_default,
       EXTRA AS extra, CHARACTER_SET_NAME AS character_set_name,
       COLLATION_NAME AS collation_name, GENERATION_EXPRESSION AS generation_expression,
       COLUMN_COMMENT AS column_comment
FROM information_schema.COLUMNS
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND CAST(TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY);

DROP TEMPORARY TABLE IF EXISTS tmp_iws_task_actual_indexes;
CREATE TEMPORARY TABLE tmp_iws_task_actual_indexes AS
SELECT TABLE_NAME AS table_name, INDEX_NAME AS index_name,
       NON_UNIQUE AS non_unique, SEQ_IN_INDEX AS seq_in_index,
       COLUMN_NAME AS column_name, SUB_PART AS sub_part, PACKED AS packed,
       NULLABLE AS nullable, INDEX_TYPE AS index_type, COLLATION AS index_collation,
       EXPRESSION AS index_expression, IS_VISIBLE AS is_visible,
       INDEX_COMMENT AS index_comment
FROM information_schema.STATISTICS
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND CAST(TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY);

DROP TEMPORARY TABLE IF EXISTS tmp_iws_task_actual_checks;
CREATE TEMPORARY TABLE tmp_iws_task_actual_checks AS
SELECT tc.TABLE_NAME AS table_name, tc.CONSTRAINT_NAME AS constraint_name,
       cc.CHECK_CLAUSE AS check_clause, tc.ENFORCED AS enforced
FROM information_schema.TABLE_CONSTRAINTS tc
JOIN information_schema.CHECK_CONSTRAINTS cc
  ON CAST(cc.CONSTRAINT_SCHEMA AS BINARY) = CAST(tc.CONSTRAINT_SCHEMA AS BINARY)
 AND CAST(cc.CONSTRAINT_NAME AS BINARY) = CAST(tc.CONSTRAINT_NAME AS BINARY)
WHERE CAST(tc.CONSTRAINT_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND tc.CONSTRAINT_TYPE = 'CHECK'
  AND CAST(tc.TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY);

SET @object_conflict_count = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY)
      AND TABLE_TYPE <> 'BASE TABLE'
);
SET @existing_table_count = (
    SELECT COUNT(*) FROM tmp_iws_task_actual_tables WHERE table_type = 'BASE TABLE'
);
SET @pre_table_mismatch = (
    SELECT COUNT(*) FROM tmp_iws_task_actual_tables a
    LEFT JOIN tmp_iws_task_expected_tables e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE e.table_name IS NULL
       OR CAST(a.table_type AS BINARY) <> CAST(e.table_type AS BINARY)
       OR NOT (CAST(a.engine AS BINARY) <=> CAST(e.engine AS BINARY))
       OR NOT (CAST(a.table_collation AS BINARY) <=> CAST(e.table_collation AS BINARY))
       OR CAST(a.create_options AS BINARY) <> CAST(e.create_options AS BINARY)
       OR CAST(a.table_comment AS BINARY) <> CAST(e.table_comment AS BINARY)
);
SET @pre_column_mismatch = (
    SELECT COUNT(*) FROM tmp_iws_task_actual_columns a
    LEFT JOIN tmp_iws_task_expected_columns e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE e.column_name IS NULL OR a.ordinal_position <> e.ordinal_position
       OR CAST(a.column_type AS BINARY) <> CAST(e.column_type AS BINARY)
       OR CAST(a.is_nullable AS BINARY) <> CAST(e.is_nullable AS BINARY)
       OR NOT (CAST(a.column_default AS BINARY) <=> CAST(e.column_default AS BINARY))
       OR CAST(a.extra AS BINARY) <> CAST(e.extra AS BINARY)
       OR NOT (CAST(a.character_set_name AS BINARY) <=> CAST(e.character_set_name AS BINARY))
       OR NOT (CAST(a.collation_name AS BINARY) <=> CAST(e.collation_name AS BINARY))
       OR CAST(a.generation_expression AS BINARY) <> CAST(e.generation_expression AS BINARY)
       OR CAST(a.column_comment AS BINARY) <> CAST(e.column_comment AS BINARY)
);
SET @pre_column_missing = (
    SELECT COUNT(*) FROM tmp_iws_task_expected_columns e
    JOIN tmp_iws_task_actual_tables t
      ON CAST(t.table_name AS BINARY) = CAST(e.table_name AS BINARY) AND t.table_type = 'BASE TABLE'
    LEFT JOIN tmp_iws_task_actual_columns a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE a.column_name IS NULL
);
SET @pre_index_mismatch = (
    SELECT COUNT(*) FROM tmp_iws_task_actual_indexes a
    LEFT JOIN tmp_iws_task_expected_indexes e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE e.index_name IS NULL OR a.non_unique <> e.non_unique
       OR NOT (CAST(a.column_name AS BINARY) <=> CAST(e.column_name AS BINARY))
       OR NOT (a.sub_part <=> e.sub_part)
       OR NOT (CAST(a.packed AS BINARY) <=> CAST(e.packed AS BINARY))
       OR NOT (CAST(a.nullable AS BINARY) <=> CAST(e.nullable AS BINARY))
       OR CAST(a.index_type AS BINARY) <> CAST(e.index_type AS BINARY)
       OR NOT (CAST(a.index_collation AS BINARY) <=> CAST(e.index_collation AS BINARY))
       OR NOT (CAST(a.index_expression AS BINARY) <=> CAST(e.index_expression AS BINARY))
       OR CAST(a.is_visible AS BINARY) <> CAST(e.is_visible AS BINARY)
       OR CAST(a.index_comment AS BINARY) <> CAST(e.index_comment AS BINARY)
);
SET @pre_index_missing = (
    SELECT COUNT(*) FROM tmp_iws_task_expected_indexes e
    JOIN tmp_iws_task_actual_tables t
      ON CAST(t.table_name AS BINARY) = CAST(e.table_name AS BINARY) AND t.table_type = 'BASE TABLE'
    LEFT JOIN tmp_iws_task_actual_indexes a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE a.index_name IS NULL
);
SET @pre_check_mismatch = (
    SELECT COUNT(*) FROM tmp_iws_task_actual_checks a
    LEFT JOIN tmp_iws_task_expected_checks e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE e.constraint_name IS NULL
       OR REPLACE(CAST(a.check_clause AS BINARY), '\\', '')
          <> REPLACE(CAST(e.check_clause AS BINARY), '\\', '')
       OR CAST(a.enforced AS BINARY) <> CAST(e.enforced AS BINARY)
);
SET @pre_check_missing = (
    SELECT COUNT(*) FROM tmp_iws_task_expected_checks e
    JOIN tmp_iws_task_actual_tables t
      ON CAST(t.table_name AS BINARY) = CAST(e.table_name AS BINARY) AND t.table_type = 'BASE TABLE'
    LEFT JOIN tmp_iws_task_actual_checks a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE a.constraint_name IS NULL
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
SET @inbound_fk_count = (
    SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
    WHERE CAST(REFERENCED_TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND CAST(REFERENCED_TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY)
);
SET @pre_table_definition_mismatch =
    @pre_table_mismatch + @pre_column_mismatch + @pre_column_missing
    + @pre_index_mismatch + @pre_index_missing
    + @pre_check_mismatch + @pre_check_missing;
SET @preflight_ok = IF(
    @metadata_visibility_ok = 1
    AND @schema_ok = 1 AND @schema_exists = 1 AND @version_ok = 1
    AND @object_conflict_count = 0
    AND @pre_table_definition_mismatch = 0
    AND @own_fk_count = 0 AND @trigger_count = 0 AND @inbound_fk_count = 0,
    1, 0
);

SELECT
    @task_release_id AS `发布标识`,
    @target_schema AS `目标库`,
    DATABASE() AS `当前库`,
    VERSION() AS `MySQL版本`,
    @existing_table_count AS `现有目标表数`,
    @pre_table_definition_mismatch AS `目标结构冲突数`,
    @own_fk_count AS `本表外键数`,
    @trigger_count AS `触发器数`,
    @inbound_fk_count AS `外部指入外键数`,
    IF(@preflight_ok = 1, '预检通过', '预检失败，禁止业务任务 DDL') AS `预检结果`;
SELECT IF(
    @preflight_ok = 1,
    1,
    CAST(CONCAT(
        'invalid business task preflight: metadata=', @metadata_visibility_ok,
        ', schema=', @schema_ok,
        ', schema_exists=', @schema_exists,
        ', version=', @version_ok,
        ', object_conflict=', @object_conflict_count,
        ', structure=', @pre_table_definition_mismatch,
        ', own_fk=', @own_fk_count,
        ', trigger=', @trigger_count,
        ', inbound_fk=', @inbound_fk_count
    ) AS JSON)
) AS `业务任务预检硬门禁`;

CREATE TABLE IF NOT EXISTS `wms_business_task` (
    `task_id` bigint NOT NULL COMMENT 'WMS业务任务主键，系统生成',
    `business_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '父业务类型稳定编码',
    `business_id` bigint NOT NULL COMMENT '父业务内部ID',
    `line_id` bigint NULL COMMENT '父业务明细ID',
    `allocation_unit_id` bigint NOT NULL COMMENT '分配单元ID',
    `round_no` int NOT NULL DEFAULT 0 COMMENT '轮次号',
    `phase` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '阶段编码',
    `task_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '业务任务唯一键',
    `plan_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '计划类型编码',
    `plan_instance_id` bigint NOT NULL COMMENT '计划实例ID',
    `input_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '规范化输入SHA-256小写十六进制',
    `input_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '完整冻结业务命令JSON',
    `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/RUNNING/COMPLETED/FAILED/CANCELLED',
    `revision` int NOT NULL DEFAULT 1 COMMENT '业务任务修订号',
    `create_dept` bigint NULL COMMENT '创建部门',
    `create_by` bigint NULL COMMENT '创建者',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_by` bigint NULL COMMENT '更新者',
    `update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`task_id`),
    UNIQUE KEY `uk_wms_business_task_key` (`business_type`, `task_key`),
    UNIQUE KEY `uk_wms_business_task_instance` (`plan_instance_id`),
    KEY `idx_wms_business_task_business` (`business_type`, `business_id`, `phase`),
    CONSTRAINT `ck_wms_business_task_round_no` CHECK (`round_no` >= 0),
    CONSTRAINT `ck_wms_business_task_revision` CHECK (`revision` >= 1),
    CONSTRAINT `ck_wms_business_task_input_hash` CHECK (
        REGEXP_LIKE(`input_hash`, _utf8mb4'^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT `ck_wms_business_task_input_json` CHECK (JSON_VALID(`input_json`)),
    CONSTRAINT `ck_wms_business_task_status` CHECK (
        `status` IN ('ACTIVE','RUNNING','COMPLETED','FAILED','CANCELLED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='WMS业务任务与冻结输入';

SET @post_table_count = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY)
      AND TABLE_TYPE = 'BASE TABLE'
);
SET @post_column_count = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY)
);
SET @post_index_rows = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY)
);
SET @post_check_rows = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CAST(CONSTRAINT_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST(@target_table AS BINARY)
      AND CONSTRAINT_TYPE = 'CHECK'
);
SET @postflight_ok = IF(
    @post_table_count = 1
    AND @post_column_count = 19
    AND @post_index_rows = 7
    AND @post_check_rows = 5,
    1, 0
);
SELECT
    @post_table_count AS `目标表数`,
    @post_column_count AS `列数`,
    @post_index_rows AS `索引列行数`,
    @post_check_rows AS `CHECK数`,
    IF(@postflight_ok = 1, '业务任务结构验收通过', '业务任务结构验收失败') AS `验收结果`;
SELECT IF(@postflight_ok = 1, 1, CAST('invalid business task postflight' AS JSON)) AS `业务任务后置硬门禁`;

SELECT '业务任务结构已满足；再次执行本脚本会逐列、逐索引、逐 CHECK 复核' AS `执行说明`;
