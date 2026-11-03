-- IWS+ 空间页面与权限菜单独立验收，2026-11-02，MySQL 8.0。
-- 唯一环境参数在下方；mysql --database=<同一目标库> --batch --raw < 本文件。
-- 使用独立连接、串行部署；禁止 --force、自动重连及失败后从中途续跑。
-- 只读取持久菜单；仅创建会话临时表，不修复、不授权，不依赖业务表。
-- 既有根必须完整匹配；ID、同父路径或权限归属冲突一律拒绝。
-- 当前 sys_menu 无 route_name 列；后端生成 Space1761400000000050200，
-- Web 用该名称包装 WmsInventorySpace，沿用 company 的 is_cache=Y。
-- 页面不直接附带权限；查询与空间变更由四个 F 权限分别授权。
-- 完整元数据指路由与权限字段；既有审计时间、人员及备注不覆盖。
-- 缺失、完整元数据不一致或归属冲突均抛错；无持久写入。
-- 执行期间须停止菜单编辑；使用独立连接，不改变调用方事务。
SET @space_target_schema = 'innolux-iws';
SET NAMES utf8mb4;
SET @space_ready = 0;
SET @space_structure_ok = 0;
SET @space_match_count = 0;
SET @space_conflict_count = 1;
SET @space_post_ok = 0;

-- 2026-11-02 保留空间页面和三个查询权限，新增空间生命周期命令权限。
-- 必须独立会话串行执行，停止菜单/授权编辑和 DDL；不接管已有事务。
-- 要求当前账号直接拥有全局 SELECT、TRIGGER，且 partial_revokes=OFF。
-- 不接受仅库级授权或仅角色继承作为全局元数据可见性证明。
-- 拒绝两表上任何未知触发器及涉及两表的入向/出向 FK。
-- 不修改持久结构；结构不符只拒绝，既有审计字段保持原样。
SET @space_metadata_ok = 0;
SET @space_dependency_ok = 0;
SET @space_roles_unchanged = 0;
SET @space_grantee = CONCAT(QUOTE(SUBSTRING_INDEX(CURRENT_USER(), '@', 1)),
                            '@', QUOTE(SUBSTRING_INDEX(CURRENT_USER(), '@', -1)));
