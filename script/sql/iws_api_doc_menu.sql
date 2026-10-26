/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 接口文档菜单升级；目标MySQL 8.0，可重复执行并核验既有菜单；
 * @ModifyRecord : V1.0 新建；
 */

-- 目标：MySQL 8.0；单一目标库参数见下一行。
-- 本脚本只写 sys_menu 一行，不向任何角色授予菜单，不签发真实权限。
-- 菜单页面由前端 monitor/apiDoc/index 承载。

SET NAMES utf8mb4;
SET @upgrade_schema = 'innolux-iws';
SET @upgrade_schema_q = CONCAT('`', REPLACE(@upgrade_schema, '`', '``'), '`');

SET @menu_target_ok = (SELECT COUNT(*) = 1 FROM information_schema.SCHEMATA WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@upgrade_schema AS BINARY));
SET @menu_database_matches = (DATABASE() IS NOT NULL AND CAST(DATABASE() AS BINARY) = CAST(@upgrade_schema AS BINARY));
SET @menu_version_ok = (VERSION() LIKE '8.0.%');
SET @menu_schema_collation_ok = (SELECT COUNT(*) = 1 FROM information_schema.SCHEMATA WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@upgrade_schema AS BINARY) AND DEFAULT_COLLATION_NAME = 'utf8mb4_general_ci');
SET @menu_base_ok = (@menu_target_ok = 1 AND @menu_database_matches = 1 AND @menu_version_ok = 1 AND @menu_schema_collation_ok = 1);
SET @menu_table_ok = (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@upgrade_schema AS BINARY) AND CAST(TABLE_NAME AS BINARY) = CAST('sys_menu' AS BINARY) AND TABLE_TYPE = 'BASE TABLE');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_api_doc_menu_post;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_api_doc_menu_actual;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_api_doc_menu_conflicts;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_api_doc_menu_expected;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_api_doc_menu_columns;

