/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 应用版本管理
 * @Version      : V1.0
 * @Description  : APK 发布记录表及系统管理菜单升级；目标 MySQL 8.0，可重复执行并核验既有定义；
 * @ModifyRecord : V1.0 新建；
 */

-- 目标：MySQL 8.0；单一目标库参数见下一行。
-- 执行：mysql --database=innolux-iws --default-character-set=utf8mb4 --batch --raw < iws_apk_management.sql
-- 同一连接顺序执行，不添加 --force。预检或验收失败会触发 SQL 错误，后续写入同时受门控。
-- 本脚本只创建 sys_apk_release 和独立菜单，不写业务、endpoint/environment 或角色授权。

SET @apk_schema = 'innolux-iws';
SET @apk_schema_q = CONCAT('`', REPLACE(@apk_schema, '`', '``'), '`');

SET @apk_target_ok = (
    SELECT COUNT(*) = 1 FROM information_schema.SCHEMATA
    WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@apk_schema AS BINARY)
);
SET @apk_database_matches = (
    DATABASE() IS NOT NULL
    AND CAST(DATABASE() AS BINARY) = CAST(@apk_schema AS BINARY)
);
SET @apk_version_ok = (VERSION() LIKE '8.0.%');
SET @apk_base_ok = (
    @apk_target_ok = 1 AND @apk_database_matches = 1 AND @apk_version_ok = 1
);

DROP TEMPORARY TABLE IF EXISTS tmp_iws_apk_cols;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_apk_idxs;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_apk_menu_expected;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_apk_menu_actual;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_apk_menu_post;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_apk_conflicts;