SET @space_metadata_ok = (
    @@GLOBAL.partial_revokes = 0
    AND (SELECT COUNT(DISTINCT PRIVILEGE_TYPE) FROM information_schema.USER_PRIVILEGES
         WHERE CAST(GRANTEE AS BINARY) = CAST(@space_grantee AS BINARY)
           AND PRIVILEGE_TYPE IN ('SELECT', 'TRIGGER')) = 2
);
SELECT '全局元数据可见性' AS `校验项`, IF(@space_metadata_ok = 1, '通过', '失败') AS `结果`;
SELECT JSON_EXTRACT(IF(@space_metadata_ok = 1, '{}', 'invalid'), '$') AS `元数据可见性门禁`;
SET @space_dependency_ok = (
    @space_metadata_ok = 1
    AND (SELECT COUNT(*) FROM information_schema.TRIGGERS
         WHERE CAST(EVENT_OBJECT_SCHEMA AS BINARY) = CAST(DATABASE() AS BINARY)
           AND EVENT_OBJECT_TABLE IN ('sys_menu', 'sys_role_menu')) = 0
    AND (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
         WHERE REFERENCED_TABLE_NAME IS NOT NULL
           AND ((CAST(REFERENCED_TABLE_SCHEMA AS BINARY) = CAST(DATABASE() AS BINARY)
                 AND REFERENCED_TABLE_NAME IN ('sys_menu', 'sys_role_menu'))
             OR (CAST(TABLE_SCHEMA AS BINARY) = CAST(DATABASE() AS BINARY)
                 AND TABLE_NAME IN ('sys_menu', 'sys_role_menu')))) = 0
);
SELECT '菜单及授权触发器与外键' AS `校验项`, IF(@space_dependency_ok = 1, '通过', '失败') AS `结果`;
SELECT JSON_EXTRACT(IF(@space_dependency_ok = 1, '{}', 'invalid'), '$') AS `持久影响门禁`;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_columns;
CREATE TEMPORARY TABLE tmp_iws_space_columns (
    table_name varchar(64), column_name varchar(64), ordinal_position int,
    column_type varchar(64), is_nullable varchar(3), column_default varchar(500),
    character_set_name varchar(64), collation_name varchar(64)
) ENGINE=InnoDB;
INSERT INTO tmp_iws_space_columns VALUES
    ('sys_menu', 'menu_id', '1', 'bigint', 'NO', NULL, NULL, NULL),
    ('sys_menu', 'menu_name', '2', 'varchar(50)', 'NO', NULL, 'utf8mb4', 'utf8mb4_general_ci'),
    ('sys_menu', 'parent_id', '3', 'bigint', 'YES', '0', NULL, NULL),
    ('sys_menu', 'order_num', '4', 'int', 'YES', '0', NULL, NULL),
    ('sys_menu', 'path', '5', 'varchar(200)', 'YES', '', 'utf8mb4', 'utf8mb4_general_ci'),
    ('sys_menu', 'component', '6', 'varchar(255)', 'YES', NULL, 'utf8mb4', 'utf8mb4_general_ci'),
    ('sys_menu', 'query_param', '7', 'varchar(255)', 'YES', NULL, 'utf8mb4', 'utf8mb4_general_ci'),
    ('sys_menu', 'is_frame', '8', 'char(1)', 'YES', 'N', 'utf8mb4', 'utf8mb4_general_ci'),
    ('sys_menu', 'is_cache', '9', 'char(1)', 'YES', 'Y', 'utf8mb4', 'utf8mb4_general_ci'),
    ('sys_menu', 'menu_type', '10', 'char(1)', 'YES', '', 'utf8mb4', 'utf8mb4_general_ci'),
    ('sys_menu', 'visible', '11', 'char(1)', 'YES', '0', 'utf8mb4', 'utf8mb4_general_ci'),
    ('sys_menu', 'status', '12', 'char(1)', 'YES', '0', 'utf8mb4', 'utf8mb4_general_ci'),
    ('sys_menu', 'perms', '13', 'varchar(100)', 'YES', NULL, 'utf8mb4', 'utf8mb4_general_ci'),
    ('sys_menu', 'icon', '14', 'varchar(100)', 'YES', '#', 'utf8mb4', 'utf8mb4_general_ci'),
    ('sys_menu', 'active_menu', '15', 'varchar(255)', 'YES', '', 'utf8mb4', 'utf8mb4_general_ci'),
    ('sys_menu', 'ext', '16', 'varchar(2000)', 'YES', '', 'utf8mb4', 'utf8mb4_general_ci'),
    ('sys_menu', 'create_dept', '17', 'bigint', 'YES', NULL, NULL, NULL),
    ('sys_menu', 'create_by', '18', 'bigint', 'YES', NULL, NULL, NULL),
    ('sys_menu', 'create_time', '19', 'datetime', 'YES', NULL, NULL, NULL),
    ('sys_menu', 'update_by', '20', 'bigint', 'YES', NULL, NULL, NULL),
    ('sys_menu', 'update_time', '21', 'datetime', 'YES', NULL, NULL, NULL),
    ('sys_menu', 'remark', '22', 'varchar(500)', 'YES', '', 'utf8mb4', 'utf8mb4_general_ci'),
    ('sys_role_menu', 'role_id', '1', 'bigint', 'NO', NULL, NULL, NULL),
    ('sys_role_menu', 'menu_id', '2', 'bigint', 'NO', NULL, NULL, NULL);
SET @space_structure_ok = (
    @space_metadata_ok = 1 AND @space_dependency_ok = 1
    AND (CAST(DATABASE() AS BINARY) <=> CAST(@space_target_schema AS BINARY))
    AND VERSION() REGEXP '^8[.]0[.]'
    AND (SELECT COUNT(*) FROM information_schema.TABLES
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('sys_menu', 'sys_role_menu')
           AND TABLE_TYPE = 'BASE TABLE' AND ENGINE = 'InnoDB') = 2
    AND (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('sys_menu', 'sys_role_menu')) = 24
    AND (SELECT COUNT(*) FROM tmp_iws_space_columns e JOIN information_schema.COLUMNS c
         ON c.TABLE_SCHEMA = DATABASE() AND c.TABLE_NAME = e.table_name AND c.COLUMN_NAME = e.column_name
         WHERE c.ORDINAL_POSITION = e.ordinal_position
           AND (CAST(c.COLUMN_NAME AS BINARY) <=> CAST(e.column_name AS BINARY))
           AND (CAST(c.COLUMN_TYPE AS BINARY) <=> CAST(e.column_type AS BINARY))
           AND (CAST(c.IS_NULLABLE AS BINARY) <=> CAST(e.is_nullable AS BINARY))
           AND (CAST(c.COLUMN_DEFAULT AS BINARY) <=> CAST(e.column_default AS BINARY))
           AND (CAST(c.CHARACTER_SET_NAME AS BINARY) <=> CAST(e.character_set_name AS BINARY))
           AND (CAST(c.COLLATION_NAME AS BINARY) <=> CAST(e.collation_name AS BINARY))
           AND c.EXTRA = '' AND c.GENERATION_EXPRESSION = '') = 24
    AND (SELECT COUNT(*) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('sys_menu', 'sys_role_menu')) = 3
    AND (SELECT COUNT(*) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE() AND NON_UNIQUE = 0 AND INDEX_NAME = 'PRIMARY'
           AND INDEX_TYPE = 'BTREE' AND IS_VISIBLE = 'YES' AND SUB_PART IS NULL
           AND EXPRESSION IS NULL AND COLLATION = 'A'
           AND ((TABLE_NAME = 'sys_menu' AND COLUMN_NAME = 'menu_id' AND SEQ_IN_INDEX = 1)
             OR (TABLE_NAME = 'sys_role_menu' AND COLUMN_NAME = 'role_id' AND SEQ_IN_INDEX = 1)
             OR (TABLE_NAME = 'sys_role_menu' AND COLUMN_NAME = 'menu_id' AND SEQ_IN_INDEX = 2))) = 3
    AND (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('sys_menu', 'sys_role_menu')
           AND CONSTRAINT_TYPE <> 'PRIMARY KEY') = 0
);
SELECT '目标库与菜单授权结构' AS `校验项`, IF(@space_structure_ok = 1, '通过', '失败') AS `结果`,
       DATABASE() AS `当前库`;
