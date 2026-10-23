/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件与系统接入配置
 * @Version      : V1.0
 * @Description  : endpoint与process菜单及按钮权限升级；目标MySQL 8.0，可重复执行并核验既有菜单；
 * @ModifyRecord : V1.0 新建；
 */

-- 目标：MySQL 8.0；单一目标库参数见下一行。
-- 本脚本只写 sys_menu，不向任何角色授予菜单，不签发真实权限。
-- 菜单不按模块启停自动隐藏；运行时过滤由后端能力判断负责。

SET @upgrade_schema = 'innolux_iws';
SET @upgrade_schema_q = CONCAT('`', REPLACE(@upgrade_schema, '`', '``'), '`');

SET @menu_target_ok = (SELECT COUNT(*) = 1 FROM information_schema.SCHEMATA WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@upgrade_schema AS BINARY));
SET @menu_database_matches = (DATABASE() IS NOT NULL AND CAST(DATABASE() AS BINARY) = CAST(@upgrade_schema AS BINARY));
SET @menu_version_ok = (VERSION() LIKE '8.0.%');
SET @menu_schema_collation_ok = (SELECT COUNT(*) = 1 FROM information_schema.SCHEMATA WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@upgrade_schema AS BINARY) AND DEFAULT_COLLATION_NAME = 'utf8mb4_general_ci');
SET @menu_table_case_setting_ok = (@@lower_case_table_names IN (0, 1, 2));
SET @menu_base_ok = (@menu_target_ok = 1 AND @menu_database_matches = 1 AND @menu_version_ok = 1 AND @menu_schema_collation_ok = 1 AND @menu_table_case_setting_ok = 1);
SET @menu_table_ok = (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@upgrade_schema AS BINARY) AND CAST(TABLE_NAME AS BINARY) = CAST('sys_menu' AS BINARY) AND TABLE_TYPE = 'BASE TABLE');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_menu_sys_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_menu_expected;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_menu_actual;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_menu_conflicts;
CREATE TEMPORARY TABLE tmp_iws_menu_sys_columns (column_name varchar(64) NOT NULL, column_type varchar(255) NOT NULL, is_nullable varchar(3) NOT NULL, PRIMARY KEY (column_name));
CREATE TEMPORARY TABLE tmp_iws_menu_expected (
    menu_id bigint NOT NULL PRIMARY KEY, natural_key varchar(512) NOT NULL, menu_name varchar(50) NOT NULL,
    parent_id bigint NOT NULL, order_num int NOT NULL, path varchar(200) NOT NULL, component varchar(255) DEFAULT NULL,
    query_param varchar(255) DEFAULT NULL, is_frame char(1) NOT NULL, is_cache char(1) NOT NULL, menu_type char(1) NOT NULL,
    visible char(1) NOT NULL, status char(1) NOT NULL, perms varchar(100) NOT NULL, icon varchar(100) NOT NULL,
    active_menu varchar(255) NOT NULL, ext varchar(2000) NOT NULL, create_dept bigint NOT NULL, create_by bigint NOT NULL,
    remark varchar(500) NOT NULL, UNIQUE KEY uk_tmp_iws_menu_natural (natural_key)
);
CREATE TEMPORARY TABLE tmp_iws_menu_actual (
    menu_id bigint NOT NULL, menu_name varchar(50) NOT NULL, parent_id bigint DEFAULT NULL,
    order_num int DEFAULT NULL, path varchar(200) DEFAULT NULL, component varchar(255) DEFAULT NULL,
    query_param varchar(255) DEFAULT NULL, is_frame char(1) DEFAULT NULL, is_cache char(1) DEFAULT NULL,
    menu_type char(1) DEFAULT NULL, visible char(1) DEFAULT NULL, status char(1) DEFAULT NULL,
    perms varchar(100) DEFAULT NULL, icon varchar(100) DEFAULT NULL, active_menu varchar(255) DEFAULT NULL,
    ext varchar(2000) DEFAULT NULL, create_dept bigint DEFAULT NULL, create_by bigint DEFAULT NULL,
    create_time datetime DEFAULT NULL, update_by bigint DEFAULT NULL, update_time datetime DEFAULT NULL,
    remark varchar(500) DEFAULT NULL, KEY idx_tmp_iws_menu_actual_id (menu_id)
);
CREATE TEMPORARY TABLE tmp_iws_menu_post LIKE tmp_iws_menu_actual;
CREATE TEMPORARY TABLE tmp_iws_menu_conflicts (object_key varchar(128) NOT NULL, reason varchar(255) NOT NULL);

