-- @Date         : 2026-11-06
-- @Author       : YONGJIE.PENG
-- @Job          : INNOLUX IWS 标准化重构
-- @Module       : IWS 统一身份认证
-- @Description  : 新增菜单应用归属及角色菜单应用作用域，不迁移或删除原全局授权；
-- @Execution    : mysql --database=<目标库> --default-character-set=utf8mb4 < iws_application_scoped_role_menus.sql

SET NAMES utf8mb4;

SET @target_schema = DATABASE();
SET @target_ok = (
  SELECT COUNT(*) = 1
  FROM information_schema.SCHEMATA
  WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@target_schema AS BINARY)
);
SET @menu_table_ok = (
  SELECT COUNT(*) = 2
  FROM information_schema.COLUMNS
  WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
    AND CAST(TABLE_NAME AS BINARY) = CAST('sys_menu' AS BINARY)
    AND COLUMN_NAME IN ('menu_id', 'perms')
);
SET @role_table_ok = (
  SELECT COUNT(*) = 1
  FROM information_schema.COLUMNS
  WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
    AND CAST(TABLE_NAME AS BINARY) = CAST('sys_role' AS BINARY)
    AND COLUMN_NAME = 'role_id'
);

SELECT
  @target_schema AS `目标库`,
  @target_ok AS `目标库有效`,
  @menu_table_ok AS `菜单表结构有效`,
  @role_table_ok AS `角色表结构有效`;

