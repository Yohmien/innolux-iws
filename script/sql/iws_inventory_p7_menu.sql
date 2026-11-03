-- IWS+ 在库管理 P7 只读菜单，2026-11-01，MySQL 8.0。
-- 唯一环境参数在下方；mysql --database=<同一目标库> --batch --raw < 本文件。
-- 使用独立连接、串行部署；禁止 --force、自动重连及失败后从中途续跑。
-- 只补缺失页面与权限，不更新既有行，不写 sys_role_menu。
-- 既有在库管理根必须完整匹配；ID、父子路径、名称或权限归属冲突一律拒绝。
-- 业务任务查询使用独立 inventory:task:query，不与库存业务目录页面权限复用。
SET @p7_target_schema = 'innolux-iws';
SET @p7_root_menu_id = 1761400000000020004;
SET NAMES utf8mb4;
SET @p7_structure_ok = 0;
SET @p7_ready = 0;
SET @p7_match_count = 0;
SET @p7_conflict_count = 1;
SET @p7_post_ok = 0;
SET @p7_roles_unchanged = 0;

SET @p7_structure_ok = (
    CAST(DATABASE() AS BINARY) = CAST(@p7_target_schema AS BINARY)
    AND VERSION() REGEXP '^8[.]0[.]'
    AND (SELECT COUNT(*) FROM information_schema.TABLES
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('sys_menu', 'sys_role_menu')
           AND TABLE_TYPE = 'BASE TABLE' AND ENGINE = 'InnoDB') = 2
    AND (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_menu') = 22
    AND (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role_menu') = 2
    AND (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_menu'
           AND (
               (COLUMN_NAME IN ('menu_id', 'parent_id', 'create_dept', 'create_by', 'update_by')
                AND COLUMN_TYPE = 'bigint')
               OR (COLUMN_NAME = 'order_num' AND COLUMN_TYPE = 'int')
               OR (COLUMN_NAME = 'menu_name' AND COLUMN_TYPE = 'varchar(50)' AND IS_NULLABLE = 'NO')
               OR (COLUMN_NAME = 'path' AND COLUMN_TYPE = 'varchar(200)')
               OR (COLUMN_NAME IN ('component', 'query_param', 'active_menu') AND COLUMN_TYPE = 'varchar(255)')
               OR (COLUMN_NAME IN ('is_frame', 'is_cache', 'menu_type', 'visible', 'status') AND COLUMN_TYPE = 'char(1)')
               OR (COLUMN_NAME IN ('perms', 'icon') AND COLUMN_TYPE = 'varchar(100)')
               OR (COLUMN_NAME = 'ext' AND COLUMN_TYPE = 'varchar(2000)')
               OR (COLUMN_NAME IN ('create_time', 'update_time') AND COLUMN_TYPE = 'datetime')
               OR (COLUMN_NAME = 'remark' AND COLUMN_TYPE = 'varchar(500)')
           )) = 22
    AND (SELECT COUNT(*) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('sys_menu', 'sys_role_menu')) = 3
);
SELECT '目标库与菜单结构' AS `校验项`, IF(@p7_structure_ok = 1, '通过', '失败') AS `结果`,
       DATABASE() AS `当前库`;
SELECT JSON_EXTRACT(IF(@p7_structure_ok = 1, '{}', '{"gate":"invalid"}'), '$') AS `菜单结构门禁`;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_p7_expected;
CREATE TEMPORARY TABLE tmp_iws_p7_expected (
    menu_id bigint NOT NULL PRIMARY KEY,
    menu_name varchar(50) NOT NULL,
    parent_id bigint NOT NULL,
    order_num int NOT NULL,
    path varchar(200) NOT NULL,
    component varchar(255) DEFAULT NULL,
    query_param varchar(255) NOT NULL,
    is_frame char(1) NOT NULL,
    is_cache char(1) NOT NULL,
    menu_type char(1) NOT NULL,
    visible char(1) NOT NULL,
    status char(1) NOT NULL,
    perms varchar(100) NOT NULL,
    icon varchar(100) NOT NULL,
    active_menu varchar(255) NOT NULL,
    ext varchar(2000) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
INSERT INTO tmp_iws_p7_expected
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext)
VALUES
    (@p7_root_menu_id, '在库管理', 0, 13, 'iws-inventory', NULL, '',
     'N', 'Y', 'M', '0', '0', '', 'list', '', ''),
    (1761400000000050400, '在库物料', @p7_root_menu_id, 4, 'materials',
     'wms/inventory/material/index', '', 'N', 'Y', 'C', '0', '0', '', 'goods', '', ''),
    (1761400000000054001, '物料查询', 1761400000000050400, 1, '', NULL, '',
     'N', 'Y', 'F', '0', '0', 'inventory:material:query', '#', '', ''),
    (1761400000000050500, '在库规则', @p7_root_menu_id, 5, 'rules',
     'wms/inventory/rules/index', '', 'N', 'Y', 'C', '0', '0', '', 'skill', '', ''),
    (1761400000000055001, '规则查询', 1761400000000050500, 1, '', NULL, '',
     'N', 'Y', 'F', '0', '0', 'inventory:rule:query', '#', '', ''),
    (1761400000000055002, '草稿预览', 1761400000000050500, 2, '', NULL, '',
     'N', 'Y', 'F', '0', '0', 'inventory:rule:draft', '#', '', ''),
    (1761400000000055003, '规则发布', 1761400000000050500, 3, '', NULL, '',
     'N', 'Y', 'F', '0', '0', 'inventory:rule:publish', '#', '', ''),
    (1761400000000055004, '逻辑合并', 1761400000000050500, 4, '', NULL, '',
     'N', 'Y', 'F', '0', '0', 'inventory:logical-merge:manage', '#', '', ''),
    (1761400000000050600, '物料追踪', @p7_root_menu_id, 6, 'traces',
     'wms/inventory/trace/index', '', 'N', 'Y', 'C', '0', '0', '', 'chart', '', ''),
    (1761400000000056001, '追踪查询', 1761400000000050600, 1, '', NULL, '',
     'N', 'Y', 'F', '0', '0', 'inventory:trace:query', '#', '', ''),
    (1761400000000056002, '追踪导出', 1761400000000050600, 2, '', NULL, '',
     'N', 'Y', 'F', '0', '0', 'inventory:trace:export', '#', '', ''),
    (1761400000000056003, 'UNKNOWN查证', 1761400000000050600, 3, '', NULL, '',
     'N', 'Y', 'F', '0', '0', 'inventory:allocation:investigate', '#', '', ''),
    (1761400000000050700, '在库业务', @p7_root_menu_id, 7, 'business',
     'wms/inventory/business/index', '', 'N', 'Y', 'C', '0', '0', '', 'log', '', ''),
    (1761400000000057001, '业务任务查询', 1761400000000050700, 1, '', NULL, '',
     'N', 'Y', 'F', '0', '0', 'inventory:task:query', '#', '', '');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_p7_state;
CREATE TEMPORARY TABLE tmp_iws_p7_state (
    menu_id bigint NOT NULL PRIMARY KEY,
    state varchar(16) NOT NULL
) ENGINE=InnoDB;
INSERT INTO tmp_iws_p7_state
SELECT e.menu_id,
       CASE WHEN m.menu_id IS NULL THEN 'MISSING'
            WHEN (CAST(m.menu_name AS BINARY) <=> CAST(e.menu_name AS BINARY))
             AND (m.parent_id <=> e.parent_id) AND (m.order_num <=> e.order_num)
             AND (CAST(m.path AS BINARY) <=> CAST(e.path AS BINARY))
             AND (CAST(m.component AS BINARY) <=> CAST(e.component AS BINARY))
             AND (CAST(m.query_param AS BINARY) <=> CAST(e.query_param AS BINARY))
             AND (CAST(m.is_frame AS BINARY) <=> CAST(e.is_frame AS BINARY))
             AND (CAST(m.is_cache AS BINARY) <=> CAST(e.is_cache AS BINARY))
             AND (CAST(m.menu_type AS BINARY) <=> CAST(e.menu_type AS BINARY))
             AND (CAST(m.visible AS BINARY) <=> CAST(e.visible AS BINARY))
             AND (CAST(m.status AS BINARY) <=> CAST(e.status AS BINARY))
             AND (CAST(m.perms AS BINARY) <=> CAST(e.perms AS BINARY))
             AND (CAST(m.icon AS BINARY) <=> CAST(e.icon AS BINARY))
             AND (CAST(m.active_menu AS BINARY) <=> CAST(e.active_menu AS BINARY))
             AND (CAST(m.ext AS BINARY) <=> CAST(e.ext AS BINARY))
            THEN 'MATCHED' ELSE 'CONFLICT' END
FROM tmp_iws_p7_expected e LEFT JOIN sys_menu m ON m.menu_id = e.menu_id;

SET @p7_conflict_count = (
    SELECT COUNT(*) FROM tmp_iws_p7_expected e JOIN sys_menu m ON m.menu_id <> e.menu_id
    WHERE (e.path <> '' AND m.parent_id = e.parent_id
           AND CONVERT(m.path USING utf8mb4) COLLATE utf8mb4_general_ci = e.path)
       OR (e.perms <> ''
           AND CONVERT(m.perms USING utf8mb4) COLLATE utf8mb4_general_ci = e.perms)
       OR (e.menu_type IN ('M', 'C') AND m.parent_id = e.parent_id
           AND CONVERT(m.menu_name USING utf8mb4) COLLATE utf8mb4_general_ci = e.menu_name)
);
SET @p7_ready = (
    SELECT @p7_structure_ok = 1
       AND COUNT(*) = 14
       AND SUM(menu_id = @p7_root_menu_id AND state = 'MATCHED') = 1
       AND SUM(state = 'CONFLICT') = 0
       AND @p7_conflict_count = 0
    FROM tmp_iws_p7_state
);
SELECT e.menu_id AS `菜单ID`, e.menu_name AS `菜单`, s.state AS `状态`
FROM tmp_iws_p7_expected e JOIN tmp_iws_p7_state s ON s.menu_id = e.menu_id
ORDER BY e.menu_id;
SELECT '菜单前置检查' AS `校验项`, IF(@p7_ready = 1, '通过', '失败') AS `结果`,
       @p7_conflict_count AS `路由名称或权限冲突数`,
       (SELECT COUNT(*) FROM tmp_iws_p7_state WHERE state = 'MISSING') AS `待新增数`;
SELECT JSON_EXTRACT(IF(@p7_ready = 1, '{}', '{"gate":"invalid"}'), '$') AS `菜单前置门禁`;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_p7_role_before;
CREATE TEMPORARY TABLE tmp_iws_p7_role_before (
    role_id bigint NOT NULL,
    menu_id bigint NOT NULL,
    PRIMARY KEY (role_id, menu_id)
) ENGINE=InnoDB;
INSERT INTO tmp_iws_p7_role_before SELECT role_id, menu_id FROM sys_role_menu;

START TRANSACTION;
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext,
     create_dept, create_by, create_time, update_by, update_time, remark)
SELECT e.menu_id, e.menu_name, e.parent_id, e.order_num, e.path, e.component, e.query_param,
       e.is_frame, e.is_cache, e.menu_type, e.visible, e.status, e.perms, e.icon,
       e.active_menu, e.ext, NULL, NULL, CURRENT_TIMESTAMP, NULL, NULL, ''
FROM tmp_iws_p7_expected e
WHERE @p7_ready = 1 AND e.menu_id <> @p7_root_menu_id
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_id = e.menu_id);