INSERT INTO tmp_iws_menu_sys_columns VALUES
('menu_id','bigint','NO'),('menu_name','varchar(50)','NO'),('parent_id','bigint','YES'),('order_num','int','YES'),
('path','varchar(200)','YES'),('component','varchar(255)','YES'),('query_param','varchar(255)','YES'),
('is_frame','char(1)','YES'),('is_cache','char(1)','YES'),('menu_type','char(1)','YES'),('visible','char(1)','YES'),
('status','char(1)','YES'),('perms','varchar(100)','YES'),('icon','varchar(100)','YES'),('active_menu','varchar(255)','YES'),
('ext','varchar(2000)','YES'),('create_dept','bigint','YES'),('create_by','bigint','YES'),('create_time','datetime','YES'),
('update_by','bigint','YES'),('update_time','datetime','YES'),('remark','varchar(500)','YES');

INSERT INTO tmp_iws_menu_expected
(menu_id,natural_key,menu_name,parent_id,order_num,path,component,query_param,is_frame,is_cache,menu_type,visible,status,perms,icon,active_menu,ext,create_dept,create_by,remark) VALUES
(1761400000000030001,'M:0:endpoint','系统接入配置',0,6,'endpoint',NULL,NULL,'N','Y','M','0','0','','connection','','',1761000000000000103,1761100000000000001,'IWS系统定义、环境与共享地址基础配置'),
(1761400000000030002,'C:1761400000000030001:system','系统定义',1761400000000030001,1,'system','endpoint/system/index',NULL,'N','Y','C','0','0','endpoint:system:list','server','','',1761000000000000103,1761100000000000001,'逻辑系统目录'),
(1761400000000030003,'C:1761400000000030001:environment','环境配置',1761400000000030001,2,'environment','endpoint/environment/index',NULL,'N','Y','C','0','0','endpoint:environment:list','tree','','',1761000000000000103,1761100000000000001,'环境目录，当前运行环境只读展示'),
(1761400000000030004,'C:1761400000000030001:address','系统地址',1761400000000030001,3,'address','endpoint/address/index',NULL,'N','Y','C','0','0','endpoint:address:list','link','','',1761000000000000103,1761100000000000001,'环境共享地址与系统关联'),
(1761400000000030011,'F:1761400000000030002:endpoint:system:query','系统查询',1761400000000030002,1,'',NULL,NULL,'N','Y','F','0','0','endpoint:system:query','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000030012,'F:1761400000000030002:endpoint:system:add','系统新增',1761400000000030002,2,'',NULL,NULL,'N','Y','F','0','0','endpoint:system:add','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000030013,'F:1761400000000030002:endpoint:system:edit','系统修改',1761400000000030002,3,'',NULL,NULL,'N','Y','F','0','0','endpoint:system:edit','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000030014,'F:1761400000000030002:endpoint:audit:query','配置审计查询',1761400000000030002,4,'',NULL,NULL,'N','Y','F','0','0','endpoint:audit:query','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000030015,'F:1761400000000030003:endpoint:environment:query','环境查询',1761400000000030003,1,'',NULL,NULL,'N','Y','F','0','0','endpoint:environment:query','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000030016,'F:1761400000000030003:endpoint:environment:add','环境新增',1761400000000030003,2,'',NULL,NULL,'N','Y','F','0','0','endpoint:environment:add','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000030017,'F:1761400000000030003:endpoint:environment:edit','环境修改',1761400000000030003,3,'',NULL,NULL,'N','Y','F','0','0','endpoint:environment:edit','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000030018,'F:1761400000000030004:endpoint:address:query','地址查询',1761400000000030004,1,'',NULL,NULL,'N','Y','F','0','0','endpoint:address:query','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000030019,'F:1761400000000030004:endpoint:address:add','地址新增',1761400000000030004,2,'',NULL,NULL,'N','Y','F','0','0','endpoint:address:add','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000030020,'F:1761400000000030004:endpoint:address:edit','地址修改',1761400000000030004,3,'',NULL,NULL,'N','Y','F','0','0','endpoint:address:edit','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031001,'M:0:process','计划事件',0,7,'process',NULL,NULL,'N','Y','M','0','0','','tree-table','','',1761000000000000103,1761100000000000001,'计划定义、事件执行与监督调度'),
(1761400000000031002,'C:1761400000000031001:schedule','业务调度',1761400000000031001,1,'schedule','process/schedule/index',NULL,'N','Y','C','0','0','process:schedule:list','calendar','','',1761000000000000103,1761100000000000001,'业务统一调度与监督控制'),
(1761400000000031003,'C:1761400000000031001:definition','计划定义',1761400000000031001,2,'definition','process/definition/index',NULL,'N','Y','C','0','0','process:definition:list','tree','','',1761000000000000103,1761100000000000001,'计划版本、状态、事件与边'),
(1761400000000031004,'C:1761400000000031001:execution','执行总览',1761400000000031001,3,'execution','process/execution/index',NULL,'N','Y','C','0','0','process:execution:list','dashboard','','',1761000000000000103,1761100000000000001,'执行、队列、异常与接口抛账记录'),
(1761400000000031005,'C:1761400000000031001:instance/detail','计划详情',1761400000000031001,4,'instance/detail','process/instance/detail',NULL,'N','Y','C','1','0','process:instance:query','#','/process/execution','',1761000000000000103,1761100000000000001,'隐藏计划执行链详情'),
(1761400000000031006,'C:1761400000000031001:definition/edit','计划配置编辑',1761400000000031001,5,'definition/edit','process/definition/edit',NULL,'N','Y','C','1','0','process:definition:edit','#','/process/definition','',1761000000000000103,1761100000000000001,'隐藏计划配置编辑页'),
(1761400000000031011,'F:1761400000000031002:process:schedule:query','调度查询',1761400000000031002,1,'',NULL,NULL,'N','Y','F','0','0','process:schedule:query','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031012,'F:1761400000000031002:process:schedule:add','调度新增',1761400000000031002,2,'',NULL,NULL,'N','Y','F','0','0','process:schedule:add','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031013,'F:1761400000000031002:process:schedule:edit','调度修改',1761400000000031002,3,'',NULL,NULL,'N','Y','F','0','0','process:schedule:edit','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031014,'F:1761400000000031002:process:schedule:remove','调度删除',1761400000000031002,4,'',NULL,NULL,'N','Y','F','0','0','process:schedule:remove','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031015,'F:1761400000000031002:process:schedule:pause','监督暂停',1761400000000031002,5,'',NULL,NULL,'N','Y','F','0','0','process:schedule:pause','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031016,'F:1761400000000031002:process:schedule:resume','监督恢复',1761400000000031002,6,'',NULL,NULL,'N','Y','F','0','0','process:schedule:resume','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031017,'F:1761400000000031002:process:schedule:restart','监督重启worker',1761400000000031002,7,'',NULL,NULL,'N','Y','F','0','0','process:schedule:restart','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031018,'F:1761400000000031003:process:definition:query','计划定义查询',1761400000000031003,1,'',NULL,NULL,'N','Y','F','0','0','process:definition:query','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031019,'F:1761400000000031003:process:definition:add','计划草稿新增',1761400000000031003,2,'',NULL,NULL,'N','Y','F','0','0','process:definition:add','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031020,'F:1761400000000031003:process:definition:edit','计划草稿修改',1761400000000031003,3,'',NULL,NULL,'N','Y','F','0','0','process:definition:edit','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031021,'F:1761400000000031003:process:definition:copy','计划版本复制',1761400000000031003,4,'',NULL,NULL,'N','Y','F','0','0','process:definition:copy','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031022,'F:1761400000000031003:process:definition:publish','计划发布',1761400000000031003,5,'',NULL,NULL,'N','Y','F','0','0','process:definition:publish','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031023,'F:1761400000000031003:process:definition:remove','计划草稿删除',1761400000000031003,6,'',NULL,NULL,'N','Y','F','0','0','process:definition:remove','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031024,'F:1761400000000031004:process:instance:chain','执行链查询',1761400000000031004,1,'',NULL,NULL,'N','Y','F','0','0','process:instance:chain','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031025,'F:1761400000000031004:process:instance:attach','任务补挂',1761400000000031004,2,'',NULL,NULL,'N','Y','F','0','0','process:instance:attach','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031026,'F:1761400000000031004:process:instance:confirm','首次确认',1761400000000031004,3,'',NULL,NULL,'N','Y','F','0','0','process:instance:confirm','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031027,'F:1761400000000031004:process:instance:pause','实例暂停',1761400000000031004,4,'',NULL,NULL,'N','Y','F','0','0','process:instance:pause','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031028,'F:1761400000000031004:process:instance:resume','实例恢复',1761400000000031004,5,'',NULL,NULL,'N','Y','F','0','0','process:instance:resume','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031029,'F:1761400000000031004:process:instance:cancel','实例取消',1761400000000031004,6,'',NULL,NULL,'N','Y','F','0','0','process:instance:cancel','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031030,'F:1761400000000031004:process:instance:hold','挂起计划',1761400000000031004,7,'',NULL,NULL,'N','Y','F','0','0','process:instance:hold','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031031,'F:1761400000000031004:process:instance:release','放行计划',1761400000000031004,8,'',NULL,NULL,'N','Y','F','0','0','process:instance:release','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031032,'F:1761400000000031004:process:instance:priority','实例优先级',1761400000000031004,9,'',NULL,NULL,'N','Y','F','0','0','process:instance:priority','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031033,'F:1761400000000031004:process:instance:execute','即时执行',1761400000000031004,10,'',NULL,NULL,'N','Y','F','0','0','process:instance:execute','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031034,'F:1761400000000031004:process:trigger:receive','触发受理',1761400000000031004,11,'',NULL,NULL,'N','Y','F','0','0','process:trigger:receive','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031035,'F:1761400000000031004:process:event:query','事件查询',1761400000000031004,12,'',NULL,NULL,'N','Y','F','0','0','process:event:query','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031036,'F:1761400000000031004:process:event:retry','事件重试',1761400000000031004,13,'',NULL,NULL,'N','Y','F','0','0','process:event:retry','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031037,'F:1761400000000031004:process:event:revalidate','事件重新校验',1761400000000031004,14,'',NULL,NULL,'N','Y','F','0','0','process:event:revalidate','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031038,'F:1761400000000031004:process:event:finalize','事件最终失败',1761400000000031004,15,'',NULL,NULL,'N','Y','F','0','0','process:event:finalize','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031039,'F:1761400000000031004:process:event:priority','事件插队',1761400000000031004,16,'',NULL,NULL,'N','Y','F','0','0','process:event:priority','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031040,'F:1761400000000031004:process:event:request','请求报文查看',1761400000000031004,17,'',NULL,NULL,'N','Y','F','0','0','process:event:request','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031041,'F:1761400000000031004:process:audit:query','审计时间线',1761400000000031004,18,'',NULL,NULL,'N','Y','F','0','0','process:audit:query','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031042,'F:1761400000000031004:process:request:list','抛账记录列表',1761400000000031004,19,'',NULL,NULL,'N','Y','F','0','0','process:request:list','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031043,'F:1761400000000031004:process:request:query','抛账记录详情',1761400000000031004,20,'',NULL,NULL,'N','Y','F','0','0','process:request:query','#','','',1761000000000000103,1761100000000000001,'');