SELECT JSON_EXTRACT(IF(@space_structure_ok = 1, '{}', 'invalid'), '$') AS `菜单结构门禁`;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_columns;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_expected;
CREATE TEMPORARY TABLE tmp_iws_space_expected (
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
INSERT INTO tmp_iws_space_expected
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext)
VALUES
    (1761400000000020004, '在库管理', 0, 13, 'iws-inventory', NULL, '', 'N', 'Y', 'M', '0', '0', '', 'list', '', ''),
    (1761400000000050200, '空间查询', 1761400000000020004, 2, 'space', 'wms/inventory/space/index', '', 'N', 'Y', 'C', '0', '0', '', 'tree', '', ''),
    (1761400000000052001, '仓库查询', 1761400000000050200, 1, '', NULL, '', 'N', 'Y', 'F', '0', '0', 'inventory:warehouse:query', '#', '', ''),
    (1761400000000052002, '库区查询', 1761400000000050200, 2, '', NULL, '', 'N', 'Y', 'F', '0', '0', 'inventory:zone:query', '#', '', ''),
    (1761400000000052003, '储位查询', 1761400000000050200, 3, '', NULL, '', 'N', 'Y', 'F', '0', '0', 'inventory:storage:query', '#', '', ''),
    (1761400000000052004, '空间变更', 1761400000000050200, 4, '', NULL, '', 'N', 'Y', 'F', '0', '0', 'inventory:space:change', '#', '', '');

-- 状态表只存在于会话：完整元数据逐项比较，NULL 不等同空串。
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_state;
CREATE TEMPORARY TABLE tmp_iws_space_state (
    menu_id bigint NOT NULL PRIMARY KEY,
    state varchar(16) NOT NULL
) ENGINE=InnoDB;
INSERT INTO tmp_iws_space_state
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
FROM tmp_iws_space_expected e LEFT JOIN sys_menu m ON m.menu_id = e.menu_id;

SET @space_conflict_count = (
    SELECT COUNT(*) FROM tmp_iws_space_expected e JOIN sys_menu m ON m.menu_id <> e.menu_id
    WHERE (e.path <> '' AND m.parent_id = e.parent_id
           AND CONVERT(m.path USING utf8mb4) COLLATE utf8mb4_general_ci = e.path)
       OR (e.perms <> '' AND CONVERT(m.perms USING utf8mb4) COLLATE utf8mb4_general_ci = e.perms)
       OR (e.parent_id = 0 AND m.parent_id = 0
           AND CONVERT(m.menu_name USING utf8mb4) COLLATE utf8mb4_general_ci = e.menu_name)
);
SET @space_ready = (
    SELECT @space_structure_ok = 1
    AND COUNT(*) = 6
    AND SUM(menu_id = 1761400000000020004 AND state = 'MATCHED') = 1
    AND SUM(state = 'CONFLICT') = 0
    AND @space_conflict_count = 0
    FROM tmp_iws_space_state
);
SELECT e.menu_id AS `菜单ID`, e.menu_name AS `菜单`, s.state AS `状态`
FROM tmp_iws_space_expected e JOIN tmp_iws_space_state s ON s.menu_id = e.menu_id ORDER BY e.menu_id;
SELECT '菜单前置检查' AS `校验项`, IF(@space_ready = 1, '通过', '失败') AS `结果`,
       @space_conflict_count AS `路径权限或重复根冲突数`,
       (SELECT COUNT(*) FROM tmp_iws_space_state WHERE state = 'MISSING') AS `待新增数`;
SELECT JSON_EXTRACT(IF(@space_ready = 1, '{}', 'invalid'), '$') AS `菜单前置门禁`;

SET @space_post_ok = (@space_ready = 1 AND
    (SELECT COUNT(*) FROM tmp_iws_space_state WHERE state = 'MATCHED') = 6);
SELECT '空间页面菜单独立验收' AS `校验项`, IF(@space_post_ok = 1, '通过', '失败') AS `结果`,
       '本脚本不修改角色授权' AS `角色处理`;
SELECT JSON_EXTRACT(IF(@space_post_ok = 1, '{}', 'invalid'), '$') AS `最终验收门禁`;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_state;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_expected;
