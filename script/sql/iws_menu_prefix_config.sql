/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 菜单编号前缀开关参数升级；目标MySQL 8.0，以当前连接库为准，可重复执行并按参数键核验；
 * @ModifyRecord : V1.0 新建；
 */

-- 目标：MySQL 8.0；以当前连接的数据库为准，执行前显式显示目标库名。
-- 本脚本只维护 sys_config 中的界面开关，不修改菜单、角色或业务数据。
SET @config_key = 'sys.ui.menuPrefixEnabled';
SET @config_id = 1761700000000000008;
SET @config_schema = DATABASE();
SET @config_schema_ok = (@config_schema IS NOT NULL AND @config_schema <> '');
SET @config_table_ok = (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@config_schema AS BINARY) AND CAST(TABLE_NAME AS BINARY) = CAST('sys_config' AS BINARY) AND TABLE_TYPE = 'BASE TABLE');
SET @config_base_ok = (@config_schema_ok = 1 AND @config_table_ok = 1);

SELECT @config_schema AS `目标库`, @config_schema_ok AS `已选择数据库`, @config_table_ok AS `sys_config存在`;
-- 库或表不可用时通过无效 JSON 中止，避免在错误目标执行后续比较与写入。
SELECT JSON_EXTRACT(IF(@config_base_ok = 1, '{}', 'invalid'), '$') AS `库表预检`;

SET @config_id_conflicts = (SELECT COUNT(*) FROM sys_config WHERE config_id = @config_id AND CAST(config_key AS BINARY) <> CAST(@config_key AS BINARY));
SET @config_preflight_ok = (@config_base_ok = 1 AND @config_id_conflicts = 0);

SELECT @config_preflight_ok AS `预检通过`, @config_id_conflicts AS `主键冲突`;
-- 预检失败时中止；GUI 若续错，后续 INSERT 仍由 @config_preflight_ok 门控。
SELECT JSON_EXTRACT(IF(@config_preflight_ok = 1, '{}', 'invalid'), '$') AS `配置预检`;

-- 已存在同键配置时保留现值，不覆盖现场设置。
INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type, create_dept, create_by, create_time, remark)
SELECT @config_id, '界面体验-菜单编号前缀开关', @config_key, 'false', 'Y', 1761000000000000103, 1761100000000000001, CURRENT_TIMESTAMP, 'true显示菜单编号前缀，false隐藏；默认关闭，刷新页面后生效'
FROM DUAL
WHERE @config_preflight_ok = 1
  AND NOT EXISTS (SELECT 1 FROM sys_config WHERE CAST(config_key AS BINARY) = CAST(@config_key AS BINARY));

-- 写入后按参数键核验；不存在时同样通过无效 JSON 报错。
SELECT JSON_EXTRACT(IF((SELECT COUNT(*) FROM sys_config WHERE CAST(config_key AS BINARY) = CAST(@config_key AS BINARY)) = 1, '{}', 'invalid'), '$') AS `写入核验`;

SELECT config_id, config_name, config_key, config_value, config_type, remark
FROM sys_config
WHERE CAST(config_key AS BINARY) = CAST(@config_key AS BINARY);