-- 先加载目标菜单快照；只读取 sys_menu，不改变目标数据。
SELECT COUNT(*) INTO @menu_schema_conflicts
FROM tmp_iws_menu_sys_columns e
LEFT JOIN information_schema.COLUMNS c
  ON CAST(c.TABLE_SCHEMA AS BINARY) = CAST(@upgrade_schema AS BINARY)
 AND CAST(c.TABLE_NAME AS BINARY) = CAST('sys_menu' AS BINARY)
 AND c.COLUMN_NAME = e.column_name
WHERE c.COLUMN_NAME IS NULL
   OR NOT (c.COLUMN_TYPE <=> e.column_type)
   OR NOT (c.IS_NULLABLE <=> e.is_nullable);
SET @menu_sys_schema_ok = IF(@menu_table_ok = 1 AND @menu_schema_conflicts = 0, 1, 0);
SET @menu_load_sql = IF(@menu_sys_schema_ok = 1, CONCAT(
    'INSERT INTO tmp_iws_menu_actual (menu_id, menu_name, parent_id, order_num, path, component, query_param,',
    ' is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by,',
    ' create_time, update_by, update_time, remark) ',
    'SELECT menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache,',
    ' menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time,',
    ' update_by, update_time, remark FROM ', @upgrade_schema_q, '.`sys_menu`'
), 'DO 0');
PREPARE menu_preflight_load_stmt FROM @menu_load_sql;
EXECUTE menu_preflight_load_stmt;
DEALLOCATE PREPARE menu_preflight_load_stmt;

