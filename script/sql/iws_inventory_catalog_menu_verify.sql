-- IWS+ 库存业务目录只读入口独立验收，2026-10-29，MySQL 8.0。
-- 唯一环境参数在下方；mysql --database=<同一目标库> --batch --raw < 本文件。
-- 使用独立连接、串行部署；禁止 --force、自动重连及失败后从中途续跑。
-- 只读取持久菜单；仅创建会话临时表，不修复、不授权。
-- 既有在库管理根必须完整匹配；ID、路由、同父名称或权限归属冲突一律拒绝。
-- 页面本身仅登记 inventory:business:query，不创建发布或挂载权限。
-- 当前 sys_menu 无 route_name 列；后端生成 Catalog1761400000000050300。
-- 缺失、完整元数据不一致或归属冲突均抛错；无持久写入。
-- 执行期间须停止菜单编辑；使用独立连接，不改变调用方事务。
SET @catalog_target_schema = 'innolux-iws';
SET @catalog_root_menu_id = 1761400000000020004;
SET @catalog_page_menu_id = 1761400000000050300;
SET @catalog_query_permission = 'inventory:business:query';
SET NAMES utf8mb4;
SET @catalog_ready = 0;
SET @catalog_structure_ok = 0;
SET @catalog_match_count = 0;
SET @catalog_conflict_count = 1;
SET @catalog_post_ok = 0;

SET @catalog_structure_ok = (
    CAST(DATABASE() AS BINARY) = CAST(@catalog_target_schema AS BINARY)
    AND VERSION() REGEXP '^8[.]0[.]'
    AND (SELECT COUNT(*) FROM information_schema.TABLES
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_menu'
           AND TABLE_TYPE = 'BASE TABLE' AND ENGINE = 'InnoDB') = 1
    AND (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_menu') = 22
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
    AND (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_menu'
           AND COLUMN_NAME = 'menu_id' AND IS_NULLABLE = 'NO') = 1
    AND (SELECT COUNT(*) = 1 AND MAX(COLUMN_NAME) = 'menu_id' AND MAX(SEQ_IN_INDEX) = 1
                AND MAX(NON_UNIQUE) = 0
         FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_menu' AND INDEX_NAME = 'PRIMARY')
);
SELECT '目标库与菜单结构' AS `校验项`, IF(@catalog_structure_ok = 1, '通过', '失败') AS `结果`,
       DATABASE() AS `当前库`;
SELECT JSON_EXTRACT(IF(@catalog_structure_ok = 1, '{}', 'invalid'), '$') AS `菜单结构门禁`;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_expected;
CREATE TEMPORARY TABLE tmp_iws_catalog_expected (
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
INSERT INTO tmp_iws_catalog_expected
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext)
VALUES
    (@catalog_root_menu_id, '在库管理', 0, 13, 'iws-inventory', NULL, '',
     'N', 'Y', 'M', '0', '0', '', 'list', '', ''),
    (@catalog_page_menu_id, '库存业务目录', @catalog_root_menu_id, 3, 'catalog',
     'wms/inventory/catalog/index', '', 'N', 'Y', 'C', '0', '0',
     @catalog_query_permission, 'list', '', '');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_state;
CREATE TEMPORARY TABLE tmp_iws_catalog_state (
    menu_id bigint NOT NULL PRIMARY KEY,
    state varchar(16) NOT NULL
) ENGINE=InnoDB;
INSERT INTO tmp_iws_catalog_state
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
FROM tmp_iws_catalog_expected e LEFT JOIN sys_menu m ON m.menu_id = e.menu_id;

SET @catalog_conflict_count = (
    SELECT COUNT(*) FROM tmp_iws_catalog_expected e JOIN sys_menu m ON m.menu_id <> e.menu_id
    WHERE (e.menu_id = @catalog_root_menu_id
           AND (
               (m.menu_type IN ('M', 'C') AND e.path <> ''
                AND CONVERT(m.path USING utf8mb4) COLLATE utf8mb4_general_ci = e.path)
               OR (m.parent_id = 0
                   AND CONVERT(m.menu_name USING utf8mb4) COLLATE utf8mb4_general_ci = e.menu_name)
           ))
       OR (e.menu_id = @catalog_page_menu_id
           AND (
               (m.menu_type IN ('M', 'C') AND e.path <> ''
                AND CONVERT(m.path USING utf8mb4) COLLATE utf8mb4_general_ci = e.path)
               OR (m.parent_id = e.parent_id
                   AND CONVERT(m.menu_name USING utf8mb4) COLLATE utf8mb4_general_ci = e.menu_name)
               OR (e.perms <> ''
                   AND CONVERT(m.perms USING utf8mb4) COLLATE utf8mb4_general_ci = e.perms)
           ))
);
SET @catalog_match_count = (
    SELECT COUNT(*) FROM tmp_iws_catalog_state WHERE state = 'MATCHED'
);
SET @catalog_post_ok = (
    @catalog_structure_ok = 1 AND @catalog_match_count = 2 AND @catalog_conflict_count = 0
);
SELECT e.menu_id AS `菜单ID`, e.menu_name AS `菜单`, s.state AS `状态`
FROM tmp_iws_catalog_expected e JOIN tmp_iws_catalog_state s ON s.menu_id = e.menu_id
ORDER BY e.menu_id;
SELECT '库存业务目录入口独立验收' AS `校验项`,
       IF(@catalog_post_ok = 1, '通过', '失败') AS `结果`,
       @catalog_match_count AS `完整匹配数`,
       @catalog_conflict_count AS `路由名称或权限冲突数`,
       '本脚本不修改角色授权' AS `角色处理`;
SELECT JSON_EXTRACT(IF(@catalog_post_ok = 1, '{}', 'invalid'), '$') AS `最终验收门禁`;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_state;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_catalog_expected;
