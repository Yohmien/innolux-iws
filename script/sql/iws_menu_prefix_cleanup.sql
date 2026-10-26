/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 清理菜单名中的手写字母编号并将官网固定到末尾；目标MySQL 8.0，以当前连接库为准，可重复执行；
 * @ModifyRecord : V1.0 新建；
 */

-- 目标：MySQL 8.0；以当前连接的数据库为准。
-- 菜单编号改由 sys.ui.menuPrefixEnabled 生成；本脚本只改菜单名称与排序，不改结构、权限和角色授权。
SET @menu_schema = DATABASE();
SET @menu_table_ok = (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@menu_schema AS BINARY) AND CAST(TABLE_NAME AS BINARY) = CAST('sys_menu' AS BINARY) AND TABLE_TYPE = 'BASE TABLE');

SELECT @menu_schema AS `目标库`, @menu_table_ok AS `sys_menu存在`;
-- 表不可用时通过无效 JSON 中止，避免在错误目标执行后续比较与写入。
SELECT JSON_EXTRACT(IF(@menu_table_ok = 1, '{}', 'invalid'), '$') AS `菜单预检`;

SET @menu_tail_conflicts = (SELECT COUNT(*) FROM sys_menu WHERE parent_id = 0 AND menu_id <> 1761400000000000004 AND order_num >= 9999);
SET @menu_preflight_ok = (@menu_table_ok = 1 AND @menu_tail_conflicts = 0);

SELECT @menu_preflight_ok AS `预检通过`, @menu_tail_conflicts AS `末尾顺序冲突`;
-- 预检失败时中止；GUI 若续错，后续更新仍由 @menu_preflight_ok 门控。
SELECT JSON_EXTRACT(IF(@menu_preflight_ok = 1, '{}', 'invalid'), '$') AS `配置预检`;

-- 去掉菜单名里的手写编号；已改过的行不再匹配，保留现场后续调整。
UPDATE sys_menu SET menu_name = '基础数据'
WHERE @menu_preflight_ok = 1 AND menu_id = 1761400000000020001 AND CAST(menu_name AS BINARY) = CAST('A基础数据' AS BINARY);
UPDATE sys_menu SET menu_name = '入库管理'
WHERE @menu_preflight_ok = 1 AND menu_id = 1761400000000020002 AND CAST(menu_name AS BINARY) = CAST('B入库管理' AS BINARY);
UPDATE sys_menu SET menu_name = '储位管理'
WHERE @menu_preflight_ok = 1 AND menu_id = 1761400000000020003 AND CAST(menu_name AS BINARY) = CAST('C储位管理' AS BINARY);
UPDATE sys_menu SET menu_name = '在库管理'
WHERE @menu_preflight_ok = 1 AND menu_id = 1761400000000020004 AND CAST(menu_name AS BINARY) = CAST('D在库管理' AS BINARY);
UPDATE sys_menu SET menu_name = '出库管理'
WHERE @menu_preflight_ok = 1 AND menu_id = 1761400000000020005 AND CAST(menu_name AS BINARY) = CAST('E出库管理' AS BINARY);
UPDATE sys_menu SET menu_name = '供应商标准页'
WHERE @menu_preflight_ok = 1 AND menu_id = 1761400000000020100 AND CAST(menu_name AS BINARY) = CAST('A01供应商标准页' AS BINARY);

-- 官网固定使用 order_num=9999，排在全部根菜单之后。
UPDATE sys_menu SET order_num = 9999
WHERE @menu_preflight_ok = 1 AND menu_id = 1761400000000000004 AND order_num <> 9999;

SET @menu_cleanup_ok = (
  (SELECT COUNT(*) FROM sys_menu
    WHERE menu_id IN (1761400000000020001, 1761400000000020002, 1761400000000020003, 1761400000000020004, 1761400000000020005, 1761400000000020100)
      AND CAST(menu_name AS BINARY) IN (CAST('A基础数据' AS BINARY), CAST('B入库管理' AS BINARY), CAST('C储位管理' AS BINARY), CAST('D在库管理' AS BINARY), CAST('E出库管理' AS BINARY), CAST('A01供应商标准页' AS BINARY))
  ) = 0
  AND (SELECT COUNT(*) FROM sys_menu WHERE menu_id = 1761400000000000004 AND order_num = 9999) = 1
);

-- 写入后按菜单ID核验；旧名称仍存在或官网顺序未落地时通过无效 JSON 报错。
SELECT JSON_EXTRACT(IF(@menu_cleanup_ok = 1, '{}', 'invalid'), '$') AS `写入核验`;

SELECT menu_id, menu_name, order_num, parent_id
FROM sys_menu
WHERE menu_id IN (1761400000000020001, 1761400000000020002, 1761400000000020003, 1761400000000020004, 1761400000000020005, 1761400000000020100, 1761400000000000004)
ORDER BY parent_id, order_num, menu_id;