CREATE TEMPORARY TABLE tmp_iws_apk_cols (
    ordinal_position int NOT NULL PRIMARY KEY,
    column_name varchar(64) NOT NULL UNIQUE,
    column_type varchar(255) NOT NULL,
    is_nullable varchar(3) NOT NULL,
    column_default varchar(1024) DEFAULT NULL,
    extra varchar(64) NOT NULL,
    character_set_name varchar(64) DEFAULT NULL,
    collation_name varchar(64) DEFAULT NULL
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TEMPORARY TABLE tmp_iws_apk_idxs (
    index_name varchar(64) NOT NULL,
    non_unique int NOT NULL,
    seq_in_index int NOT NULL,
    column_name varchar(64) NOT NULL,
    sub_part int DEFAULT NULL,
    index_type varchar(16) NOT NULL,
    PRIMARY KEY (index_name, seq_in_index)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TEMPORARY TABLE tmp_iws_apk_menu_expected (
    menu_id bigint NOT NULL PRIMARY KEY,
    natural_key varchar(512) NOT NULL UNIQUE,
    menu_name varchar(50) NOT NULL,
    parent_id bigint NOT NULL,
    order_num int NOT NULL,
    path varchar(200) NOT NULL,
    component varchar(255) DEFAULT NULL,
    query_param varchar(255) DEFAULT NULL,
    is_frame char(1) NOT NULL,
    is_cache char(1) NOT NULL,
    menu_type char(1) NOT NULL,
    visible char(1) NOT NULL,
    status char(1) NOT NULL,
    perms varchar(100) NOT NULL,
    icon varchar(100) NOT NULL,
    active_menu varchar(255) NOT NULL,
    ext varchar(2000) NOT NULL,
    create_dept bigint NOT NULL,
    create_by bigint NOT NULL,
    remark varchar(500) NOT NULL
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TEMPORARY TABLE tmp_iws_apk_menu_actual (
    menu_id bigint NOT NULL,
    menu_name varchar(50) DEFAULT NULL,
    parent_id bigint DEFAULT NULL,
    order_num int DEFAULT NULL,
    path varchar(200) DEFAULT NULL,
    component varchar(255) DEFAULT NULL,
    query_param varchar(255) DEFAULT NULL,
    is_frame char(1) DEFAULT NULL,
    is_cache char(1) DEFAULT NULL,
    menu_type char(1) DEFAULT NULL,
    visible char(1) DEFAULT NULL,
    status char(1) DEFAULT NULL,
    perms varchar(100) DEFAULT NULL,
    icon varchar(100) DEFAULT NULL,
    active_menu varchar(255) DEFAULT NULL,
    ext varchar(2000) DEFAULT NULL,
    KEY idx_tmp_iws_apk_menu_id (menu_id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TEMPORARY TABLE tmp_iws_apk_menu_post LIKE tmp_iws_apk_menu_actual;

CREATE TEMPORARY TABLE tmp_iws_apk_conflicts (
    object_key varchar(128) NOT NULL,
    reason varchar(255) NOT NULL,
    KEY idx_tmp_iws_apk_conflict (object_key)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO tmp_iws_apk_cols
(ordinal_position, column_name, column_type, is_nullable, column_default, extra, character_set_name, collation_name) VALUES
(1,  'release_id',           'bigint',        'NO',  NULL,       '', NULL,      NULL),
(2,  'app_name',             'varchar(200)',  'NO',  NULL,       '', 'utf8mb4', 'utf8mb4_general_ci'),
(3,  'package_name',         'varchar(255)',  'NO',  NULL,       '', 'utf8mb4', 'utf8mb4_bin'),
(4,  'version_name',         'varchar(100)',  'NO',  NULL,       '', 'utf8mb4', 'utf8mb4_general_ci'),
(5,  'version_code',         'bigint',        'NO',  NULL,       '', NULL,      NULL),
(6,  'sha256',               'char(64)',      'NO',  NULL,       '', 'ascii',   'ascii_bin'),
(7,  'file_size',            'bigint',        'NO',  NULL,       '', NULL,      NULL),
(8,  'oss_id',               'bigint',        'NO',  NULL,       '', NULL,      NULL),
(9,  'status',               'varchar(20)',   'NO',  'UPLOADED', '', 'utf8mb4', 'utf8mb4_general_ci'),
(10, 'force_update',         'tinyint(1)',    'NO',  '1',        '', NULL,      NULL),
(11, 'process_instance_id',  'bigint',        'YES', NULL,       '', NULL,      NULL),
(12, 'remark',               'varchar(500)',  'YES', NULL,       '', 'utf8mb4', 'utf8mb4_general_ci'),
(13, 'create_dept',          'bigint',        'YES', NULL,       '', NULL,      NULL),
(14, 'create_by',            'bigint',        'YES', NULL,       '', NULL,      NULL),
(15, 'create_time',          'datetime',      'YES', NULL,       '', NULL,      NULL),
(16, 'update_by',            'bigint',        'YES', NULL,       '', NULL,      NULL),
(17, 'update_time',          'datetime',      'YES', NULL,       '', NULL,      NULL),
(18, 'download_count',       'bigint',        'NO',  '0',        '', NULL,      NULL);

INSERT INTO tmp_iws_apk_idxs
(index_name, non_unique, seq_in_index, column_name, sub_part, index_type) VALUES
('PRIMARY',                 0, 1, 'release_id',   NULL, 'BTREE'),
('uk_apk_package_version',  0, 1, 'package_name', NULL, 'BTREE'),
('uk_apk_package_version',  0, 2, 'version_code', NULL, 'BTREE'),
('idx_apk_name',            1, 1, 'app_name',     NULL, 'BTREE'),
('idx_apk_published',       1, 1, 'package_name', NULL, 'BTREE'),
('idx_apk_published',       1, 2, 'status',       NULL, 'BTREE'),
('idx_apk_published',       1, 3, 'version_code', NULL, 'BTREE');

INSERT INTO tmp_iws_apk_menu_expected
(menu_id, natural_key, menu_name, parent_id, order_num, path, component, query_param,
 is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext,
 create_dept, create_by, remark) VALUES
(1761400000000040000, 'C:1761400000000000001:apk', 'APK管理',
 1761400000000000001, 12, 'apk', 'system/apk/index', '',
 'N', 'Y', 'C', '0', '0', '', 'upload', '', '',
 1761000000000000103, 1761100000000000001, 'APK发布管理菜单'),
(1761400000000040001, 'F:1761400000000040000:system:apk:list', 'APK查询',
 1761400000000040000, 1, '', NULL, NULL,
 'N', 'Y', 'F', '0', '0', 'system:apk:list', '#', '', '',
 1761000000000000103, 1761100000000000001, ''),
(1761400000000040002, 'F:1761400000000040000:system:apk:upload', 'APK上传',
 1761400000000040000, 2, '', NULL, NULL,
 'N', 'Y', 'F', '0', '0', 'system:apk:upload', '#', '', '',
 1761000000000000103, 1761100000000000001, ''),
(1761400000000040003, 'F:1761400000000040000:system:apk:publish', '发布/初始化',
 1761400000000040000, 3, '', NULL, NULL,
 'N', 'Y', 'F', '0', '0', 'system:apk:publish', '#', '', '',
 1761000000000000103, 1761100000000000001, ''),
(1761400000000040004, 'F:1761400000000040000:system:apk:download', 'APK下载',
 1761400000000040000, 4, '', NULL, NULL,
 'N', 'Y', 'F', '0', '0', 'system:apk:download', '#', '', '',
 1761000000000000103, 1761100000000000001, '');

-- 现有表缺失可创建；同名非表、大小写近似、引擎/排序规则、列或索引与目标不同均拒绝。
SET @apk_named_table_count = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@apk_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('sys_apk_release' AS BINARY)
);
SET @apk_table_ok = (
    SELECT COUNT(*) = 1 FROM information_schema.TABLES
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@apk_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('sys_apk_release' AS BINARY)
      AND CAST(TABLE_TYPE AS BINARY) = CAST('BASE TABLE' AS BINARY)
      AND CAST(ENGINE AS BINARY) = CAST('InnoDB' AS BINARY)
      AND CAST(TABLE_COLLATION AS BINARY) = CAST('utf8mb4_general_ci' AS BINARY)
);
SET @apk_menu_table_ok = (
    SELECT COUNT(*) = 1 FROM information_schema.TABLES
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@apk_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('sys_menu' AS BINARY)
      AND CAST(TABLE_TYPE AS BINARY) = CAST('BASE TABLE' AS BINARY)
);

DELETE FROM tmp_iws_apk_conflicts;
INSERT INTO tmp_iws_apk_conflicts (object_key, reason)
SELECT 'sys_apk_release', CONCAT('存在大小写/近似对象：', i.TABLE_NAME)
FROM information_schema.TABLES i
WHERE CAST(i.TABLE_SCHEMA AS BINARY) = CAST(@apk_schema AS BINARY)
  AND CAST(LOWER(i.TABLE_NAME) AS BINARY) = CAST('sys_apk_release' AS BINARY)
  AND CAST(i.TABLE_NAME AS BINARY) <> CAST('sys_apk_release' AS BINARY);
INSERT INTO tmp_iws_apk_conflicts (object_key, reason)
SELECT 'sys_apk_release', '同名对象存在但不是符合目标的基础表'
FROM information_schema.TABLES i
WHERE CAST(i.TABLE_SCHEMA AS BINARY) = CAST(@apk_schema AS BINARY)
  AND CAST(i.TABLE_NAME AS BINARY) = CAST('sys_apk_release' AS BINARY)
  AND NOT (
      CAST(i.TABLE_TYPE AS BINARY) = CAST('BASE TABLE' AS BINARY)
      AND CAST(i.ENGINE AS BINARY) = CAST('InnoDB' AS BINARY)
      AND CAST(i.TABLE_COLLATION AS BINARY) = CAST('utf8mb4_general_ci' AS BINARY)
  );

INSERT INTO tmp_iws_apk_conflicts (object_key, reason)
SELECT DISTINCT 'sys_apk_release', CONCAT('列定义不一致：', e.column_name)
FROM tmp_iws_apk_cols e
LEFT JOIN information_schema.COLUMNS c
  ON CAST(c.TABLE_SCHEMA AS BINARY) = CAST(@apk_schema AS BINARY)
 AND CAST(c.TABLE_NAME AS BINARY) = CAST('sys_apk_release' AS BINARY)
 AND CAST(c.COLUMN_NAME AS BINARY) = CAST(e.column_name AS BINARY)
WHERE @apk_table_ok = 1
  AND (
      c.COLUMN_NAME IS NULL OR c.ORDINAL_POSITION <> e.ordinal_position
      OR NOT (CAST(c.COLUMN_TYPE AS BINARY) <=> CAST(e.column_type AS BINARY))
      OR NOT (CAST(c.IS_NULLABLE AS BINARY) <=> CAST(e.is_nullable AS BINARY))
      OR NOT (CAST(c.COLUMN_DEFAULT AS BINARY) <=> CAST(e.column_default AS BINARY))
      OR NOT (CAST(c.EXTRA AS BINARY) <=> CAST(e.extra AS BINARY))
      OR NOT (CAST(c.CHARACTER_SET_NAME AS BINARY) <=> CAST(e.character_set_name AS BINARY))
      OR NOT (CAST(c.COLLATION_NAME AS BINARY) <=> CAST(e.collation_name AS BINARY))
  );
SET @apk_actual_column_count = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@apk_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('sys_apk_release' AS BINARY)
);
INSERT INTO tmp_iws_apk_conflicts (object_key, reason)
SELECT 'sys_apk_release', CONCAT('列数量与目标不一致：', @apk_actual_column_count)
WHERE @apk_table_ok = 1 AND @apk_actual_column_count <> 18;

INSERT INTO tmp_iws_apk_conflicts (object_key, reason)
SELECT DISTINCT 'sys_apk_release', CONCAT('索引定义不一致：', e.index_name)
FROM tmp_iws_apk_idxs e
LEFT JOIN information_schema.STATISTICS s
  ON CAST(s.TABLE_SCHEMA AS BINARY) = CAST(@apk_schema AS BINARY)
 AND CAST(s.TABLE_NAME AS BINARY) = CAST('sys_apk_release' AS BINARY)
 AND CAST(s.INDEX_NAME AS BINARY) = CAST(e.index_name AS BINARY)
 AND s.SEQ_IN_INDEX = e.seq_in_index
WHERE @apk_table_ok = 1
  AND (
      s.INDEX_NAME IS NULL OR s.NON_UNIQUE <> e.non_unique
      OR NOT (CAST(s.COLUMN_NAME AS BINARY) <=> CAST(e.column_name AS BINARY))
      OR NOT (s.SUB_PART <=> e.sub_part)
      OR NOT (CAST(s.INDEX_TYPE AS BINARY) <=> CAST(e.index_type AS BINARY))
  );
SET @apk_actual_index_rows = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@apk_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('sys_apk_release' AS BINARY)
);
INSERT INTO tmp_iws_apk_conflicts (object_key, reason)
SELECT 'sys_apk_release', CONCAT('索引列数量与目标不一致：', @apk_actual_index_rows)
WHERE @apk_table_ok = 1 AND @apk_actual_index_rows <> 7;

-- 只加载固定菜单 ID、自然键或目标权限涉及的既有行，用它做真实冲突拒绝。
SET @apk_menu_load_sql = IF(@apk_menu_table_ok = 1, CONCAT(
    'INSERT INTO tmp_iws_apk_menu_actual ',
    '(menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache,',
    ' menu_type, visible, status, perms, icon, active_menu, ext) ',
    'SELECT menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache,',
    ' menu_type, visible, status, perms, icon, active_menu, ext FROM ', @apk_schema_q, '.`sys_menu` ',
    'WHERE menu_id = 1761400000000000001 ',
    'OR menu_id IN (1761400000000040000,1761400000000040001,1761400000000040002,1761400000000040003,1761400000000040004) ',
    'OR (parent_id = 1761400000000000001 AND (CAST(path AS BINARY) = CAST(''apk'' AS BINARY) ',
    'OR CAST(component AS BINARY) = CAST(''system/apk/index'' AS BINARY))) ',
    'OR CAST(perms AS BINARY) IN (CAST(''system:apk:list'' AS BINARY), CAST(''system:apk:upload'' AS BINARY), ',
    'CAST(''system:apk:publish'' AS BINARY), CAST(''system:apk:download'' AS BINARY))'
), 'DO 0');
PREPARE apk_menu_load_stmt FROM @apk_menu_load_sql;
EXECUTE apk_menu_load_stmt;
DEALLOCATE PREPARE apk_menu_load_stmt;

INSERT INTO tmp_iws_apk_conflicts (object_key, reason)
SELECT 'sys_menu', 'sys_menu 基础表缺失'
WHERE @apk_menu_table_ok = 0;
SET @apk_menu_required_column_count = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@apk_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('sys_menu' AS BINARY)
      AND CAST(COLUMN_NAME AS BINARY) IN (
          CAST('menu_id' AS BINARY), CAST('menu_name' AS BINARY), CAST('parent_id' AS BINARY),
          CAST('order_num' AS BINARY), CAST('path' AS BINARY), CAST('component' AS BINARY),
          CAST('query_param' AS BINARY), CAST('is_frame' AS BINARY), CAST('is_cache' AS BINARY),
          CAST('menu_type' AS BINARY), CAST('visible' AS BINARY), CAST('status' AS BINARY),
          CAST('perms' AS BINARY), CAST('icon' AS BINARY), CAST('active_menu' AS BINARY),
          CAST('ext' AS BINARY), CAST('create_dept' AS BINARY), CAST('create_by' AS BINARY),
          CAST('create_time' AS BINARY), CAST('update_by' AS BINARY), CAST('update_time' AS BINARY),
          CAST('remark' AS BINARY)
      )
);
INSERT INTO tmp_iws_apk_conflicts (object_key, reason)
SELECT 'sys_menu', CONCAT('sys_menu 必需列数量不符：', @apk_menu_required_column_count)
WHERE @apk_menu_table_ok = 1 AND @apk_menu_required_column_count <> 22;

INSERT INTO tmp_iws_apk_conflicts (object_key, reason)
SELECT DISTINCT CAST(m.menu_id AS char), '既有菜单ID字段与目标不一致'
FROM tmp_iws_apk_menu_expected e
JOIN tmp_iws_apk_menu_actual m ON m.menu_id = e.menu_id
WHERE NOT (
    CAST(m.menu_name AS BINARY) <=> CAST(e.menu_name AS BINARY) AND m.parent_id <=> e.parent_id
    AND m.order_num <=> e.order_num AND CAST(m.path AS BINARY) <=> CAST(e.path AS BINARY)
    AND CAST(m.component AS BINARY) <=> CAST(e.component AS BINARY)
    AND CAST(m.query_param AS BINARY) <=> CAST(e.query_param AS BINARY)
    AND CAST(m.is_frame AS BINARY) <=> CAST(e.is_frame AS BINARY)
    AND CAST(m.is_cache AS BINARY) <=> CAST(e.is_cache AS BINARY)
    AND CAST(m.menu_type AS BINARY) <=> CAST(e.menu_type AS BINARY)
    AND CAST(m.visible AS BINARY) <=> CAST(e.visible AS BINARY)
    AND CAST(m.status AS BINARY) <=> CAST(e.status AS BINARY)
    AND CAST(m.perms AS BINARY) <=> CAST(e.perms AS BINARY)
    AND CAST(m.icon AS BINARY) <=> CAST(e.icon AS BINARY)
    AND CAST(m.active_menu AS BINARY) <=> CAST(e.active_menu AS BINARY)
    AND CAST(m.ext AS BINARY) <=> CAST(e.ext AS BINARY)
);
INSERT INTO tmp_iws_apk_conflicts (object_key, reason)
SELECT DISTINCT CAST(e.menu_id AS char), CONCAT('自然键已被其他菜单占用：', e.natural_key)
FROM tmp_iws_apk_menu_expected e
JOIN tmp_iws_apk_menu_actual m ON m.menu_id <> e.menu_id
WHERE (e.menu_type = 'C' AND m.parent_id = e.parent_id AND CAST(m.path AS BINARY) = CAST(e.path AS BINARY))
   OR (e.menu_type = 'C' AND CAST(m.component AS BINARY) = CAST(e.component AS BINARY))
   OR (e.menu_type = 'F' AND CAST(m.perms AS BINARY) = CAST(e.perms AS BINARY));
INSERT INTO tmp_iws_apk_conflicts (object_key, reason)
SELECT '1761400000000000001', '系统管理目录缺失或定义不一致'
WHERE NOT EXISTS (
    SELECT 1 FROM tmp_iws_apk_menu_actual m
    WHERE m.menu_id = 1761400000000000001
      AND CAST(m.menu_name AS BINARY) = CAST('系统管理' AS BINARY)
      AND CAST(m.path AS BINARY) = CAST('system' AS BINARY)
      AND CAST(m.menu_type AS BINARY) = CAST('M' AS BINARY)
);

SELECT COUNT(*) INTO @apk_preflight_conflicts FROM tmp_iws_apk_conflicts;
SET @apk_preflight_ok = IF(@apk_base_ok = 1 AND @apk_preflight_conflicts = 0, 1, 0);
SELECT
    @apk_target_ok AS '目标库存在',
    @apk_database_matches AS '当前连接库一致',
    @apk_version_ok AS 'MySQL8版本',
    IF(@apk_preflight_ok = 1, '前置校验通过', '前置校验失败，禁止写入') AS '执行说明',
    @apk_schema AS '目标库';
SELECT object_key AS '对象', reason AS '冲突说明' FROM tmp_iws_apk_conflicts ORDER BY object_key, reason;
-- 这条 SELECT 故意在前置失败时触发 JSON 错误；后续所有持久化语句仍由 @apk_preflight_ok 门控。
SELECT JSON_EXTRACT(IF(@apk_preflight_ok = 1, '{}', 'invalid'), '$') AS 'APK升级预检';

SET @apk_create_sql = IF(@apk_preflight_ok = 1 AND @apk_named_table_count = 0, CONCAT(
    'CREATE TABLE ', @apk_schema_q, '.`sys_apk_release` (',
    '`release_id` bigint NOT NULL,',
    '`app_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,',
    '`package_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,',
    '`version_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,',
    '`version_code` bigint NOT NULL,',
    '`sha256` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,',
    '`file_size` bigint NOT NULL,',
    '`oss_id` bigint NOT NULL,',
    '`status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT ''UPLOADED'',',
    '`force_update` tinyint(1) NOT NULL DEFAULT 1,',
    '`process_instance_id` bigint DEFAULT NULL,',
    '`remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,',
    '`create_dept` bigint DEFAULT NULL,',
    '`create_by` bigint DEFAULT NULL,',
    '`create_time` datetime DEFAULT NULL,',
    '`update_by` bigint DEFAULT NULL,',
    '`update_time` datetime DEFAULT NULL,',
    '`download_count` bigint NOT NULL DEFAULT 0,',
    'PRIMARY KEY (`release_id`),',
    'UNIQUE KEY `uk_apk_package_version` (`package_name`,`version_code`),',
    'KEY `idx_apk_name` (`app_name`),',
    'KEY `idx_apk_published` (`package_name`,`status`,`version_code`)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE apk_create_stmt FROM @apk_create_sql;
EXECUTE apk_create_stmt;
DEALLOCATE PREPARE apk_create_stmt;

SET @apk_table_present = (
    SELECT COUNT(*) = 1 FROM information_schema.TABLES
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@apk_schema AS BINARY)
      AND CAST(TABLE_NAME AS BINARY) = CAST('sys_apk_release' AS BINARY)
      AND CAST(TABLE_TYPE AS BINARY) = CAST('BASE TABLE' AS BINARY)
);
SET @apk_table_chain_ok = IF(@apk_preflight_ok = 1 AND @apk_table_present = 1, 1, 0);

-- 对新建表做同目标的精简后置核验，失败时后续菜单不写入。
SET @apk_post_column_mismatch = (
    SELECT COUNT(*) FROM tmp_iws_apk_cols e
    LEFT JOIN information_schema.COLUMNS c
      ON CAST(c.TABLE_SCHEMA AS BINARY) = CAST(@apk_schema AS BINARY)
     AND CAST(c.TABLE_NAME AS BINARY) = CAST('sys_apk_release' AS BINARY)
     AND CAST(c.COLUMN_NAME AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE c.COLUMN_NAME IS NULL OR c.ORDINAL_POSITION <> e.ordinal_position
       OR NOT (CAST(c.COLUMN_TYPE AS BINARY) <=> CAST(e.column_type AS BINARY))
       OR NOT (CAST(c.IS_NULLABLE AS BINARY) <=> CAST(e.is_nullable AS BINARY))
       OR NOT (CAST(c.COLUMN_DEFAULT AS BINARY) <=> CAST(e.column_default AS BINARY))
       OR NOT (CAST(c.EXTRA AS BINARY) <=> CAST(e.extra AS BINARY))
       OR NOT (CAST(c.CHARACTER_SET_NAME AS BINARY) <=> CAST(e.character_set_name AS BINARY))
       OR NOT (CAST(c.COLLATION_NAME AS BINARY) <=> CAST(e.collation_name AS BINARY))
);
SET @apk_post_index_mismatch = (
    SELECT COUNT(*) FROM tmp_iws_apk_idxs e
    LEFT JOIN information_schema.STATISTICS s
      ON CAST(s.TABLE_SCHEMA AS BINARY) = CAST(@apk_schema AS BINARY)
     AND CAST(s.TABLE_NAME AS BINARY) = CAST('sys_apk_release' AS BINARY)
     AND CAST(s.INDEX_NAME AS BINARY) = CAST(e.index_name AS BINARY)
     AND s.SEQ_IN_INDEX = e.seq_in_index
    WHERE s.INDEX_NAME IS NULL OR s.NON_UNIQUE <> e.non_unique
       OR NOT (CAST(s.COLUMN_NAME AS BINARY) <=> CAST(e.column_name AS BINARY))
       OR NOT (s.SUB_PART <=> e.sub_part)
       OR NOT (CAST(s.INDEX_TYPE AS BINARY) <=> CAST(e.index_type AS BINARY))
);
SET @apk_table_post_ok = IF(
    @apk_table_chain_ok = 1 AND @apk_post_column_mismatch = 0 AND @apk_post_index_mismatch = 0,
    1, 0
);
SELECT 'sys_apk_release' AS '对象',
       @apk_post_column_mismatch AS '列差异数', @apk_post_index_mismatch AS '索引差异数';
SELECT JSON_EXTRACT(IF(@apk_table_post_ok = 1, '{}', 'invalid'), '$') AS 'APK表后置校验';

-- 只补缺失的固定菜单 ID；既有同定义行不动。
SET @apk_menu_insert_sql = IF(@apk_table_post_ok = 1, CONCAT(
    'INSERT INTO ', @apk_schema_q, '.`sys_menu` ',
    '(menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache,',
    ' menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, remark) ',
    'SELECT e.menu_id, e.menu_name, e.parent_id, e.order_num, e.path, e.component, e.query_param,',
    ' e.is_frame, e.is_cache, e.menu_type, e.visible, e.status, e.perms, e.icon, e.active_menu, e.ext,',
    ' e.create_dept, e.create_by, CURRENT_TIMESTAMP, e.remark FROM tmp_iws_apk_menu_expected e ',
    'WHERE NOT EXISTS (SELECT 1 FROM ', @apk_schema_q, '.`sys_menu` m WHERE m.menu_id = e.menu_id) ',
    'ORDER BY e.menu_id'
), 'DO 0');
PREPARE apk_menu_insert_stmt FROM @apk_menu_insert_sql;
EXECUTE apk_menu_insert_stmt;
DEALLOCATE PREPARE apk_menu_insert_stmt;

DELETE FROM tmp_iws_apk_menu_post;
SET @apk_menu_post_load_sql = IF(@apk_menu_table_ok = 1, CONCAT(
    'INSERT INTO tmp_iws_apk_menu_post ',
    '(menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache,',
    ' menu_type, visible, status, perms, icon, active_menu, ext) ',
    'SELECT menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache,',
    ' menu_type, visible, status, perms, icon, active_menu, ext FROM ', @apk_schema_q, '.`sys_menu` ',
    'WHERE menu_id = 1761400000000000001 ',
    'OR menu_id IN (1761400000000040000,1761400000000040001,1761400000000040002,1761400000000040003,1761400000000040004) ',
    'OR (parent_id = 1761400000000000001 AND (CAST(path AS BINARY) = CAST(''apk'' AS BINARY) ',
    'OR CAST(component AS BINARY) = CAST(''system/apk/index'' AS BINARY))) ',
    'OR CAST(perms AS BINARY) IN (CAST(''system:apk:list'' AS BINARY), CAST(''system:apk:upload'' AS BINARY), ',
    'CAST(''system:apk:publish'' AS BINARY), CAST(''system:apk:download'' AS BINARY))'
), 'DO 0');
PREPARE apk_menu_post_load_stmt FROM @apk_menu_post_load_sql;
EXECUTE apk_menu_post_load_stmt;
DEALLOCATE PREPARE apk_menu_post_load_stmt;

DELETE FROM tmp_iws_apk_conflicts;
INSERT INTO tmp_iws_apk_conflicts (object_key, reason)
SELECT CAST(e.menu_id AS char), '菜单写入后仍缺失'
FROM tmp_iws_apk_menu_expected e
LEFT JOIN tmp_iws_apk_menu_post p ON p.menu_id = e.menu_id
WHERE p.menu_id IS NULL;
INSERT INTO tmp_iws_apk_conflicts (object_key, reason)
SELECT CAST(e.menu_id AS char), '菜单写入后字段不一致'
FROM tmp_iws_apk_menu_expected e
JOIN tmp_iws_apk_menu_post m ON m.menu_id = e.menu_id
WHERE NOT (
    CAST(m.menu_name AS BINARY) <=> CAST(e.menu_name AS BINARY) AND m.parent_id <=> e.parent_id
    AND m.order_num <=> e.order_num AND CAST(m.path AS BINARY) <=> CAST(e.path AS BINARY)
    AND CAST(m.component AS BINARY) <=> CAST(e.component AS BINARY)
    AND CAST(m.query_param AS BINARY) <=> CAST(e.query_param AS BINARY)
    AND CAST(m.is_frame AS BINARY) <=> CAST(e.is_frame AS BINARY)
    AND CAST(m.is_cache AS BINARY) <=> CAST(e.is_cache AS BINARY)
    AND CAST(m.menu_type AS BINARY) <=> CAST(e.menu_type AS BINARY)
    AND CAST(m.visible AS BINARY) <=> CAST(e.visible AS BINARY)
    AND CAST(m.status AS BINARY) <=> CAST(e.status AS BINARY)
    AND CAST(m.perms AS BINARY) <=> CAST(e.perms AS BINARY)
    AND CAST(m.icon AS BINARY) <=> CAST(e.icon AS BINARY)
    AND CAST(m.active_menu AS BINARY) <=> CAST(e.active_menu AS BINARY)
    AND CAST(m.ext AS BINARY) <=> CAST(e.ext AS BINARY)
);
INSERT INTO tmp_iws_apk_conflicts (object_key, reason)
SELECT DISTINCT CAST(e.menu_id AS char), CONCAT('菜单自然键发生重复：', e.natural_key)
FROM tmp_iws_apk_menu_expected e
JOIN tmp_iws_apk_menu_post m ON m.menu_id <> e.menu_id
WHERE (e.menu_type = 'C' AND m.parent_id = e.parent_id AND CAST(m.path AS BINARY) = CAST(e.path AS BINARY))
   OR (e.menu_type = 'C' AND CAST(m.component AS BINARY) = CAST(e.component AS BINARY))
   OR (e.menu_type = 'F' AND CAST(m.perms AS BINARY) = CAST(e.perms AS BINARY));
INSERT INTO tmp_iws_apk_conflicts (object_key, reason)
SELECT '1761400000000000001', '系统管理父目录缺失或不是目录'
WHERE NOT EXISTS (
    SELECT 1 FROM tmp_iws_apk_menu_post p
    WHERE p.menu_id = 1761400000000000001 AND CAST(p.menu_type AS BINARY) = CAST('M' AS BINARY)
);
SELECT COUNT(*) INTO @apk_menu_post_conflicts FROM tmp_iws_apk_conflicts;
SET @apk_menu_post_ok = IF(
    @apk_table_post_ok = 1 AND @apk_menu_table_ok = 1 AND @apk_menu_post_conflicts = 0,
    1, 0
);
SELECT object_key AS '对象', reason AS '冲突说明' FROM tmp_iws_apk_conflicts ORDER BY object_key, reason;
SELECT JSON_EXTRACT(IF(@apk_menu_post_ok = 1, '{}', 'invalid'), '$') AS 'APK菜单后置校验';
SELECT
    IF(@apk_menu_post_ok = 1, 'APK 发布表与菜单升级完成', 'APK 发布表与菜单升级失败') AS '执行结果',
    @apk_schema AS '目标库',
    (SELECT COUNT(*) FROM tmp_iws_apk_cols) AS '目标列数',
    (SELECT COUNT(*) FROM tmp_iws_apk_idxs) AS '目标索引列数',
    (SELECT COUNT(*) FROM tmp_iws_apk_menu_expected) AS '本次菜单行数';

DROP TEMPORARY TABLE IF EXISTS tmp_iws_apk_menu_post;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_apk_menu_actual;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_apk_menu_expected;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_apk_conflicts;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_apk_idxs;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_apk_cols;