DELETE FROM tmp_iws_menu_conflicts;
INSERT INTO tmp_iws_menu_conflicts (object_key, reason)
SELECT CAST(m.menu_id AS char), '既有菜单ID字段与目标不一致'
FROM tmp_iws_menu_expected e
JOIN tmp_iws_menu_actual m ON m.menu_id = e.menu_id
WHERE NOT (m.menu_name <=> e.menu_name AND m.parent_id <=> e.parent_id AND m.order_num <=> e.order_num
  AND m.path <=> e.path AND m.component <=> e.component AND m.query_param <=> e.query_param
  AND m.is_frame <=> e.is_frame AND m.is_cache <=> e.is_cache AND m.menu_type <=> e.menu_type
  AND m.visible <=> e.visible AND m.status <=> e.status AND m.perms <=> e.perms
  AND m.icon <=> e.icon AND m.active_menu <=> e.active_menu AND m.ext <=> e.ext);
INSERT INTO tmp_iws_menu_conflicts (object_key, reason)
SELECT CAST(e.menu_id AS char), CONCAT('自然键已被其他菜单占用：', e.natural_key)
FROM tmp_iws_menu_expected e
JOIN tmp_iws_menu_actual m
  ON m.parent_id = e.parent_id AND m.menu_type = e.menu_type
 AND (CASE WHEN m.menu_type = 'F' THEN CONCAT('F:', m.parent_id, ':', COALESCE(m.perms, ''))
           ELSE CONCAT(m.menu_type, ':', m.parent_id, ':', COALESCE(m.path, '')) END) = e.natural_key
