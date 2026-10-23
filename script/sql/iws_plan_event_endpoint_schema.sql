/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件与系统接入配置
 * @Version      : V1.0
 * @Description  : 系统接入配置独立目录表升级；目标 MySQL 8.0，可重复执行并进行完整结构核验；
 * @ModifyRecord : V1.0 新建；
 */

-- 目标：MySQL 8.0；单一目标库参数见下一行。
-- 执行：由同一会话顺序执行；支持 mysql 客户端与可执行 PREPARE 的 SQL 客户端。
-- 安全：所有持久化写入均受目标、预检与后置核验门控；客户端即使续错也不会继续落写。
-- 说明：本脚本不创建业务系统、环境、地址或凭据，仅创建结构及唯一目录控制行。

SET @upgrade_schema = 'innolux_iws';

SET @upgrade_schema_q = CONCAT('`', REPLACE(@upgrade_schema, '`', '``'), '`');

SET @ep_target_ok = (
    SELECT COUNT(*) = 1
    FROM information_schema.SCHEMATA
    WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@upgrade_schema AS BINARY)
);
SET @ep_database_matches = (
    DATABASE() IS NOT NULL
    AND CAST(DATABASE() AS BINARY) = CAST(@upgrade_schema AS BINARY)
);
SET @ep_version_ok = (VERSION() LIKE '8.0.%');
SET @ep_schema_collation_ok = (
    SELECT COUNT(*) = 1
    FROM information_schema.SCHEMATA
    WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@upgrade_schema AS BINARY)
      AND DEFAULT_COLLATION_NAME = 'utf8mb4_general_ci'
);
SET @ep_table_case_setting_ok = (@@lower_case_table_names IN (0, 1, 2));
SET @ep_precheck_ok = (
    @ep_target_ok = 1
    AND @ep_database_matches = 1
    AND @ep_version_ok = 1
    AND @ep_schema_collation_ok = 1
    AND @ep_table_case_setting_ok = 1
);

SELECT
    @ep_target_ok AS `目标库存在`,
    @ep_database_matches AS `当前连接库一致`,
    @ep_version_ok AS `MySQL8版本`,
    @ep_schema_collation_ok AS `目标库排序规则`,
    @ep_table_case_setting_ok AS `表名大小写设置合法`,
    @@lower_case_table_names AS `lower_case_table_names`,
    IF(@ep_precheck_ok = 1, '前置校验通过', '前置校验失败，禁止写入') AS `执行说明`;

-- 执行器应在此停止；继续执行的客户端也会因无效 JSON 报错，后续写入仍受门控。
SELECT JSON_EXTRACT(IF(@ep_precheck_ok = 1, '{}', 'invalid'), '$') AS `前置校验`;

SET @ep_conflicts = 0;
SET @ep_control_count = NULL;
SET @ep_control_bad = NULL;
SET @ep_final_count = NULL;
SET @ep_final_revision = NULL;
SET @ep_final_ok = 0;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_ep_expected_tables;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_ep_expected_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_ep_expected_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_ep_conflicts;

CREATE TEMPORARY TABLE tmp_iws_ep_expected_tables (
    table_name varchar(64) NOT NULL,
    PRIMARY KEY (table_name)
);

CREATE TEMPORARY TABLE tmp_iws_ep_expected_columns (
    table_name varchar(64) NOT NULL,
    ordinal_position int NOT NULL,
    column_name varchar(64) NOT NULL,
    column_type varchar(255) NOT NULL,
    is_nullable varchar(3) NOT NULL,
    column_default varchar(1024) DEFAULT NULL,
    extra varchar(64) NOT NULL,
    character_set_name varchar(64) DEFAULT NULL,
    collation_name varchar(64) DEFAULT NULL,
    PRIMARY KEY (table_name, ordinal_position),
    UNIQUE KEY uk_tmp_iws_ep_column (table_name, column_name)
);

CREATE TEMPORARY TABLE tmp_iws_ep_expected_indexes (
    table_name varchar(64) NOT NULL,
    index_name varchar(64) NOT NULL,
    non_unique int NOT NULL,
    seq_in_index int NOT NULL,
    column_name varchar(64) NOT NULL,
    sub_part int DEFAULT NULL,
    index_type varchar(16) NOT NULL,
    PRIMARY KEY (table_name, index_name, seq_in_index)
);