SET @p7_match_count = (
    SELECT COUNT(*) FROM tmp_iws_p7_expected e JOIN sys_menu m ON m.menu_id = e.menu_id
    WHERE (CAST(m.menu_name AS BINARY) <=> CAST(e.menu_name AS BINARY))
      AND (m.parent_id <=> e.parent_id) AND (m.order_num <=> e.order_num)
      AND (CAST(m.path AS BINARY) <=> CAST(e.path AS BINARY))
      AND (CAST(m.component AS BINARY) <=> CAST(e.component AS BINARY))
      AND (CAST(m.query_param AS BINARY) <=> CAST(e.query_param AS BINARY))
      AND (CAST(m.is_frame AS BINARY) <=> CAST(e.is_frame AS BINARY))
      AND (CAST(m.is_cache AS BINARY) <=> CAST(e.is_cache AS BINARY))
      AND (CAST(m.menu_type AS BINARY) <=> CAST(e.menu_type AS BINARY))
      AND (CAST(m.visible AS BINARY) <=> CAST(e.visible AS BINARY))
      AND (CAST(m.status AS BINARY) <=> CAST(e.status AS BINARY))
      AND (CAST(m.perms AS BINARY) <=> CAST(e.perms AS BINARY))
      AND (CAST(m.icon AS BINARY) <=> CAST(e.icon AS BINARY))
      AND (CAST(m.active_menu AS BINARY) <=> CAST(e.active_menu AS BINARY))
      AND (CAST(m.ext AS BINARY) <=> CAST(e.ext AS BINARY))
);
SET @p7_conflict_count = (
    SELECT COUNT(*) FROM tmp_iws_p7_expected e JOIN sys_menu m ON m.menu_id <> e.menu_id
    WHERE (e.path <> '' AND m.parent_id = e.parent_id
           AND CONVERT(m.path USING utf8mb4) COLLATE utf8mb4_general_ci = e.path)
       OR (e.perms <> ''
           AND CONVERT(m.perms USING utf8mb4) COLLATE utf8mb4_general_ci = e.perms)
       OR (e.menu_type IN ('M', 'C') AND m.parent_id = e.parent_id
           AND CONVERT(m.menu_name USING utf8mb4) COLLATE utf8mb4_general_ci = e.menu_name)
);
SET @p7_role_extra = EXISTS (
    SELECT 1 FROM sys_role_menu r LEFT JOIN tmp_iws_p7_role_before b
    ON b.role_id = r.role_id AND b.menu_id = r.menu_id WHERE b.role_id IS NULL
);
SET @p7_role_missing = EXISTS (
    SELECT 1 FROM tmp_iws_p7_role_before b LEFT JOIN sys_role_menu r
    ON b.role_id = r.role_id AND b.menu_id = r.menu_id WHERE r.role_id IS NULL
);
SET @p7_roles_unchanged = (@p7_role_extra = 0 AND @p7_role_missing = 0);
SET @p7_post_ok = (
    @p7_ready = 1 AND @p7_match_count = 14
    AND @p7_conflict_count = 0 AND @p7_roles_unchanged = 1
);
SET @p7_finish_sql = IF(@p7_post_ok = 1, 'COMMIT', 'ROLLBACK');
PREPARE iws_p7_finish FROM @p7_finish_sql;
EXECUTE iws_p7_finish;
DEALLOCATE PREPARE iws_p7_finish;
SELECT '在库管理P7只读菜单' AS `校验项`,
       IF(@p7_post_ok = 1, '通过', '失败，已回滚') AS `结果`,
       @p7_match_count AS `完整匹配数`,
       IF(@p7_roles_unchanged = 1, '逐行相同，新增授权0', '角色授权不一致') AS `角色处理`;
SELECT JSON_EXTRACT(IF(@p7_post_ok = 1, '{}', '{"gate":"invalid"}'), '$') AS `最终门禁`;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_p7_state;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_p7_expected;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_p7_role_before;