DROP PROCEDURE IF EXISTS `iws_ensure_role_menu_application_scope`;
DELIMITER $$
CREATE PROCEDURE `iws_ensure_role_menu_application_scope`()
BEGIN
  DECLARE v_menu_application_exists INT DEFAULT 0;
  DECLARE v_menu_application_type VARCHAR(64) DEFAULT '';
  DECLARE v_menu_application_nullable VARCHAR(3) DEFAULT '';
  DECLARE v_menu_application_default VARCHAR(64) DEFAULT '';
  DECLARE v_menu_application_comment VARCHAR(255) DEFAULT '';
  DECLARE v_menu_application_index INT DEFAULT 0;
  DECLARE v_menu_application_index_name_conflict INT DEFAULT 0;
  DECLARE v_scope_columns INT DEFAULT 0;
  DECLARE v_menu_columns INT DEFAULT 0;
  DECLARE v_menu_index INT DEFAULT 0;
  DECLARE v_scope_pk INT DEFAULT 0;
  DECLARE v_menu_pk INT DEFAULT 0;

  IF @target_ok <> 1 OR @menu_table_ok <> 1 OR @role_table_ok <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '目标库或角色菜单前置结构不满足升级条件';
  END IF;

  SELECT COUNT(*) INTO v_menu_application_exists
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @target_schema
    AND TABLE_NAME = 'sys_menu'
    AND COLUMN_NAME = 'application_id';

  IF v_menu_application_exists = 0 THEN
    ALTER TABLE `sys_menu`
      ADD COLUMN `application_id` varchar(64) NOT NULL DEFAULT 'iws-a'
        COMMENT '所属应用，* 表示共享' AFTER `remark`;
  ELSE
    SELECT
      COLUMN_TYPE,
      IS_NULLABLE,
      COALESCE(COLUMN_DEFAULT, ''),
      COLUMN_COMMENT
    INTO
      v_menu_application_type,
      v_menu_application_nullable,
      v_menu_application_default,
      v_menu_application_comment
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @target_schema
      AND TABLE_NAME = 'sys_menu'
      AND COLUMN_NAME = 'application_id';

    IF LOWER(v_menu_application_type) <> 'varchar(64)'
      OR v_menu_application_nullable <> 'NO'
      OR v_menu_application_default NOT IN ('*', 'iws-a')
      OR v_menu_application_comment <> '所属应用，* 表示共享' THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'sys_menu.application_id 类型、空值、默认值或注释与目标结构不一致';
    END IF;

    IF v_menu_application_default = '*' THEN
      ALTER TABLE `sys_menu`
        ALTER COLUMN `application_id` SET DEFAULT 'iws-a';
    END IF;
  END IF;

  SELECT COUNT(*) INTO v_menu_application_index
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = @target_schema
    AND TABLE_NAME = 'sys_menu'
    AND INDEX_NAME = 'idx_sys_menu_application'
    AND COLUMN_NAME = 'application_id'
    AND SEQ_IN_INDEX = 1;

  IF v_menu_application_index = 0 THEN
    SELECT COUNT(*) INTO v_menu_application_index_name_conflict
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @target_schema
      AND TABLE_NAME = 'sys_menu'
      AND INDEX_NAME = 'idx_sys_menu_application';

    IF v_menu_application_index_name_conflict > 0 THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = '索引 idx_sys_menu_application 已存在但结构不匹配';
    END IF;

    ALTER TABLE `sys_menu`
      ADD INDEX `idx_sys_menu_application` (`application_id`);
  END IF;

  -- 默认业务菜单归 IWS-A；仅认证中心、AI、共享监控/工具及官网保持共享
  UPDATE `sys_menu`
  SET `application_id` = 'iws-a'
  WHERE `application_id` IN ('', '*');

  WITH RECURSIVE shared_menu (menu_id) AS (
    SELECT menu_id
    FROM `sys_menu`
    WHERE menu_id IN (
      1761400000000000001,
      1761400000000000002,
      1761400000000000003,
      1761400000000000004,
      1761400000000000008
    )
    UNION ALL
    SELECT child.menu_id
    FROM `sys_menu` child
    INNER JOIN shared_menu parent ON child.parent_id = parent.menu_id
  )
  UPDATE `sys_menu` target
  INNER JOIN shared_menu scope ON scope.menu_id = target.menu_id
  SET target.application_id = '*';

  CREATE TABLE IF NOT EXISTS `sys_role_menu_scope` (
    `role_id` bigint NOT NULL COMMENT '角色ID',
    `application_id` varchar(64) NOT NULL COMMENT '统一身份应用标识',
    PRIMARY KEY (`role_id`, `application_id`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
    COMMENT='角色应用菜单作用域';

  CREATE TABLE IF NOT EXISTS `sys_role_menu_application` (
    `role_id` bigint NOT NULL COMMENT '角色ID',
    `application_id` varchar(64) NOT NULL COMMENT '统一身份应用标识',
    `menu_id` bigint NOT NULL COMMENT '菜单ID',
    PRIMARY KEY (`role_id`, `application_id`, `menu_id`),
    KEY `idx_role_menu_application_menu` (`menu_id`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
    COMMENT='角色应用菜单关联';

  SELECT COUNT(*) INTO v_scope_columns
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @target_schema
    AND TABLE_NAME = 'sys_role_menu_scope'
    AND COLUMN_NAME IN ('role_id', 'application_id');

  SELECT COUNT(*) INTO v_menu_columns
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @target_schema
    AND TABLE_NAME = 'sys_role_menu_application'
    AND COLUMN_NAME IN ('role_id', 'application_id', 'menu_id');

  SELECT COUNT(*) INTO v_scope_pk
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = @target_schema
    AND TABLE_NAME = 'sys_role_menu_scope'
    AND INDEX_NAME = 'PRIMARY'
    AND COLUMN_NAME IN ('role_id', 'application_id')
    AND NON_UNIQUE = 0;

  SELECT COUNT(*) INTO v_menu_pk
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = @target_schema
    AND TABLE_NAME = 'sys_role_menu_application'
    AND INDEX_NAME = 'PRIMARY'
    AND COLUMN_NAME IN ('role_id', 'application_id', 'menu_id')
    AND NON_UNIQUE = 0;

  SELECT COUNT(*) INTO v_menu_index
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = @target_schema
    AND TABLE_NAME = 'sys_role_menu_application'
    AND INDEX_NAME = 'idx_role_menu_application_menu'
    AND COLUMN_NAME = 'menu_id'
    AND SEQ_IN_INDEX = 1;

  IF v_scope_columns <> 2
    OR v_menu_columns <> 3
    OR v_scope_pk <> 2
    OR v_menu_pk <> 3
    OR v_menu_index <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '角色应用菜单表结构核验失败';
  END IF;
END$$
DELIMITER ;

CALL `iws_ensure_role_menu_application_scope`();
DROP PROCEDURE IF EXISTS `iws_ensure_role_menu_application_scope`;

SELECT
  '菜单应用归属' AS `校验项`,
  COUNT(*) = 1 AS `结构正确`
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @target_schema
  AND TABLE_NAME = 'sys_menu'
  AND COLUMN_NAME = 'application_id'
  AND LOWER(COLUMN_TYPE) = 'varchar(64)'
  AND IS_NULLABLE = 'NO'
  AND COALESCE(COLUMN_DEFAULT, '') = 'iws-a'

UNION ALL

SELECT
  '菜单应用索引' AS `校验项`,
  COUNT(*) = 1 AS `结构正确`
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = @target_schema
  AND TABLE_NAME = 'sys_menu'
  AND INDEX_NAME = 'idx_sys_menu_application'
  AND COLUMN_NAME = 'application_id'

UNION ALL

SELECT
  '角色应用菜单作用域' AS `校验项`,
  COUNT(*) > 0 AS `结构正确`
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = @target_schema
  AND TABLE_NAME = 'sys_role_menu_scope'

UNION ALL

SELECT
  '角色应用菜单关联' AS `校验项`,
  COUNT(*) > 0 AS `结构正确`
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = @target_schema
  AND TABLE_NAME = 'sys_role_menu_application';
