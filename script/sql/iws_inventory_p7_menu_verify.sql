-- IWS+ 在库管理 P7 只读菜单独立验收，2026-11-01，MySQL 8.0。
-- 唯一环境参数在下方；mysql --database=<同一目标库> --batch --raw < 本文件。
-- 只读取持久菜单；仅创建会话临时表，不修复、不授权。
SET @p7_target_schema = 'innolux-iws';
SET @p7_root_menu_id = 1761400000000020004;
SET NAMES utf8mb4;
SET @p7_structure_ok = 0;
SET @p7_match_count = 0;
SET @p7_conflict_count = 1;
SET @p7_post_ok = 0;

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
);
SELECT '目标库与菜单结构' AS `校验项`, IF(@p7_structure_ok = 1, '通过', '失败') AS `结果`,
       DATABASE() AS `当前库`;
SELECT JSON_EXTRACT(IF(@p7_structure_ok = 1, '{}', '{"gate":"invalid"}'), '$') AS `菜单结构门禁`;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_p7_verify_expected;
CREATE TEMPORARY TABLE tmp_iws_p7_verify_expected (
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
INSERT INTO tmp_iws_p7_verify_expected
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

DROP TEMPORARY TABLE IF EXISTS tmp_iws_p7_verify_state;
CREATE TEMPORARY TABLE tmp_iws_p7_verify_state (
    menu_id bigint NOT NULL PRIMARY KEY,
    state varchar(16) NOT NULL
) ENGINE=InnoDB;
INSERT INTO tmp_iws_p7_verify_state
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
FROM tmp_iws_p7_verify_expected e LEFT JOIN sys_menu m ON m.menu_id = e.menu_id;

SET @p7_conflict_count = (
    SELECT COUNT(*) FROM tmp_iws_p7_verify_expected e JOIN sys_menu m ON m.menu_id <> e.menu_id
    WHERE (e.path <> '' AND m.parent_id = e.parent_id
           AND CONVERT(m.path USING utf8mb4) COLLATE utf8mb4_general_ci = e.path)
       OR (e.perms <> ''
           AND CONVERT(m.perms USING utf8mb4) COLLATE utf8mb4_general_ci = e.perms)
       OR (e.menu_type IN ('M', 'C') AND m.parent_id = e.parent_id
           AND CONVERT(m.menu_name USING utf8mb4) COLLATE utf8mb4_general_ci = e.menu_name)
);
SET @p7_match_count = (SELECT COUNT(*) FROM tmp_iws_p7_verify_state WHERE state = 'MATCHED');
SET @p7_post_ok = (
    @p7_structure_ok = 1 AND @p7_match_count = 14 AND @p7_conflict_count = 0
);
SELECT e.menu_id AS `菜单ID`, e.menu_name AS `菜单`, s.state AS `状态`
FROM tmp_iws_p7_verify_expected e JOIN tmp_iws_p7_verify_state s ON s.menu_id = e.menu_id
ORDER BY e.menu_id;
SELECT '在库管理P7菜单独立验收' AS `校验项`,
       IF(@p7_post_ok = 1, '通过', '失败') AS `结果`,
       @p7_match_count AS `完整匹配数`,
       @p7_conflict_count AS `路由名称或权限冲突数`,
       '本脚本不修改角色授权' AS `角色处理`;
SELECT JSON_EXTRACT(IF(@p7_post_ok = 1, '{}', '{"gate":"invalid"}'), '$') AS `最终验收门禁`;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_p7_verify_state;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_p7_verify_expected;