WHERE m.menu_id <> e.menu_id;
INSERT INTO tmp_iws_menu_conflicts (object_key, reason)
SELECT e.column_name, 'sys_menu 必需列缺失或类型不符'
FROM tmp_iws_menu_sys_columns e
LEFT JOIN information_schema.COLUMNS c
  ON CAST(c.TABLE_SCHEMA AS BINARY) = CAST(@upgrade_schema AS BINARY)
 AND CAST(c.TABLE_NAME AS BINARY) = CAST('sys_menu' AS BINARY)
 AND c.COLUMN_NAME = e.column_name
WHERE c.COLUMN_NAME IS NULL
   OR NOT (c.COLUMN_TYPE <=> e.column_type)
   OR NOT (c.IS_NULLABLE <=> e.is_nullable);
INSERT INTO tmp_iws_menu_conflicts (object_key, reason)
SELECT 'sys_menu', '缺少 menu_id 主键索引'
WHERE NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS s
    WHERE CAST(s.TABLE_SCHEMA AS BINARY) = CAST(@upgrade_schema AS BINARY)
      AND CAST(s.TABLE_NAME AS BINARY) = CAST('sys_menu' AS BINARY)
      AND s.INDEX_NAME = 'PRIMARY' AND s.SEQ_IN_INDEX = 1
      AND s.COLUMN_NAME = 'menu_id'
);

SELECT COUNT(*) INTO @menu_conflicts FROM tmp_iws_menu_conflicts;
SET @menu_preflight_ok = IF(@menu_base_ok = 1 AND @menu_conflicts = 0, 1, 0);
SELECT object_key AS `对象`, reason AS `冲突说明` FROM tmp_iws_menu_conflicts ORDER BY object_key, reason;
SELECT JSON_EXTRACT(IF(@menu_preflight_ok = 1, '{}', 'invalid'), '$') AS `菜单预检`;