CREATE TEMPORARY TABLE tmp_iws_api_doc_menu_columns (
    column_name varchar(64) NOT NULL,
    column_type varchar(255) NOT NULL,
    is_nullable varchar(3) NOT NULL,
    PRIMARY KEY (column_name)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TEMPORARY TABLE tmp_iws_api_doc_menu_expected (
    menu_id bigint NOT NULL PRIMARY KEY,
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

CREATE TEMPORARY TABLE tmp_iws_api_doc_menu_actual (
    menu_id bigint NOT NULL,
    menu_name varchar(50) NOT NULL,
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
    create_dept bigint DEFAULT NULL,
    create_by bigint DEFAULT NULL,
    create_time datetime DEFAULT NULL,
    update_by bigint DEFAULT NULL,
    update_time datetime DEFAULT NULL,
    remark varchar(500) DEFAULT NULL,
    KEY idx_tmp_iws_api_doc_menu_actual_id (menu_id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TEMPORARY TABLE tmp_iws_api_doc_menu_post LIKE tmp_iws_api_doc_menu_actual;
CREATE TEMPORARY TABLE tmp_iws_api_doc_menu_conflicts (
    object_key varchar(128) NOT NULL,
    reason varchar(255) NOT NULL
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO tmp_iws_api_doc_menu_columns VALUES
('menu_id','bigint','NO'),('menu_name','varchar(50)','NO'),('parent_id','bigint','YES'),('order_num','int','YES'),
('path','varchar(200)','YES'),('component','varchar(255)','YES'),('query_param','varchar(255)','YES'),
('is_frame','char(1)','YES'),('is_cache','char(1)','YES'),('menu_type','char(1)','YES'),('visible','char(1)','YES'),
('status','char(1)','YES'),('perms','varchar(100)','YES'),('icon','varchar(100)','YES'),('active_menu','varchar(255)','YES'),
('ext','varchar(2000)','YES'),('create_dept','bigint','YES'),('create_by','bigint','YES'),('create_time','datetime','YES'),
('update_by','bigint','YES'),('update_time','datetime','YES'),('remark','varchar(500)','YES');

INSERT INTO tmp_iws_api_doc_menu_expected
(menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, remark) VALUES
(1761400000000000132, '接口文档', 1761400000000000003, 9, 'api-doc', 'monitor/apiDoc/index', '', 'N', 'Y', 'C', '0', '0', 'monitor:apidoc:list', 'documentation', '', '', 1761000000000000103, 1761100000000000001, '接口文档菜单');

SELECT COUNT(*) INTO @menu_column_conflicts
FROM tmp_iws_api_doc_menu_columns e
LEFT JOIN information_schema.COLUMNS c
  ON CAST(c.TABLE_SCHEMA AS BINARY) = CAST(@upgrade_schema AS BINARY)
 AND CAST(c.TABLE_NAME AS BINARY) = CAST('sys_menu' AS BINARY)
 AND c.COLUMN_NAME = e.column_name
WHERE c.COLUMN_NAME IS NULL
   OR NOT (c.COLUMN_TYPE <=> e.column_type)
   OR NOT (c.IS_NULLABLE <=> e.is_nullable);

SET @menu_pk_ok = (SELECT COUNT(*) = 1 FROM information_schema.STATISTICS s
    WHERE CAST(s.TABLE_SCHEMA AS BINARY) = CAST(@upgrade_schema AS BINARY)
      AND CAST(s.TABLE_NAME AS BINARY) = CAST('sys_menu' AS BINARY)
      AND s.INDEX_NAME = 'PRIMARY' AND s.SEQ_IN_INDEX = 1
      AND s.COLUMN_NAME = 'menu_id');
SET @menu_schema_ok = IF(@menu_base_ok = 1 AND @menu_table_ok = 1 AND @menu_column_conflicts = 0 AND @menu_pk_ok = 1, 1, 0);

SET @menu_load_sql = IF(@menu_schema_ok = 1, CONCAT(
    'INSERT INTO tmp_iws_api_doc_menu_actual (menu_id, menu_name, parent_id, order_num, path, component, query_param,',
    ' is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by,',
    ' create_time, update_by, update_time, remark) ',
    'SELECT menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache,',
    ' menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time,',
    ' update_by, update_time, remark FROM ', @upgrade_schema_q, '.`sys_menu`'
), 'DO 0');
PREPARE menu_preflight_load_stmt FROM @menu_load_sql;
EXECUTE menu_preflight_load_stmt;
DEALLOCATE PREPARE menu_preflight_load_stmt;

DELETE FROM tmp_iws_api_doc_menu_conflicts;
INSERT INTO tmp_iws_api_doc_menu_conflicts (object_key, reason)
SELECT '目标库', '目标库不存在、当前连接库不匹配、不是 MySQL 8.0 或排序规则不是 utf8mb4_general_ci'
WHERE @menu_base_ok = 0;
INSERT INTO tmp_iws_api_doc_menu_conflicts (object_key, reason)
SELECT e.column_name, 'sys_menu 必需列缺失或类型不符'
FROM tmp_iws_api_doc_menu_columns e
LEFT JOIN information_schema.COLUMNS c
  ON CAST(c.TABLE_SCHEMA AS BINARY) = CAST(@upgrade_schema AS BINARY)
 AND CAST(c.TABLE_NAME AS BINARY) = CAST('sys_menu' AS BINARY)
 AND c.COLUMN_NAME = e.column_name
WHERE c.COLUMN_NAME IS NULL
   OR NOT (c.COLUMN_TYPE <=> e.column_type)
   OR NOT (c.IS_NULLABLE <=> e.is_nullable);
INSERT INTO tmp_iws_api_doc_menu_conflicts (object_key, reason)
SELECT 'sys_menu', '不存在基础表或缺少 menu_id 主键索引'
WHERE @menu_table_ok = 0 OR @menu_pk_ok = 0;
INSERT INTO tmp_iws_api_doc_menu_conflicts (object_key, reason)
SELECT CAST(e.menu_id AS char), '同 ID 菜单字段与目标不一致'
FROM tmp_iws_api_doc_menu_expected e
JOIN tmp_iws_api_doc_menu_actual a ON a.menu_id = e.menu_id
WHERE @menu_schema_ok = 1
  AND NOT (a.menu_name <=> e.menu_name AND a.parent_id <=> e.parent_id AND a.order_num <=> e.order_num
    AND a.path <=> e.path AND a.component <=> e.component AND a.query_param <=> e.query_param
    AND a.is_frame <=> e.is_frame AND a.is_cache <=> e.is_cache AND a.menu_type <=> e.menu_type
    AND a.visible <=> e.visible AND a.status <=> e.status AND a.perms <=> e.perms
    AND a.icon <=> e.icon AND a.active_menu <=> e.active_menu AND a.ext <=> e.ext
    AND a.create_dept <=> e.create_dept AND a.create_by <=> e.create_by AND a.remark <=> e.remark);
INSERT INTO tmp_iws_api_doc_menu_conflicts (object_key, reason)
SELECT CAST(e.menu_id AS char), '菜单自然键已被其他菜单占用'
FROM tmp_iws_api_doc_menu_expected e
JOIN tmp_iws_api_doc_menu_actual a
  ON a.parent_id = e.parent_id AND a.menu_type = e.menu_type AND a.path = e.path
WHERE @menu_schema_ok = 1 AND a.menu_id <> e.menu_id;
INSERT INTO tmp_iws_api_doc_menu_conflicts (object_key, reason)
SELECT CAST(e.menu_id AS char), '父级菜单不存在或不是目录'
FROM tmp_iws_api_doc_menu_expected e
LEFT JOIN tmp_iws_api_doc_menu_actual p ON p.menu_id = e.parent_id
WHERE @menu_schema_ok = 1 AND (p.menu_id IS NULL OR p.menu_type <> 'M');

SELECT COUNT(*) INTO @menu_conflicts FROM tmp_iws_api_doc_menu_conflicts;
SET @menu_preflight_ok = IF(@menu_schema_ok = 1 AND @menu_conflicts = 0, 1, 0);

SELECT @upgrade_schema AS `目标库`, @menu_target_ok AS `目标库存在`, @menu_database_matches AS `当前连接库匹配`,
       @menu_version_ok AS `MySQL8.0`, @menu_table_ok AS `sys_menu存在`, @menu_preflight_ok AS `预检通过`;
SELECT object_key AS `对象`, reason AS `冲突说明` FROM tmp_iws_api_doc_menu_conflicts ORDER BY object_key, reason;
SELECT JSON_EXTRACT(IF(@menu_preflight_ok = 1, '{}', 'invalid'), '$') AS `菜单预检`;

SET @menu_insert_sql = IF(@menu_preflight_ok = 1, CONCAT(
    'INSERT INTO ', @upgrade_schema_q, '.`sys_menu` ',
    '(menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark) ',
    'SELECT e.menu_id, e.menu_name, e.parent_id, e.order_num, e.path, e.component, e.query_param, e.is_frame, e.is_cache, e.menu_type, e.visible, e.status, e.perms, e.icon, e.active_menu, e.ext, e.create_dept, e.create_by, SYSDATE(), NULL, NULL, e.remark ',
    'FROM tmp_iws_api_doc_menu_expected e WHERE NOT EXISTS (SELECT 1 FROM ', @upgrade_schema_q, '.`sys_menu` m WHERE m.menu_id = e.menu_id)'
), 'DO 0');
PREPARE menu_insert_stmt FROM @menu_insert_sql;
EXECUTE menu_insert_stmt;
DEALLOCATE PREPARE menu_insert_stmt;

DELETE FROM tmp_iws_api_doc_menu_post;
SET @menu_post_load_sql = IF(@menu_schema_ok = 1, CONCAT(
    'INSERT INTO tmp_iws_api_doc_menu_post (menu_id, menu_name, parent_id, order_num, path, component, query_param,',
    ' is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by,',
    ' create_time, update_by, update_time, remark) ',
    'SELECT menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache,',
    ' menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time,',
    ' update_by, update_time, remark FROM ', @upgrade_schema_q, '.`sys_menu`'
), 'DO 0');
PREPARE menu_post_load_stmt FROM @menu_post_load_sql;
EXECUTE menu_post_load_stmt;
DEALLOCATE PREPARE menu_post_load_stmt;

DELETE FROM tmp_iws_api_doc_menu_conflicts;
INSERT INTO tmp_iws_api_doc_menu_conflicts (object_key, reason)
SELECT CAST(e.menu_id AS char), '菜单写入后仍缺失'
FROM tmp_iws_api_doc_menu_expected e
LEFT JOIN tmp_iws_api_doc_menu_post p ON p.menu_id = e.menu_id
WHERE @menu_preflight_ok = 1 AND p.menu_id IS NULL;
INSERT INTO tmp_iws_api_doc_menu_conflicts (object_key, reason)
SELECT CAST(e.menu_id AS char), '菜单写入后字段不一致'
FROM tmp_iws_api_doc_menu_expected e
JOIN tmp_iws_api_doc_menu_post p ON p.menu_id = e.menu_id
WHERE @menu_preflight_ok = 1
  AND NOT (p.menu_name <=> e.menu_name AND p.parent_id <=> e.parent_id AND p.order_num <=> e.order_num
    AND p.path <=> e.path AND p.component <=> e.component AND p.query_param <=> e.query_param
    AND p.is_frame <=> e.is_frame AND p.is_cache <=> e.is_cache AND p.menu_type <=> e.menu_type
    AND p.visible <=> e.visible AND p.status <=> e.status AND p.perms <=> e.perms
    AND p.icon <=> e.icon AND p.active_menu <=> e.active_menu AND p.ext <=> e.ext
    AND p.create_dept <=> e.create_dept AND p.create_by <=> e.create_by AND p.remark <=> e.remark);
INSERT INTO tmp_iws_api_doc_menu_conflicts (object_key, reason)
SELECT CAST(e.menu_id AS char), '菜单写入后父级不存在或不是目录'
FROM tmp_iws_api_doc_menu_expected e
LEFT JOIN tmp_iws_api_doc_menu_post p ON p.menu_id = e.parent_id
WHERE @menu_preflight_ok = 1 AND (p.menu_id IS NULL OR p.menu_type <> 'M');
INSERT INTO tmp_iws_api_doc_menu_conflicts (object_key, reason)
SELECT CAST(e.menu_id AS char), '菜单写入后自然键重复'
FROM tmp_iws_api_doc_menu_expected e
JOIN tmp_iws_api_doc_menu_post p
  ON p.parent_id = e.parent_id AND p.menu_type = e.menu_type AND p.path = e.path
WHERE @menu_preflight_ok = 1 AND p.menu_id <> e.menu_id;

SELECT COUNT(*) INTO @menu_conflicts FROM tmp_iws_api_doc_menu_conflicts;
SET @menu_post_ok = IF(@menu_preflight_ok = 1 AND @menu_conflicts = 0, 1, 0);

SELECT object_key AS `对象`, reason AS `冲突说明` FROM tmp_iws_api_doc_menu_conflicts ORDER BY object_key, reason;
SELECT JSON_EXTRACT(IF(@menu_post_ok = 1, '{}', 'invalid'), '$') AS `菜单后置校验`;
SELECT
    IF(@menu_post_ok = 1, '接口文档菜单升级完成', '接口文档菜单升级失败') AS `执行结果`,
    @upgrade_schema AS `目标库`,
    (SELECT COUNT(*) FROM tmp_iws_api_doc_menu_expected) AS `本次菜单数`;

SELECT menu_id AS `菜单ID`, menu_name AS `菜单名称`, parent_id AS `父级ID`, path AS `路由地址`,
       component AS `组件路径`, perms AS `权限标识`
FROM tmp_iws_api_doc_menu_expected ORDER BY menu_id;

SELECT menu_id AS `菜单ID`, menu_name AS `菜单名称`, parent_id AS `父级ID`, path AS `路由地址`,
       component AS `组件路径`, perms AS `权限标识`, create_time AS `创建时间`,
       update_by AS `更新者`, update_time AS `更新时间`
FROM tmp_iws_api_doc_menu_post
WHERE menu_id = (SELECT menu_id FROM tmp_iws_api_doc_menu_expected);

DROP TEMPORARY TABLE IF EXISTS tmp_iws_api_doc_menu_post;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_api_doc_menu_actual;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_api_doc_menu_conflicts;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_api_doc_menu_expected;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_api_doc_menu_columns;
