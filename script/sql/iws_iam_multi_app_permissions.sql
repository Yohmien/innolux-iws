-- @Date         : 2026-11-03
-- @Author       : YONGJIE.PENG
-- @Job          : INNOLUX IWS 标准化重构
-- @Module       : IWS 统一身份认证
-- @Description  : 在既有菜单权限表内补充多 IWS 与 PDA 的最小接入权限，不新增人员白名单；
-- @Execution    : mysql --database=<目标库> --default-character-set=utf8mb4 < iws_iam_multi_app_permissions.sql

SET NAMES utf8mb4;

SET @target_schema = DATABASE();
SET @target_ok = (
  SELECT COUNT(*) = 1
  FROM information_schema.SCHEMATA
  WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@target_schema AS BINARY)
);
SET @menu_table_ok = (
  SELECT COUNT(*) = 22
  FROM information_schema.COLUMNS
  WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
    AND CAST(TABLE_NAME AS BINARY) = CAST('sys_menu' AS BINARY)
    AND COLUMN_NAME IN (
      'menu_id', 'menu_name', 'parent_id', 'order_num', 'path', 'component',
      'query_param', 'is_frame', 'is_cache', 'menu_type', 'visible', 'status',
      'perms', 'icon', 'active_menu', 'ext', 'create_dept', 'create_by',
      'create_time', 'update_by', 'update_time', 'remark'
    )
);

SELECT
  @target_schema AS `目标库`,
  @target_ok AS `目标库有效`,
  @menu_table_ok AS `菜单表结构有效`;

DROP PROCEDURE IF EXISTS `iws_iam_ensure_multi_app_permissions`;

DELIMITER $$
CREATE PROCEDURE `iws_iam_ensure_multi_app_permissions`()
BEGIN
  DECLARE v_conflicts INT DEFAULT 0;
  DECLARE v_rows INT DEFAULT 0;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    RESIGNAL;
  END;

  IF @target_ok <> 1 OR @menu_table_ok <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '目标库或 sys_menu 结构不满足升级前置条件';
  END IF;

  START TRANSACTION;

  SELECT COUNT(*) INTO v_conflicts
  FROM `sys_menu`
  WHERE `menu_id` IN (
      1761400000000190001,
      1761400000000190002,
      1761400000000190003
    )
    AND CAST(`perms` AS BINARY) NOT IN (
      CAST('iam:app:iws:a' AS BINARY),
      CAST('iam:app:iws:b' AS BINARY),
      CAST('iam:app:pda' AS BINARY)
    );

  SELECT v_conflicts + COUNT(*) INTO v_conflicts
  FROM `sys_menu`
  WHERE CAST(`perms` AS BINARY) IN (
      CAST('iam:app:iws:a' AS BINARY),
      CAST('iam:app:iws:b' AS BINARY),
      CAST('iam:app:pda' AS BINARY)
    )
    AND `menu_id` NOT IN (
      1761400000000190001,
      1761400000000190002,
      1761400000000190003
    );

  IF v_conflicts > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '菜单编号或接入权限码已被其他记录占用';
  END IF;

  INSERT INTO `sys_menu` (
    `menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`,
    `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`,
    `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`,
    `create_time`, `update_by`, `update_time`, `remark`
  )
  SELECT
    1761400000000190001, 'IWS A 接入', 1761400000000000001, 90, '', '', '',
    'N', 'Y', 'F', '1', '0', 'iam:app:iws:a', '#', '', '',
    1761000000000000103, 1761100000000000001,
    CURRENT_TIMESTAMP, NULL, NULL, '统一身份多 IWS 接入权限'
  WHERE NOT EXISTS (
    SELECT 1 FROM `sys_menu` WHERE `menu_id` = 1761400000000190001
  );

  INSERT INTO `sys_menu` (
    `menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`,
    `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`,
    `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`,
    `create_time`, `update_by`, `update_time`, `remark`
  )
  SELECT
    1761400000000190002, 'IWS B 接入', 1761400000000000001, 91, '', '', '',
    'N', 'Y', 'F', '1', '0', 'iam:app:iws:b', '#', '', '',
    1761000000000000103, 1761100000000000001,
    CURRENT_TIMESTAMP, NULL, NULL, '统一身份多 IWS 接入权限'
  WHERE NOT EXISTS (
    SELECT 1 FROM `sys_menu` WHERE `menu_id` = 1761400000000190002
  );

  INSERT INTO `sys_menu` (
    `menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`,
    `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`,
    `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`,
    `create_time`, `update_by`, `update_time`, `remark`
  )
  SELECT
    1761400000000190003, 'PDA 接入', 1761400000000000001, 92, '', '', '',
    'N', 'Y', 'F', '1', '0', 'iam:app:pda', '#', '', '',
    1761000000000000103, 1761100000000000001,
    CURRENT_TIMESTAMP, NULL, NULL, '统一身份 PDA 接入权限'
  WHERE NOT EXISTS (
    SELECT 1 FROM `sys_menu` WHERE `menu_id` = 1761400000000190003
  );

  SELECT COUNT(*) INTO v_rows
  FROM `sys_menu`
  WHERE `menu_id` IN (
      1761400000000190001,
      1761400000000190002,
      1761400000000190003
    )
    AND `status` = '0'
    AND CAST(`perms` AS BINARY) IN (
      CAST('iam:app:iws:a' AS BINARY),
      CAST('iam:app:iws:b' AS BINARY),
      CAST('iam:app:pda' AS BINARY)
    );

  IF v_rows <> 3 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '多应用接入权限写入后核验失败';
  END IF;

  COMMIT;
END$$
DELIMITER ;

CALL `iws_iam_ensure_multi_app_permissions`();
DROP PROCEDURE IF EXISTS `iws_iam_ensure_multi_app_permissions`;

SELECT `menu_id`, `menu_name`, `perms`, `status`, `remark`
FROM `sys_menu`
WHERE `menu_id` IN (
    1761400000000190001,
    1761400000000190002,
    1761400000000190003
  )
ORDER BY `menu_id`;