CREATE TEMPORARY TABLE tmp_iws_ep_conflicts (
    table_name varchar(64) NOT NULL,
    reason varchar(255) NOT NULL,
    KEY idx_tmp_iws_ep_conflict (table_name)
);

INSERT INTO tmp_iws_ep_expected_tables (table_name) VALUES
('iws_endpoint_system'),
('iws_endpoint_environment'),
('iws_endpoint_address'),
('iws_endpoint_system_address'),
('iws_endpoint_catalog_control'),
('iws_endpoint_audit');

-- 精确对象名核验：允许 Linux 的 lower_case_table_names=0，但不接受仅大小写近似的新表名。
DELETE FROM tmp_iws_ep_conflicts;
INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('存在大小写不一致对象：', i.TABLE_NAME)
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON CAST(i.TABLE_SCHEMA AS BINARY) = CAST(@upgrade_schema AS BINARY)
 AND LOWER(i.TABLE_NAME) = LOWER(t.table_name)
WHERE CAST(i.TABLE_NAME AS BINARY) <> CAST(t.table_name AS BINARY);

INSERT INTO tmp_iws_ep_expected_columns
(table_name, ordinal_position, column_name, column_type, is_nullable, column_default, extra, character_set_name, collation_name) VALUES
('iws_endpoint_system', 1, 'system_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_system', 2, 'display_name', 'varchar(128)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_system', 3, 'description', 'varchar(500)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_system', 4, 'enabled', 'tinyint(1)', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_system', 5, 'revision', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_system', 6, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_endpoint_system', 7, 'create_time', 'datetime', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_system', 8, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_endpoint_system', 9, 'update_time', 'datetime', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_environment', 1, 'environment_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_environment', 2, 'display_name', 'varchar(128)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_environment', 3, 'description', 'varchar(500)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_environment', 4, 'enabled', 'tinyint(1)', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_environment', 5, 'revision', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_environment', 6, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_endpoint_environment', 7, 'create_time', 'datetime', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_environment', 8, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_endpoint_environment', 9, 'update_time', 'datetime', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_address', 1, 'address_id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL),
('iws_endpoint_address', 2, 'environment_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_address', 3, 'endpoint_ref', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_address', 4, 'protocol', 'varchar(8)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_address', 5, 'host', 'varchar(253)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_address', 6, 'port', 'smallint unsigned', 'YES', NULL, '', NULL, NULL),
('iws_endpoint_address', 7, 'base_path', 'varchar(512)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_address', 8, 'credential_ref', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_address', 9, 'enabled', 'tinyint(1)', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_address', 10, 'revision', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_address', 11, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_endpoint_address', 12, 'create_time', 'datetime', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_address', 13, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_endpoint_address', 14, 'update_time', 'datetime', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_system_address', 1, 'relation_id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL),
('iws_endpoint_system_address', 2, 'system_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_system_address', 3, 'address_id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_system_address', 4, 'enabled', 'tinyint(1)', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_system_address', 5, 'revision', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_system_address', 6, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_endpoint_system_address', 7, 'create_time', 'datetime', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_system_address', 8, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_endpoint_system_address', 9, 'update_time', 'datetime', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_catalog_control', 1, 'singleton_key', 'varchar(16)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_catalog_control', 2, 'revision', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_catalog_control', 3, 'update_time', 'datetime', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_audit', 1, 'audit_id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL),
('iws_endpoint_audit', 2, 'object_type', 'varchar(32)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 3, 'object_key', 'varchar(128)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 4, 'environment_code', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 5, 'action', 'varchar(32)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 6, 'before_summary', 'text', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 7, 'after_summary', 'text', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 8, 'operator_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_endpoint_audit', 9, 'operator_name', 'varchar(128)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 10, 'command_key', 'varchar(128)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 11, 'request_hash', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 12, 'reason', 'varchar(500)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 13, 'catalog_revision', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_audit', 14, 'result_revision', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_audit', 15, 'result_object_key', 'varchar(128)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 16, 'create_time', 'datetime', 'NO', NULL, '', NULL, NULL);

INSERT INTO tmp_iws_ep_expected_indexes
(table_name, index_name, non_unique, seq_in_index, column_name, sub_part, index_type) VALUES
('iws_endpoint_system', 'PRIMARY', 0, 1, 'system_code', NULL, 'BTREE'),
('iws_endpoint_system', 'idx_iws_endpoint_system_page', 1, 1, 'enabled', NULL, 'BTREE'),
('iws_endpoint_system', 'idx_iws_endpoint_system_page', 1, 2, 'update_time', NULL, 'BTREE'),
('iws_endpoint_system', 'idx_iws_endpoint_system_page', 1, 3, 'system_code', NULL, 'BTREE'),
('iws_endpoint_environment', 'PRIMARY', 0, 1, 'environment_code', NULL, 'BTREE'),
('iws_endpoint_environment', 'idx_iws_endpoint_environment_page', 1, 1, 'enabled', NULL, 'BTREE'),
('iws_endpoint_environment', 'idx_iws_endpoint_environment_page', 1, 2, 'update_time', NULL, 'BTREE'),
('iws_endpoint_environment', 'idx_iws_endpoint_environment_page', 1, 3, 'environment_code', NULL, 'BTREE'),
('iws_endpoint_address', 'PRIMARY', 0, 1, 'address_id', NULL, 'BTREE'),
('iws_endpoint_address', 'uk_iws_endpoint_address_ref', 0, 1, 'environment_code', NULL, 'BTREE'),
('iws_endpoint_address', 'uk_iws_endpoint_address_ref', 0, 2, 'endpoint_ref', NULL, 'BTREE'),
('iws_endpoint_address', 'idx_iws_endpoint_address_page', 1, 1, 'environment_code', NULL, 'BTREE'),
('iws_endpoint_address', 'idx_iws_endpoint_address_page', 1, 2, 'enabled', NULL, 'BTREE'),
('iws_endpoint_address', 'idx_iws_endpoint_address_page', 1, 3, 'update_time', NULL, 'BTREE'),
('iws_endpoint_address', 'idx_iws_endpoint_address_page', 1, 4, 'address_id', NULL, 'BTREE'),
('iws_endpoint_system_address', 'PRIMARY', 0, 1, 'relation_id', NULL, 'BTREE'),
('iws_endpoint_system_address', 'uk_iws_endpoint_system_address', 0, 1, 'system_code', NULL, 'BTREE'),
('iws_endpoint_system_address', 'uk_iws_endpoint_system_address', 0, 2, 'address_id', NULL, 'BTREE'),
('iws_endpoint_system_address', 'idx_iws_endpoint_system_address_addr', 1, 1, 'address_id', NULL, 'BTREE'),
('iws_endpoint_system_address', 'idx_iws_endpoint_system_address_addr', 1, 2, 'enabled', NULL, 'BTREE'),
('iws_endpoint_catalog_control', 'PRIMARY', 0, 1, 'singleton_key', NULL, 'BTREE'),
('iws_endpoint_audit', 'PRIMARY', 0, 1, 'audit_id', NULL, 'BTREE'),
('iws_endpoint_audit', 'uk_iws_endpoint_audit_command', 0, 1, 'command_key', NULL, 'BTREE'),
('iws_endpoint_audit', 'idx_iws_endpoint_audit_object', 1, 1, 'object_type', NULL, 'BTREE'),
('iws_endpoint_audit', 'idx_iws_endpoint_audit_object', 1, 2, 'object_key', NULL, 'BTREE'),
('iws_endpoint_audit', 'idx_iws_endpoint_audit_object', 1, 3, 'audit_id', NULL, 'BTREE'),
('iws_endpoint_audit', 'idx_iws_endpoint_audit_environment', 1, 1, 'environment_code', NULL, 'BTREE'),
('iws_endpoint_audit', 'idx_iws_endpoint_audit_environment', 1, 2, 'audit_id', NULL, 'BTREE'),
('iws_endpoint_audit', 'idx_iws_endpoint_audit_operator', 1, 1, 'operator_name', NULL, 'BTREE'),
('iws_endpoint_audit', 'idx_iws_endpoint_audit_operator', 1, 2, 'audit_id', NULL, 'BTREE');

-- 预检只比较已存在表；缺表由后续门控创建，已存在但定义不同则禁止任何创建。
-- 大小写冲突条目保留；其余检查追加。

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, '表不是 InnoDB 基础表或排序规则不一致'
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
WHERE i.TABLE_TYPE <> 'BASE TABLE'
   OR i.ENGINE <> 'InnoDB'
   OR NOT (i.TABLE_COLLATION <=> 'utf8mb4_general_ci');

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('列定义不匹配：', e.column_name)
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN tmp_iws_ep_expected_columns e ON e.table_name = t.table_name
LEFT JOIN information_schema.COLUMNS c
  ON c.TABLE_SCHEMA = @upgrade_schema AND c.TABLE_NAME = t.table_name AND c.COLUMN_NAME = e.column_name
WHERE c.COLUMN_NAME IS NULL
   OR c.ORDINAL_POSITION <> e.ordinal_position
   OR NOT (c.COLUMN_TYPE <=> e.column_type)
   OR NOT (c.IS_NULLABLE <=> e.is_nullable)
   OR NOT (c.COLUMN_DEFAULT <=> e.column_default)
   OR NOT (c.EXTRA <=> e.extra)
   OR NOT (c.CHARACTER_SET_NAME <=> e.character_set_name)
   OR NOT (c.COLLATION_NAME <=> e.collation_name);

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('存在未登记列：', c.COLUMN_NAME)
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN information_schema.COLUMNS c
  ON c.TABLE_SCHEMA = @upgrade_schema AND c.TABLE_NAME = t.table_name
LEFT JOIN tmp_iws_ep_expected_columns e
  ON e.table_name = t.table_name AND e.column_name = c.COLUMN_NAME
WHERE e.column_name IS NULL;

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('索引定义不匹配：', e.index_name)
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN tmp_iws_ep_expected_indexes e ON e.table_name = t.table_name
LEFT JOIN information_schema.STATISTICS s
  ON s.TABLE_SCHEMA = @upgrade_schema AND s.TABLE_NAME = t.table_name
 AND s.INDEX_NAME = e.index_name AND s.SEQ_IN_INDEX = e.seq_in_index
WHERE s.INDEX_NAME IS NULL
   OR s.NON_UNIQUE <> e.non_unique
   OR NOT (s.COLUMN_NAME <=> e.column_name)
   OR NOT (s.SUB_PART <=> e.sub_part)
   OR NOT (s.INDEX_TYPE <=> e.index_type);

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('存在未登记索引：', s.INDEX_NAME)
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN information_schema.STATISTICS s
  ON s.TABLE_SCHEMA = @upgrade_schema AND s.TABLE_NAME = t.table_name
LEFT JOIN tmp_iws_ep_expected_indexes e
  ON e.table_name = t.table_name AND e.index_name = s.INDEX_NAME AND e.seq_in_index = s.SEQ_IN_INDEX
WHERE e.index_name IS NULL;

SELECT COUNT(*) INTO @ep_conflicts FROM tmp_iws_ep_conflicts;
SET @ep_preflight_ok = IF(@ep_precheck_ok = 1 AND @ep_conflicts = 0, 1, 0);
SET @ep_ddl_chain_ok = @ep_preflight_ok;

SELECT `表名`, `冲突说明`
FROM (
    SELECT table_name AS `表名`, reason AS `冲突说明`, 1 AS sort_no FROM tmp_iws_ep_conflicts
    UNION ALL
    SELECT '--', '无结构冲突', 2
) detail
WHERE (@ep_conflicts > 0 AND sort_no = 1)
   OR (@ep_conflicts = 0 AND sort_no = 2)
ORDER BY `表名`, `冲突说明`;

SELECT JSON_EXTRACT(IF(@ep_preflight_ok = 1, '{}', 'invalid'), '$') AS `预检结果`;

-- 建表：表缺失且预检通过时才执行；按依赖顺序创建，不建立物理外键。
SET @ep_table_exists = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_system'
);
SET @ep_ddl = IF(@ep_ddl_chain_ok = 1 AND @ep_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_endpoint_system` (',
    '`system_code` varchar(64) NOT NULL,',
    '`display_name` varchar(128) NOT NULL,',
    '`description` varchar(500) DEFAULT NULL,',
    '`enabled` tinyint(1) NOT NULL,',
    '`revision` bigint NOT NULL,',
    '`create_by` bigint DEFAULT NULL,',
    '`create_time` datetime NOT NULL,',
    '`update_by` bigint DEFAULT NULL,',
    '`update_time` datetime NOT NULL,',
    'PRIMARY KEY (`system_code`),',
    'KEY `idx_iws_endpoint_system_page` (`enabled`, `update_time`, `system_code`)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_ddl_chain_ok = IF(@ep_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_system'), 1, 0);
SET @ep_table_exists = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_environment'
);
SET @ep_ddl = IF(@ep_ddl_chain_ok = 1 AND @ep_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_endpoint_environment` (',
    '`environment_code` varchar(64) NOT NULL,',
    '`display_name` varchar(128) NOT NULL,',
    '`description` varchar(500) DEFAULT NULL,',
    '`enabled` tinyint(1) NOT NULL,',
    '`revision` bigint NOT NULL,',
    '`create_by` bigint DEFAULT NULL,',
    '`create_time` datetime NOT NULL,',
    '`update_by` bigint DEFAULT NULL,',
    '`update_time` datetime NOT NULL,',
    'PRIMARY KEY (`environment_code`),',
    'KEY `idx_iws_endpoint_environment_page` (`enabled`, `update_time`, `environment_code`)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_ddl_chain_ok = IF(@ep_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_environment'), 1, 0);
SET @ep_table_exists = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_address'
);
SET @ep_ddl = IF(@ep_ddl_chain_ok = 1 AND @ep_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_endpoint_address` (',
    '`address_id` bigint NOT NULL AUTO_INCREMENT,',
    '`environment_code` varchar(64) NOT NULL,',
    '`endpoint_ref` varchar(64) NOT NULL,',
    '`protocol` varchar(8) NOT NULL,',
    '`host` varchar(253) NOT NULL,',
    '`port` smallint unsigned DEFAULT NULL,',
    '`base_path` varchar(512) NOT NULL,',
    '`credential_ref` varchar(128) DEFAULT NULL,',
    '`enabled` tinyint(1) NOT NULL,',
    '`revision` bigint NOT NULL,',
    '`create_by` bigint DEFAULT NULL,',
    '`create_time` datetime NOT NULL,',
    '`update_by` bigint DEFAULT NULL,',
    '`update_time` datetime NOT NULL,',
    'PRIMARY KEY (`address_id`),',
    'UNIQUE KEY `uk_iws_endpoint_address_ref` (`environment_code`, `endpoint_ref`),',
    'KEY `idx_iws_endpoint_address_page` (`environment_code`, `enabled`, `update_time`, `address_id`)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_ddl_chain_ok = IF(@ep_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_address'), 1, 0);
SET @ep_table_exists = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_system_address'
);
SET @ep_ddl = IF(@ep_ddl_chain_ok = 1 AND @ep_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_endpoint_system_address` (',
    '`relation_id` bigint NOT NULL AUTO_INCREMENT,',
    '`system_code` varchar(64) NOT NULL,',
    '`address_id` bigint NOT NULL,',
    '`enabled` tinyint(1) NOT NULL,',
    '`revision` bigint NOT NULL,',
    '`create_by` bigint DEFAULT NULL,',
    '`create_time` datetime NOT NULL,',
    '`update_by` bigint DEFAULT NULL,',
    '`update_time` datetime NOT NULL,',
    'PRIMARY KEY (`relation_id`),',
    'UNIQUE KEY `uk_iws_endpoint_system_address` (`system_code`, `address_id`),',
    'KEY `idx_iws_endpoint_system_address_addr` (`address_id`, `enabled`)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_ddl_chain_ok = IF(@ep_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_system_address'), 1, 0);
SET @ep_table_exists = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_catalog_control'
);
SET @ep_ddl = IF(@ep_ddl_chain_ok = 1 AND @ep_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_endpoint_catalog_control` (',
    '`singleton_key` varchar(16) NOT NULL,',
    '`revision` bigint NOT NULL,',
    '`update_time` datetime NOT NULL,',
    'PRIMARY KEY (`singleton_key`)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_ddl_chain_ok = IF(@ep_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_catalog_control'), 1, 0);
SET @ep_table_exists = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_audit'
);
SET @ep_ddl = IF(@ep_ddl_chain_ok = 1 AND @ep_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_endpoint_audit` (',
    '`audit_id` bigint NOT NULL AUTO_INCREMENT,',
    '`object_type` varchar(32) NOT NULL,',
    '`object_key` varchar(128) NOT NULL,',
    '`environment_code` varchar(64) DEFAULT NULL,',
    '`action` varchar(32) NOT NULL,',
    '`before_summary` text DEFAULT NULL,',
    '`after_summary` text DEFAULT NULL,',
    '`operator_id` bigint DEFAULT NULL,',
    '`operator_name` varchar(128) NOT NULL,',
    '`command_key` varchar(128) NOT NULL,',
    '`request_hash` varchar(64) NOT NULL,',
    '`reason` varchar(500) NOT NULL,',
    '`catalog_revision` bigint NOT NULL,',
    '`result_revision` bigint NOT NULL,',
    '`result_object_key` varchar(128) NOT NULL,',
    '`create_time` datetime NOT NULL,',
    'PRIMARY KEY (`audit_id`),',
    'UNIQUE KEY `uk_iws_endpoint_audit_command` (`command_key`),',
    'KEY `idx_iws_endpoint_audit_object` (`object_type`, `object_key`, `audit_id`),',
    'KEY `idx_iws_endpoint_audit_environment` (`environment_code`, `audit_id`),',
    'KEY `idx_iws_endpoint_audit_operator` (`operator_name`, `audit_id`)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_ddl_chain_ok = IF(@ep_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_audit'), 1, 0);
-- 后置结构核验：重复比较，并额外要求 6 张表全部存在。
DELETE FROM tmp_iws_ep_conflicts;

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, '表缺失'
FROM tmp_iws_ep_expected_tables t
LEFT JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
WHERE i.TABLE_NAME IS NULL;

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, '表不是 InnoDB 基础表或排序规则不一致'
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
WHERE i.TABLE_TYPE <> 'BASE TABLE'
   OR i.ENGINE <> 'InnoDB'
   OR NOT (i.TABLE_COLLATION <=> 'utf8mb4_general_ci');

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('列定义不匹配：', e.column_name)
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN tmp_iws_ep_expected_columns e ON e.table_name = t.table_name
LEFT JOIN information_schema.COLUMNS c
  ON c.TABLE_SCHEMA = @upgrade_schema AND c.TABLE_NAME = t.table_name AND c.COLUMN_NAME = e.column_name
WHERE c.COLUMN_NAME IS NULL
   OR c.ORDINAL_POSITION <> e.ordinal_position
   OR NOT (c.COLUMN_TYPE <=> e.column_type)
   OR NOT (c.IS_NULLABLE <=> e.is_nullable)
   OR NOT (c.COLUMN_DEFAULT <=> e.column_default)
   OR NOT (c.EXTRA <=> e.extra)
   OR NOT (c.CHARACTER_SET_NAME <=> e.character_set_name)
   OR NOT (c.COLLATION_NAME <=> e.collation_name);

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('存在未登记列：', c.COLUMN_NAME)
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN information_schema.COLUMNS c
  ON c.TABLE_SCHEMA = @upgrade_schema AND c.TABLE_NAME = t.table_name
LEFT JOIN tmp_iws_ep_expected_columns e
  ON e.table_name = t.table_name AND e.column_name = c.COLUMN_NAME
WHERE e.column_name IS NULL;

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('索引定义不匹配：', e.index_name)
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN tmp_iws_ep_expected_indexes e ON e.table_name = t.table_name
LEFT JOIN information_schema.STATISTICS s
  ON s.TABLE_SCHEMA = @upgrade_schema AND s.TABLE_NAME = t.table_name
 AND s.INDEX_NAME = e.index_name AND s.SEQ_IN_INDEX = e.seq_in_index
WHERE s.INDEX_NAME IS NULL
   OR s.NON_UNIQUE <> e.non_unique
   OR NOT (s.COLUMN_NAME <=> e.column_name)
   OR NOT (s.SUB_PART <=> e.sub_part)
   OR NOT (s.INDEX_TYPE <=> e.index_type);

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('存在未登记索引：', s.INDEX_NAME)
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN information_schema.STATISTICS s
  ON s.TABLE_SCHEMA = @upgrade_schema AND s.TABLE_NAME = t.table_name
LEFT JOIN tmp_iws_ep_expected_indexes e
  ON e.table_name = t.table_name AND e.index_name = s.INDEX_NAME AND e.seq_in_index = s.SEQ_IN_INDEX
WHERE e.index_name IS NULL;

SELECT COUNT(*) INTO @ep_conflicts FROM tmp_iws_ep_conflicts;
SET @ep_post_ok = IF(@ep_ddl_chain_ok = 1 AND @ep_preflight_ok = 1 AND @ep_conflicts = 0, 1, 0);

SELECT `表名`, `冲突说明`
FROM (
    SELECT table_name AS `表名`, reason AS `冲突说明`, 1 AS sort_no FROM tmp_iws_ep_conflicts
    UNION ALL
    SELECT '--', '无结构冲突', 2
) detail
WHERE (@ep_conflicts > 0 AND sort_no = 1)
   OR (@ep_conflicts = 0 AND sort_no = 2)
ORDER BY `表名`, `冲突说明`;

SELECT JSON_EXTRACT(IF(@ep_post_ok = 1, '{}', 'invalid'), '$') AS `后置结构校验`;

-- 唯一目录控制行：只允许 CATALOG 一行，初始 revision=0。
SET @ep_control_count_sql = CONCAT(
    'SELECT COUNT(*) INTO @ep_control_count FROM ', @upgrade_schema_q, '.`iws_endpoint_catalog_control`'
);
SET @ep_ddl = IF(@ep_post_ok = 1, @ep_control_count_sql, 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_control_bad_sql = CONCAT(
    'SELECT COUNT(*) INTO @ep_control_bad FROM ', @upgrade_schema_q,
    '.`iws_endpoint_catalog_control` WHERE `singleton_key` <> ''CATALOG'' OR `revision` < 0'
);
SET @ep_ddl = IF(@ep_post_ok = 1, @ep_control_bad_sql, 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_control_ok = IF(@ep_post_ok = 1 AND COALESCE(@ep_control_count, 0) IN (0, 1)
    AND COALESCE(@ep_control_bad, 0) = 0, 1, 0);

SELECT COUNT(*) AS `控制行矛盾数`
FROM (
    SELECT 1 AS bad WHERE @ep_control_ok = 0
) conflict_rows;

SELECT JSON_EXTRACT(IF(@ep_control_ok = 1, '{}', 'invalid'), '$') AS `目录控制行校验`;

SET @ep_ddl = IF(@ep_control_ok = 1 AND @ep_control_count = 0, CONCAT(
    'INSERT INTO ', @upgrade_schema_q, '.`iws_endpoint_catalog_control`',
    ' (`singleton_key`, `revision`, `update_time`)',
    ' VALUES (''CATALOG'', 0, CURRENT_TIMESTAMP)'
), 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_final_sql = CONCAT(
    'SELECT COUNT(*), COALESCE(MAX(`revision`), -1) INTO @ep_final_count, @ep_final_revision FROM ',
    @upgrade_schema_q, '.`iws_endpoint_catalog_control` WHERE `singleton_key` = ''CATALOG'''
);
SET @ep_ddl = IF(@ep_control_ok = 1, @ep_final_sql, 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_final_ok = IF(@ep_control_ok = 1 AND COALESCE(@ep_final_count, 0) = 1
    AND @ep_final_revision >= 0, 1, 0);

SELECT
    IF(@ep_final_ok = 1, '系统接入配置结构升级完成', '系统接入配置结构升级失败') AS `执行结果`,
    @upgrade_schema AS `目标库`,
    @ep_final_revision AS `目录版本`;

SELECT JSON_EXTRACT(IF(@ep_final_ok = 1, '{}', 'invalid'), '$') AS `最终校验`;

-- 后置验收查询：供执行人独立复核。
SELECT table_name AS `表名`, engine AS `存储引擎`, table_collation AS `排序规则`
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = @upgrade_schema
  AND table_name IN (
      'iws_endpoint_system',
      'iws_endpoint_environment',
      'iws_endpoint_address',
      'iws_endpoint_system_address',
      'iws_endpoint_catalog_control',
      'iws_endpoint_audit'
  )
ORDER BY table_name;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_ep_conflicts;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_ep_expected_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_ep_expected_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_ep_expected_tables;