SET @menu_insert_sql = IF(@menu_preflight_ok = 1, CONCAT(
    'INSERT INTO ', @upgrade_schema_q, '.`sys_menu` ',
    '(menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, remark) ',
    'SELECT menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, CURRENT_TIMESTAMP, remark '
    'FROM tmp_iws_menu_expected e WHERE NOT EXISTS (SELECT 1 FROM ', @upgrade_schema_q, '.`sys_menu` m WHERE m.menu_id = e.menu_id) ORDER BY menu_id'
), 'DO 0');
PREPARE menu_stmt FROM @menu_insert_sql;
EXECUTE menu_stmt;
DEALLOCATE PREPARE menu_stmt;

-- 在插入后重新只读加载目标快照，独立核验本次菜单。
DELETE FROM tmp_iws_menu_post;
SET @menu_post_load_sql = IF(@menu_sys_schema_ok = 1, CONCAT(
    'INSERT INTO tmp_iws_menu_post (menu_id, menu_name, parent_id, order_num, path, component, query_param,',
    ' is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by,',
    ' create_time, update_by, update_time, remark) ',
    'SELECT menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache,',
    ' menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time,',
    ' update_by, update_time, remark FROM ', @upgrade_schema_q, '.`sys_menu`'
), 'DO 0');
PREPARE menu_post_load_stmt FROM @menu_post_load_sql;
EXECUTE menu_post_load_stmt;
DEALLOCATE PREPARE menu_post_load_stmt;

DELETE FROM tmp_iws_menu_conflicts;
INSERT INTO tmp_iws_menu_conflicts (object_key, reason)
SELECT CAST(e.menu_id AS char), '菜单写入后仍缺失'
FROM tmp_iws_menu_expected e
LEFT JOIN tmp_iws_menu_post p ON p.menu_id = e.menu_id
WHERE p.menu_id IS NULL;
INSERT INTO tmp_iws_menu_conflicts
SELECT CAST(m.menu_id AS char), '菜单写入后字段不一致'
FROM tmp_iws_menu_expected e
JOIN tmp_iws_menu_post m ON m.menu_id = e.menu_id
WHERE NOT (m.menu_name <=> e.menu_name AND m.parent_id <=> e.parent_id AND m.order_num <=> e.order_num
  AND m.path <=> e.path AND m.component <=> e.component AND m.query_param <=> e.query_param
  AND m.is_frame <=> e.is_frame AND m.is_cache <=> e.is_cache AND m.menu_type <=> e.menu_type
  AND m.visible <=> e.visible AND m.status <=> e.status AND m.perms <=> e.perms
  AND m.icon <=> e.icon AND m.active_menu <=> e.active_menu AND m.ext <=> e.ext);
INSERT INTO tmp_iws_menu_conflicts
SELECT CAST(e.menu_id AS char), '菜单父级不存在或不是目录'
FROM tmp_iws_menu_expected e
LEFT JOIN tmp_iws_menu_post p ON p.menu_id = e.parent_id
WHERE e.parent_id <> 0
  AND (p.menu_id IS NULL
       OR (e.menu_type = 'C' AND p.menu_type <> 'M')
       OR (e.menu_type = 'F' AND p.menu_type <> 'C'));
SELECT COUNT(*) INTO @menu_conflicts FROM tmp_iws_menu_conflicts;
SET @menu_post_ok = IF(@menu_preflight_ok = 1 AND @menu_conflicts = 0, 1, 0);
SELECT object_key AS `对象`, reason AS `冲突说明` FROM tmp_iws_menu_conflicts ORDER BY object_key, reason;
SELECT JSON_EXTRACT(IF(@menu_post_ok = 1, '{}', 'invalid'), '$') AS `菜单后置校验`;

SELECT
    IF(@menu_post_ok = 1, 'endpoint 与 process 菜单权限升级完成', '菜单权限升级失败') AS `执行结果`,
    @upgrade_schema AS `目标库`,
    (SELECT COUNT(*) FROM tmp_iws_menu_expected) AS `本次菜单数`;

SELECT menu_id AS `菜单ID`, menu_name AS `菜单名称`, parent_id AS `父级ID`, menu_type AS `类型`, perms AS `权限标识`
FROM tmp_iws_menu_expected ORDER BY menu_id;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_menu_post;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_menu_actual;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_menu_conflicts;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_menu_expected;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_menu_sys_columns;
