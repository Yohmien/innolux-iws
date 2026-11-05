-- 当前库统一认证菜单修正；MySQL 8.0+，使用支持 DELIMITER 的客户端在同一连接执行。
-- SQL 客户端先选择目标库；只修改下一行配置。选库不一致时拒绝菜单更新。
-- 仅隐藏中心所属管理页，不删除菜单、角色授权或业务管理菜单。
-- Web 的单一“统一认证中心”入口由前端固定路由提供。
SET @iam_menu_target_schema = 'innolux-iws';

DELIMITER $$
DROP PROCEDURE IF EXISTS `iws_iam_menu_repair_20261105`$$
CREATE PROCEDURE `iws_iam_menu_repair_20261105`(IN p_target_schema VARCHAR(64))
BEGIN
    DECLARE v_schema VARCHAR(64);
    DECLARE v_count INT DEFAULT 0;
    DECLARE v_before INT DEFAULT 0;
    DECLARE v_remaining INT DEFAULT 0;
    DECLARE v_pattern VARCHAR(255) DEFAULT
        '^(system/(user|role|dept|post|menu|client|dict|config|apk)(/|$)|monitor/online(/|$))';
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    SET v_schema = DATABASE();
    IF p_target_schema IS NULL OR p_target_schema = '' OR v_schema IS NULL
        OR CAST(v_schema AS BINARY) <> CAST(p_target_schema AS BINARY) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '当前连接库与配置目标不一致，菜单修正已停止';
    END IF;
    SELECT COUNT(*) INTO v_count
    FROM information_schema.tables
    WHERE CAST(table_schema AS BINARY) = CAST(v_schema AS BINARY)
      AND table_name = 'sys_menu' AND engine = 'InnoDB';
    IF v_count <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '目标库缺少 InnoDB sys_menu 表，菜单修正已停止';
    END IF;
    SELECT COUNT(*) INTO v_count
    FROM information_schema.columns
    WHERE CAST(table_schema AS BINARY) = CAST(v_schema AS BINARY)
      AND table_name = 'sys_menu'
      AND column_name IN ('menu_id', 'menu_name', 'menu_type', 'component', 'visible');
    IF v_count <> 5 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sys_menu 必需字段不完整，菜单修正已停止';
    END IF;
    SELECT COUNT(*) INTO v_count
    FROM information_schema.columns
    WHERE CAST(table_schema AS BINARY) = CAST(v_schema AS BINARY)
      AND table_name = 'sys_menu' AND column_name IN ('menu_type', 'visible')
      AND data_type IN ('char', 'varchar') AND character_maximum_length = 1;
    IF v_count <> 2 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '菜单类型或展示字段定义不符合预期，菜单修正已停止';
    END IF;

    START TRANSACTION;
    SELECT COUNT(*) INTO v_before FROM `sys_menu`
    WHERE menu_type = 'C' AND REGEXP_LIKE(component, v_pattern, 'c')
      AND NOT (visible <=> '1');

    UPDATE `sys_menu` SET visible = '1'
    WHERE menu_type = 'C' AND REGEXP_LIKE(component, v_pattern, 'c')
      AND NOT (visible <=> '1');

    SELECT COUNT(*) INTO v_remaining FROM `sys_menu`
    WHERE menu_type = 'C' AND REGEXP_LIKE(component, v_pattern, 'c')
      AND NOT (visible <=> '1');
    IF v_remaining <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '中心所属菜单展示状态核验失败，已回滚';
    END IF;
    COMMIT;

    SELECT v_schema AS `目标库`, v_before AS `原需修正数量`, v_remaining AS `剩余异常数量`,
        '中心所属页面已隐藏，原菜单权限与角色授权保持不变；刷新菜单或重新登录后核对' AS `执行说明`;
    SELECT menu_id AS `菜单ID`, menu_name AS `菜单名称`, component AS `中心页面`, visible AS `隐藏状态`
    FROM `sys_menu`
    WHERE menu_type = 'C' AND REGEXP_LIKE(component, v_pattern, 'c')
    ORDER BY menu_id;
END$$
CALL `iws_iam_menu_repair_20261105`(@iam_menu_target_schema)$$
DROP PROCEDURE `iws_iam_menu_repair_20261105`$$
DELIMITER ;
