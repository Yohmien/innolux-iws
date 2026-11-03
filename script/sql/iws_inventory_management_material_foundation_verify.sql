-- ============================================================================
-- IWS+ 库存 P4-01 物料、装载、占用与 trace 独立目标结构
-- 字段依据：主代理2026-10-29冻结decision-proposal最小结构及六项准确边界。
-- Spec修订：trace item料号可空；FACT无reverse引用，新COMPENSATION引用被补偿原trace；
--   不记录不可在同事务追加时得知的真正提交完成时刻，仅保留occurred_at/recorded_at。
-- 本文件仅交付独立目标DDL；不代表正式数据库执行或cutover授权，待主Review。
-- ============================================================================
-- 目标数据库：MySQL 8.0.45
-- 目标库参数：@target_schema，集中定义于下方；必须与连接 DATABASE() 字节一致
-- 独立验收：要求以下 11 表已完整存在，不执行目标 DDL。
-- 目标对象：
--   wms_production_info
--   wms_material_attribute_value
--   wms_load_node
--   wms_load_relation
--   wms_load_relation_history
--   wms_storage_info
--   wms_occupancy_effect
--   wms_storage_occupancy
--   wms_material_trace_history
--   wms_material_trace_item
--   wms_material_trace_edge
-- 依据：
--   D/work/inventory-p1-contract-20261027.md §5.1、§5.3、§5.4、§5.5、§6、§8
--   主方案 §6、§7、§10.1、§11、§12；任务图 INV-P4-01
-- 范围：
--   只建立独立目标结构。跨表 ID 均为逻辑引用，不建立物理外键；不修改库存、
--   容器、旧 flow 或旧唯一约束；不创建 object_audit/command_attempt、
--   material_logical_assignment/history、carrier_aggregate 或 location_change_history；
--   不回填、不迁移、不 seed、不写真实库。
-- 精度：
--   物料/库存/trace 数量与 BOM 数量使用 decimal(20,6)；作业请求量、前后值、
--   delta 和物料家谱的后续服务约束遵循 C04。容量换算及通用 number 属性使用
--   decimal(38,12)。MySQL DECIMAL 会舍入超出 scale 的小数；后续服务须在类型转换前
--   验证并拒绝超精度请求，本 DDL 不宣称已保证超精度拒绝。
-- 键与空值：
--   MATERIAL/CARRIER 节点恰有 inventory_id/container_id 之一且各自 UNIQUE。
--   storage_info.root_node_id UNIQUE；STORAGE 必须带 storage_id，其余三类必须为空。
--   occupancy_id由服务显式分配，无AUTO_INCREMENT；detail_key为其BIGINT生成别名，
--   active_occupancy_key为ACTIVE时的明细ID、CLOSED时NULL。active_marker为1/NULL，
--   配合storage/root/dimension/unit复合唯一精确防ACTIVE重复，CLOSED可共存。
--   六列事务明细唯一键约415字节、五列活动唯一键约405字节（含保守附加开销），
--   不依赖摘要或编码字符串；全部索引预算另见报告，均低于3072字节/16列。
--   effect_key复用WMS:+64位小写SHA-256格式；production属性可空，typed属性至多一值，
--   全NULL为显式NULL、缺行ABSENT。尺寸/BOM符号范围由后续注册属性/解析合同校验。
-- DDL 边界：
--   load_relation 只拒绝自环、child 唯一；父必须为 CARRIER、环和跨根由后续
--   锁内事务校验。storage_info 只保证根键唯一，不保证其节点确为根。trace 只提供
--   append-only 目标结构；成功事实与新反向 trace 同事务由后续服务保证。
-- 验收与冲突：
--   11 表必须完整存在并逐表、逐列、逐索引、逐 CHECK 完全匹配；缺表、同名 VIEW、
--   大小写错配、未知列/索引/CHECK、表选项漂移、本表 FK、任意 schema 指入 FK、
--   目标触发器、权限不可见或错误目标库均硬失败。只读结构，不 ALTER/删除/覆盖。
-- 离线账号合同：
--   直接持有全局 SELECT、TRIGGER，partial_revokes=OFF；schema/table/角色授权不满足
--   跨 schema 入向外键与触发器完整可见性证明。
-- 执行：
--   mysql --no-defaults --protocol=TCP --host=<主机> --port=<端口> \
--     --user=<账号> --default-character-set=utf8mb4 --database=innolux-iws \
--     --batch --raw < script/sql/iws_inventory_management_material_foundation_verify.sql
--   必须使用 stdin 或等价首错退出执行器，不得添加 --force。DDL 隐式提交；部分执行后
--   依据 INFORMATION_SCHEMA 重跑，不承诺整份脚本事务回滚。
-- ============================================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 集中参数
-- ----------------------------------------------------------------------------
SET @target_schema = 'innolux-iws';
SET @material_release_id = 'INV-P4-01-MATERIAL-FOUNDATION-20261029';
SET @material_contract_version = 'MATERIAL-11T-P4-01-20261029';
SET @expected_mysql_version = '8.0.45';
SET @expected_table_count = 11;
SET @target_schema_q = CONCAT('`', REPLACE(@target_schema, '`', '``'), '`');
SET @target_table_names = 'wms_production_info,wms_material_attribute_value,wms_load_node,wms_load_relation,wms_load_relation_history,wms_storage_info,wms_occupancy_effect,wms_storage_occupancy,wms_material_trace_history,wms_material_trace_item,wms_material_trace_edge';

-- 离线部署/验收账号契约：直接持有全局 SELECT、TRIGGER，且 partial_revokes=OFF。
SET @metadata_grantee = CONCAT(
    QUOTE(LEFT(CURRENT_USER(),
        CHAR_LENGTH(CURRENT_USER()) - CHAR_LENGTH(SUBSTRING_INDEX(CURRENT_USER(), '@', -1)) - 1)),
    '@', QUOTE(SUBSTRING_INDEX(CURRENT_USER(), '@', -1))
);
SET @metadata_visibility_ok = (
    @@GLOBAL.partial_revokes = 0
    AND (SELECT COUNT(DISTINCT PRIVILEGE_TYPE) = 2
         FROM information_schema.USER_PRIVILEGES
         WHERE CAST(GRANTEE AS BINARY) = CAST(@metadata_grantee AS BINARY)
           AND PRIVILEGE_TYPE IN ('SELECT', 'TRIGGER'))
);
SET @schema_ok = (
    DATABASE() IS NOT NULL
    AND CAST(DATABASE() AS BINARY) = CAST(@target_schema AS BINARY)
);
SET @schema_exists = (
    SELECT COUNT(*) = 1
    FROM information_schema.SCHEMATA
    WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@target_schema AS BINARY)
);
SET @version_ok = (
    CAST(VERSION() AS BINARY) = CAST(@expected_mysql_version AS BINARY)
    AND VERSION() NOT LIKE '%MariaDB%'
);

-- ----------------------------------------------------------------------------
-- 预期元数据：完整规范化行；不使用摘要替代字段、索引和 CHECK 比较
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_iws_material_expected_tables;
CREATE TEMPORARY TABLE tmp_iws_material_expected_tables (
    table_name       varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    table_type       varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    engine           varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    table_collation  varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    create_options   varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    table_comment    varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_material_expected_columns;
CREATE TEMPORARY TABLE tmp_iws_material_expected_columns (
    table_name         varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ordinal_position   int          NOT NULL,
    column_name        varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    column_type        varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    is_nullable        varchar(3)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    column_default     longtext     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    extra              varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    character_set_name varchar(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    collation_name     varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    generation_expression longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    column_comment     varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name, ordinal_position),
    UNIQUE KEY uk_tmp_iws_material_expected_columns (table_name, column_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_material_expected_indexes;
CREATE TEMPORARY TABLE tmp_iws_material_expected_indexes (
    table_name       varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    index_name       varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    non_unique       int          NOT NULL,
    seq_in_index     int          NOT NULL,
    column_name      varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    sub_part         bigint       NULL,
    packed           varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    nullable         varchar(3)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    index_type       varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    index_collation  varchar(3)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    index_expression longtext     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    is_visible       varchar(3)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    index_comment    varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name, index_name, seq_in_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_material_expected_checks;
CREATE TEMPORARY TABLE tmp_iws_material_expected_checks (
    table_name       varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    constraint_name  varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    check_clause     longtext    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    enforced         varchar(3)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name, constraint_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 以下完整预期行由目标 MySQL 8.0.45 的实际 INFORMATION_SCHEMA 生成并冻结。

INSERT INTO tmp_iws_material_expected_tables
    (table_name, table_type, engine, table_collation, create_options, table_comment)
VALUES
('wms_load_node','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS统一装载节点'),
('wms_load_relation','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS当前装载父子关系'),
('wms_load_relation_history','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS装载关系变更历史'),
('wms_material_attribute_value','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS库存单元可扩展属性值'),
('wms_material_trace_edge','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS物料生产家谱N:M边'),
('wms_material_trace_history','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS成功物料事实trace头'),
('wms_material_trace_item','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS物料事实逐物料项'),
('wms_occupancy_effect','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS储位占用事务效果头'),
('wms_production_info','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS库存单元生产信息'),
('wms_storage_info','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS装载根节点存放信息'),
('wms_storage_occupancy','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS物理储位占用明细');

INSERT INTO tmp_iws_material_expected_columns
    (table_name, ordinal_position, column_name, column_type, is_nullable, column_default,
     extra, character_set_name, collation_name, generation_expression, column_comment)
VALUES
('wms_load_node',1,'load_node_id','bigint','NO',NULL,'',NULL,NULL,'','统一装载节点主键，系统生成'),
('wms_load_node',2,'node_type','varchar(16)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','节点类型：MATERIAL/CARRIER'),
('wms_load_node',3,'inventory_id','bigint','YES',NULL,'',NULL,NULL,'','物料节点库存ID，逻辑关联 wms_inventory.inventory_id'),
('wms_load_node',4,'container_id','bigint','YES',NULL,'',NULL,NULL,'','载具节点容器ID，逻辑关联 wms_container.container_id'),
('wms_load_node',5,'status','varchar(32)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','节点状态，未闭合代码由后续目录约束'),
('wms_load_node',6,'revision','int','NO','1','',NULL,NULL,'','修订号'),
('wms_load_node',7,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_load_node',8,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_load_node',9,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_load_node',10,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_load_node',11,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_load_relation',1,'relation_id','bigint','NO',NULL,'',NULL,NULL,'','当前装载关系主键，系统生成'),
('wms_load_relation',2,'child_node_id','bigint','NO',NULL,'',NULL,NULL,'','子节点ID，逻辑关联 wms_load_node.load_node_id'),
('wms_load_relation',3,'parent_node_id','bigint','NO',NULL,'',NULL,NULL,'','父载具节点ID，逻辑关联 wms_load_node.load_node_id'),
('wms_load_relation',4,'revision','int','NO','1','',NULL,NULL,'','修订号'),
('wms_load_relation',5,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_load_relation',6,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_load_relation',7,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_load_relation',8,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_load_relation',9,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_load_relation_history',1,'relation_history_id','bigint','NO',NULL,'',NULL,NULL,'','装载关系历史主键，系统生成'),
('wms_load_relation_history',2,'child_node_id','bigint','NO',NULL,'',NULL,NULL,'','子节点ID快照'),
('wms_load_relation_history',3,'from_parent_node_id','bigint','YES',NULL,'',NULL,NULL,'','变更前父载具ID，空表示根节点'),
('wms_load_relation_history',4,'to_parent_node_id','bigint','YES',NULL,'',NULL,NULL,'','变更后父载具ID，空表示拆为根节点'),
('wms_load_relation_history',5,'relation_revision','int','NO',NULL,'',NULL,NULL,'','变更后关系修订号'),
('wms_load_relation_history',6,'effect_key','varchar(128)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','领域效果键WMS:SHA-256'),
('wms_load_relation_history',7,'business_type','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','父业务类型快照'),
('wms_load_relation_history',8,'business_id','bigint','NO',NULL,'',NULL,NULL,'','父业务内部ID快照'),
('wms_load_relation_history',9,'business_line_id','bigint','YES',NULL,'',NULL,NULL,'','父业务明细ID快照'),
('wms_load_relation_history',10,'task_key','varchar(96)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','WMS任务键'),
('wms_load_relation_history',11,'plan_instance_id','bigint','YES',NULL,'',NULL,NULL,'','计划实例ID'),
('wms_load_relation_history',12,'event_id','bigint','YES',NULL,'',NULL,NULL,'','计划事件ID'),
('wms_load_relation_history',13,'attempt_id','bigint','YES',NULL,'',NULL,NULL,'','计划尝试ID'),
('wms_load_relation_history',14,'operator_id','bigint','YES',NULL,'',NULL,NULL,'','实际操作者ID快照'),
('wms_load_relation_history',15,'operator_name','varchar(128)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','实际操作者名称快照'),
('wms_load_relation_history',16,'operator_department_id','bigint','YES',NULL,'',NULL,NULL,'','实际操作者部门ID快照'),
('wms_load_relation_history',17,'operator_department_name','varchar(128)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','实际操作者部门名称快照'),
('wms_load_relation_history',18,'occurred_at','datetime(3)','NO',NULL,'',NULL,NULL,'','关系业务发生时间'),
('wms_load_relation_history',19,'recorded_at','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','事实记录时间'),
('wms_load_relation_history',20,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_load_relation_history',21,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_load_relation_history',22,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_load_relation_history',23,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者，追加事实由服务禁止更新'),
('wms_load_relation_history',24,'update_time','datetime(3)','YES',NULL,'',NULL,NULL,'','更新时间，追加事实由服务禁止更新'),
('wms_material_attribute_value',1,'attribute_value_id','bigint','NO',NULL,'',NULL,NULL,'','物料属性值主键，系统生成'),
('wms_material_attribute_value',2,'production_info_id','bigint','NO',NULL,'',NULL,NULL,'','生产信息ID，逻辑关联 wms_production_info.production_info_id'),
('wms_material_attribute_value',3,'attribute_key','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','属性键，逻辑关联 wms_attribute_catalog.attribute_key'),
('wms_material_attribute_value',4,'seq_no','int','NO',NULL,'',NULL,NULL,'','同属性多值序号，从1开始'),
('wms_material_attribute_value',5,'value_text','longtext','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','文本属性值'),
('wms_material_attribute_value',6,'value_number','decimal(38,12)','YES',NULL,'',NULL,NULL,'','数值属性值'),
('wms_material_attribute_value',7,'value_date','date','YES',NULL,'',NULL,NULL,'','日期属性值'),
('wms_material_attribute_value',8,'value_datetime','datetime(3)','YES',NULL,'',NULL,NULL,'','日期时间属性值'),
('wms_material_attribute_value',9,'unit','varchar(32)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','数值属性单位'),
('wms_material_attribute_value',10,'source_ref','varchar(500)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','脱敏来源或证据引用'),
('wms_material_attribute_value',11,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_material_attribute_value',12,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_material_attribute_value',13,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_material_attribute_value',14,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_material_attribute_value',15,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_material_trace_edge',1,'trace_edge_id','bigint','NO',NULL,'',NULL,NULL,'','生产家谱边主键，系统生成'),
('wms_material_trace_edge',2,'trace_id','bigint','NO',NULL,'',NULL,NULL,'','所属物料事实trace头ID，逻辑关联 wms_material_trace_history.trace_id'),
('wms_material_trace_edge',3,'source_item_id','bigint','NO',NULL,'',NULL,NULL,'','来源物料项ID，逻辑关联 wms_material_trace_item.item_id'),
('wms_material_trace_edge',4,'target_item_id','bigint','NO',NULL,'',NULL,NULL,'','目标物料项ID，逻辑关联 wms_material_trace_item.item_id'),
('wms_material_trace_edge',5,'quantity','decimal(20,6)','NO',NULL,'',NULL,NULL,'','来源到目标的正物料数量'),
('wms_material_trace_edge',6,'unit','varchar(32)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','家谱数量单位'),
('wms_material_trace_edge',7,'group_no','int','NO',NULL,'',NULL,NULL,'','拆合关系组序号'),
('wms_material_trace_edge',8,'recorded_at','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','事实记录时间'),
('wms_material_trace_edge',9,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_material_trace_edge',10,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_material_trace_edge',11,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_material_trace_edge',12,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者，追加事实由服务禁止更新'),
('wms_material_trace_edge',13,'update_time','datetime(3)','YES',NULL,'',NULL,NULL,'','更新时间，追加事实由服务禁止更新'),
('wms_material_trace_history',1,'trace_id','bigint','NO',NULL,'',NULL,NULL,'','物料事实trace主键，系统生成'),
('wms_material_trace_history',2,'effect_key','varchar(128)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','领域效果键WMS:SHA-256'),
('wms_material_trace_history',3,'business_type','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','父业务类型稳定编码'),
('wms_material_trace_history',4,'business_name','varchar(128)','NO',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','父业务类型名称快照'),
('wms_material_trace_history',5,'business_id','bigint','NO',NULL,'',NULL,NULL,'','父业务内部ID'),
('wms_material_trace_history',6,'business_no','varchar(100)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','父业务号快照'),
('wms_material_trace_history',7,'business_line_id','bigint','YES',NULL,'',NULL,NULL,'','父业务明细ID'),
('wms_material_trace_history',8,'root_business_type','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','根父业务类型稳定编码'),
('wms_material_trace_history',9,'root_business_id','bigint','NO',NULL,'',NULL,NULL,'','根父业务内部ID'),
('wms_material_trace_history',10,'task_key','varchar(96)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','WMS任务键'),
('wms_material_trace_history',11,'plan_instance_id','bigint','YES',NULL,'',NULL,NULL,'','计划实例ID'),
('wms_material_trace_history',12,'plan_code','varchar(64)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','计划编码'),
('wms_material_trace_history',13,'plan_type','varchar(64)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','计划类型'),
('wms_material_trace_history',14,'definition_version','varchar(64)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','计划定义版本'),
('wms_material_trace_history',15,'event_id','bigint','YES',NULL,'',NULL,NULL,'','计划事件ID'),
('wms_material_trace_history',16,'attempt_id','bigint','YES',NULL,'',NULL,NULL,'','计划尝试ID'),
('wms_material_trace_history',17,'input_hash','char(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','规范化输入SHA-256小写十六进制'),
('wms_material_trace_history',18,'reverse_trace_id','bigint','YES',NULL,'',NULL,NULL,'','新COMPENSATION行引用被补偿的原trace ID，不更新原FACT'),
('wms_material_trace_history',19,'trace_kind','varchar(16)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','事实类型：FACT/COMPENSATION'),
('wms_material_trace_history',20,'effect_result','varchar(32)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','成功事实结果，未闭合代码由后续合同约束'),
('wms_material_trace_history',21,'reason','varchar(500)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','业务原因'),
('wms_material_trace_history',22,'evidence_ref','varchar(500)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','脱敏证据引用'),
('wms_material_trace_history',23,'actor_id','bigint','YES',NULL,'',NULL,NULL,'','实际操作者ID快照'),
('wms_material_trace_history',24,'actor_name','varchar(128)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','实际操作者名称快照'),
('wms_material_trace_history',25,'initiator_id','varchar(128)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','发起人标识快照'),
('wms_material_trace_history',26,'initiator_name','varchar(128)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','发起人名称快照'),
('wms_material_trace_history',27,'department_id','bigint','YES',NULL,'',NULL,NULL,'','操作者部门ID快照'),
('wms_material_trace_history',28,'department_name','varchar(128)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','操作者部门名称快照'),
('wms_material_trace_history',29,'execution_subject','varchar(16)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','执行主体快照，代码集待后续合同确定'),
('wms_material_trace_history',30,'execution_account','varchar(64)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','执行账号快照'),
('wms_material_trace_history',31,'on_behalf_of','varchar(128)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','授权代办身份快照'),
('wms_material_trace_history',32,'source_system','varchar(64)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','来源系统'),
('wms_material_trace_history',33,'terminal_id','varchar(128)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','来源终端标识'),
('wms_material_trace_history',34,'request_id','varchar(64)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','请求ID'),
('wms_material_trace_history',35,'source_trace_id','varchar(128)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','上游链路追踪ID'),
('wms_material_trace_history',36,'occurred_at','datetime(3)','NO',NULL,'',NULL,NULL,'','真实业务发生时间'),
('wms_material_trace_history',37,'recorded_at','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','事实记录时间'),
('wms_material_trace_history',38,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_material_trace_history',39,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_material_trace_history',40,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_material_trace_history',41,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者，追加事实由服务禁止更新'),
('wms_material_trace_history',42,'update_time','datetime(3)','YES',NULL,'',NULL,NULL,'','更新时间，追加事实由服务禁止更新'),
('wms_material_trace_item',1,'item_id','bigint','NO',NULL,'',NULL,NULL,'','trace物料项主键，系统生成'),
('wms_material_trace_item',2,'trace_id','bigint','NO',NULL,'',NULL,NULL,'','物料事实trace头ID，逻辑关联 wms_material_trace_history.trace_id'),
('wms_material_trace_item',3,'inventory_id','bigint','NO',NULL,'',NULL,NULL,'','库存单元ID，逻辑关联 wms_inventory.inventory_id'),
('wms_material_trace_item',4,'stock_unit_code','varchar(100)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','原始标签/库存单元编码快照'),
('wms_material_trace_item',5,'material_code','varchar(50)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','料号快照，可空，不伪造默认料号'),
('wms_material_trace_item',6,'production_info_id','bigint','YES',NULL,'',NULL,NULL,'','生产信息ID快照'),
('wms_material_trace_item',7,'production_revision','int','YES',NULL,'',NULL,NULL,'','生产信息修订号快照'),
('wms_material_trace_item',8,'production_snapshot','json','YES',NULL,'',NULL,NULL,'','事实发生时生产属性完整快照，后续服务构造并与事实同事务落盘'),
('wms_material_trace_item',9,'quantity','decimal(20,6)','YES',NULL,'',NULL,NULL,'','事实数量'),
('wms_material_trace_item',10,'unit','varchar(32)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','事实单位'),
('wms_material_trace_item',11,'before_qty','decimal(20,6)','YES',NULL,'',NULL,NULL,'','变更前数量，可为0'),
('wms_material_trace_item',12,'delta_qty','decimal(20,6)','YES',NULL,'',NULL,NULL,'','数量增量，可为负'),
('wms_material_trace_item',13,'after_qty','decimal(20,6)','YES',NULL,'',NULL,NULL,'','变更后数量，可为0'),
('wms_material_trace_item',14,'physical_from_storage_id','bigint','YES',NULL,'',NULL,NULL,'','物理源储位ID快照'),
('wms_material_trace_item',15,'physical_from_storage_code','varchar(64)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','物理源储位编码快照'),
('wms_material_trace_item',16,'physical_to_storage_id','bigint','YES',NULL,'',NULL,NULL,'','物理目标储位ID快照'),
('wms_material_trace_item',17,'physical_to_storage_code','varchar(64)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','物理目标储位编码快照'),
('wms_material_trace_item',18,'logical_from_scope_version_id','bigint','YES',NULL,'',NULL,NULL,'','变更前逻辑归属版本ID快照'),
('wms_material_trace_item',19,'logical_to_scope_version_id','bigint','YES',NULL,'',NULL,NULL,'','变更后逻辑归属版本ID快照'),
('wms_material_trace_item',20,'load_parent_before_id','bigint','YES',NULL,'',NULL,NULL,'','变更前装载父级节点ID快照'),
('wms_material_trace_item',21,'load_parent_after_id','bigint','YES',NULL,'',NULL,NULL,'','变更后装载父级节点ID快照'),
('wms_material_trace_item',22,'load_root_before_id','bigint','YES',NULL,'',NULL,NULL,'','变更前装载根节点ID快照'),
('wms_material_trace_item',23,'load_root_after_id','bigint','YES',NULL,'',NULL,NULL,'','变更后装载根节点ID快照'),
('wms_material_trace_item',24,'lifecycle_before','varchar(32)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','变更前生命周期快照'),
('wms_material_trace_item',25,'lifecycle_after','varchar(32)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','变更后生命周期快照'),
('wms_material_trace_item',26,'rule_package_version_id','bigint','YES',NULL,'',NULL,NULL,'','适用规则包版本ID快照'),
('wms_material_trace_item',27,'recorded_at','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','事实记录时间'),
('wms_material_trace_item',28,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_material_trace_item',29,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_material_trace_item',30,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_material_trace_item',31,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者，追加事实由服务禁止更新'),
('wms_material_trace_item',32,'update_time','datetime(3)','YES',NULL,'',NULL,NULL,'','更新时间，追加事实由服务禁止更新'),
('wms_occupancy_effect',1,'occupancy_effect_id','bigint','NO',NULL,'',NULL,NULL,'','占用事务头主键，系统生成'),
('wms_occupancy_effect',2,'effect_key','varchar(128)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','领域效果键WMS:SHA-256'),
('wms_occupancy_effect',3,'business_type','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','父业务类型快照'),
('wms_occupancy_effect',4,'business_id','bigint','NO',NULL,'',NULL,NULL,'','父业务内部ID快照'),
('wms_occupancy_effect',5,'business_line_id','bigint','YES',NULL,'',NULL,NULL,'','父业务明细ID快照'),
('wms_occupancy_effect',6,'task_key','varchar(96)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','WMS任务键'),
('wms_occupancy_effect',7,'plan_instance_id','bigint','YES',NULL,'',NULL,NULL,'','计划实例ID'),
('wms_occupancy_effect',8,'event_id','bigint','YES',NULL,'',NULL,NULL,'','计划事件ID'),
('wms_occupancy_effect',9,'attempt_id','bigint','YES',NULL,'',NULL,NULL,'','计划尝试ID'),
('wms_occupancy_effect',10,'trace_id','bigint','YES',NULL,'',NULL,NULL,'','物料trace事实ID，逻辑关联 wms_material_trace_history.trace_id'),
('wms_occupancy_effect',11,'effect_result','varchar(32)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','事务结果，未闭合代码由后续合同约束'),
('wms_occupancy_effect',12,'reason','varchar(500)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','业务原因或脱敏说明'),
('wms_occupancy_effect',13,'evidence_ref','varchar(500)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','脱敏证据引用'),
('wms_occupancy_effect',14,'occurred_at','datetime(3)','NO',NULL,'',NULL,NULL,'','业务发生时间'),
('wms_occupancy_effect',15,'recorded_at','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','事实记录时间'),
('wms_occupancy_effect',16,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_occupancy_effect',17,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_occupancy_effect',18,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_occupancy_effect',19,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者，追加事实由服务禁止更新'),
('wms_occupancy_effect',20,'update_time','datetime(3)','YES',NULL,'',NULL,NULL,'','更新时间，追加事实由服务禁止更新'),
('wms_production_info',1,'production_info_id','bigint','NO',NULL,'',NULL,NULL,'','生产信息主键，系统生成'),
('wms_production_info',2,'inventory_id','bigint','NO',NULL,'',NULL,NULL,'','稳定库存单元ID，逻辑关联 wms_inventory.inventory_id'),
('wms_production_info',3,'material_code','varchar(50)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','料号，逻辑关联 wms_material.material_code'),
('wms_production_info',4,'material_type','varchar(50)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','物料类别快照'),
('wms_production_info',5,'production_date','date','YES',NULL,'',NULL,NULL,'','生产日期'),
('wms_production_info',6,'material_batch','varchar(64)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','生产批次'),
('wms_production_info',7,'supplier_code','varchar(50)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','供应商编码快照'),
('wms_production_info',8,'supplier_name','varchar(100)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','供应商名称快照'),
('wms_production_info',9,'customer_code','varchar(64)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','客户编码快照'),
('wms_production_info',10,'customer_name','varchar(100)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','客户名称快照'),
('wms_production_info',11,'length_value','decimal(38,12)','YES',NULL,'',NULL,NULL,'','长度值'),
('wms_production_info',12,'width_value','decimal(38,12)','YES',NULL,'',NULL,NULL,'','宽度值'),
('wms_production_info',13,'height_value','decimal(38,12)','YES',NULL,'',NULL,NULL,'','高度值'),
('wms_production_info',14,'dimension_unit','varchar(16)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','尺寸单位'),
('wms_production_info',15,'bom_level','int','YES',NULL,'',NULL,NULL,'','BOM层级'),
('wms_production_info',16,'bom_qty','decimal(20,6)','YES',NULL,'',NULL,NULL,'','BOM用量，与库存数量分开'),
('wms_production_info',17,'bom_version','varchar(64)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','BOM版本'),
('wms_production_info',18,'finished_material_code','varchar(50)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','最终成品料号'),
('wms_production_info',19,'finished_material_type','varchar(50)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','最终成品类别'),
('wms_production_info',20,'parse_version','varchar(64)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','标签生产信息解析版本'),
('wms_production_info',21,'revision','int','NO','1','',NULL,NULL,'','修订号'),
('wms_production_info',22,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_production_info',23,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_production_info',24,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_production_info',25,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_production_info',26,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_storage_info',1,'storage_info_id','bigint','NO',NULL,'',NULL,NULL,'','根节点存放信息主键，系统生成'),
('wms_storage_info',2,'root_node_id','bigint','NO',NULL,'',NULL,NULL,'','装载根节点ID，逻辑关联 wms_load_node.load_node_id'),
('wms_storage_info',3,'location_kind','varchar(16)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','位置类型：STORAGE/OUTSIDE/IN_TRANSIT/UNKNOWN'),
('wms_storage_info',4,'storage_id','bigint','YES',NULL,'',NULL,NULL,'','物理储位ID，仅STORAGE逻辑关联 wms_storage.storage_id'),
('wms_storage_info',5,'revision','int','NO','1','',NULL,NULL,'','修订号'),
('wms_storage_info',6,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_storage_info',7,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_storage_info',8,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_storage_info',9,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_storage_info',10,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_storage_occupancy',1,'occupancy_id','bigint','NO',NULL,'',NULL,NULL,'','储位占用明细主键，服务显式分配，不使用AUTO_INCREMENT'),
('wms_storage_occupancy',2,'occupancy_effect_id','bigint','NO',NULL,'',NULL,NULL,'','占用事务头ID，逻辑关联 wms_occupancy_effect.occupancy_effect_id'),
('wms_storage_occupancy',3,'storage_id','bigint','NO',NULL,'',NULL,NULL,'','物理储位ID，逻辑关联 wms_storage.storage_id'),
('wms_storage_occupancy',4,'root_node_id','bigint','NO',NULL,'',NULL,NULL,'','装载根节点ID，逻辑关联 wms_load_node.load_node_id'),
('wms_storage_occupancy',5,'dimension_key','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','容量维度键'),
('wms_storage_occupancy',6,'occupied_qty','decimal(38,12)','NO',NULL,'',NULL,NULL,'','当前占用数量快照'),
('wms_storage_occupancy',7,'unit','varchar(32)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','占用单位'),
('wms_storage_occupancy',8,'generation','int','NO',NULL,'',NULL,NULL,'','同一根物理占用的代次'),
('wms_storage_occupancy',9,'status','varchar(16)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','状态：ACTIVE/CLOSED'),
('wms_storage_occupancy',10,'detail_key','bigint','NO',NULL,'STORED GENERATED',NULL,NULL,'`occupancy_id`','明细ID独立别名，组合事实由六列唯一键保证'),
('wms_storage_occupancy',11,'active_occupancy_key','bigint','YES',NULL,'STORED GENERATED',NULL,NULL,'if((`status` = _utf8mb4\\''ACTIVE\\''),`occupancy_id`,NULL)','活动明细ID别名，CLOSED为NULL'),
('wms_storage_occupancy',12,'active_marker','tinyint','YES',NULL,'STORED GENERATED',NULL,NULL,'if((`status` = _utf8mb4\\''ACTIVE\\''),1,NULL)','活动组合标志，NULL只豁免CLOSED历史'),
('wms_storage_occupancy',13,'revision','int','NO','1','',NULL,NULL,'','修订号'),
('wms_storage_occupancy',14,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_storage_occupancy',15,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_storage_occupancy',16,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_storage_occupancy',17,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_storage_occupancy',18,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间');

INSERT INTO tmp_iws_material_expected_indexes
    (table_name, index_name, non_unique, seq_in_index, column_name, sub_part, packed,
     nullable, index_type, index_collation, index_expression, is_visible, index_comment)
VALUES
('wms_load_node','idx_wms_load_node_type_status',1,1,'node_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_load_node','idx_wms_load_node_type_status',1,2,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_load_node','PRIMARY',0,1,'load_node_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_load_node','uk_wms_load_node_container',0,1,'container_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_load_node','uk_wms_load_node_inventory',0,1,'inventory_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_load_relation','idx_wms_load_relation_parent',1,1,'parent_node_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_load_relation','idx_wms_load_relation_parent',1,2,'child_node_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_load_relation','PRIMARY',0,1,'relation_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_load_relation','uk_wms_load_relation_child',0,1,'child_node_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_load_relation_history','idx_wms_load_relation_history_child_time',1,1,'child_node_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_load_relation_history','idx_wms_load_relation_history_child_time',1,2,'occurred_at',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_load_relation_history','idx_wms_load_relation_history_effect',1,1,'effect_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_load_relation_history','idx_wms_load_relation_history_instance',1,1,'plan_instance_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_load_relation_history','idx_wms_load_relation_history_task',1,1,'business_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_load_relation_history','idx_wms_load_relation_history_task',1,2,'business_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_load_relation_history','idx_wms_load_relation_history_task',1,3,'task_key',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_load_relation_history','PRIMARY',0,1,'relation_history_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_attribute_value','idx_wms_material_attribute_value_key',1,1,'attribute_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_attribute_value','idx_wms_material_attribute_value_key',1,2,'production_info_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_attribute_value','PRIMARY',0,1,'attribute_value_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_attribute_value','uk_wms_material_attribute_value_seq',0,1,'production_info_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_attribute_value','uk_wms_material_attribute_value_seq',0,2,'attribute_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_attribute_value','uk_wms_material_attribute_value_seq',0,3,'seq_no',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_trace_edge','idx_wms_material_trace_edge_source',1,1,'source_item_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_trace_edge','idx_wms_material_trace_edge_target',1,1,'target_item_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_trace_edge','PRIMARY',0,1,'trace_edge_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_trace_edge','uk_wms_material_trace_edge',0,1,'trace_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_trace_edge','uk_wms_material_trace_edge',0,2,'source_item_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_trace_edge','uk_wms_material_trace_edge',0,3,'target_item_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_trace_edge','uk_wms_material_trace_edge',0,4,'group_no',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_trace_history','idx_wms_material_trace_history_business',1,1,'business_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_trace_history','idx_wms_material_trace_history_business',1,2,'business_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_trace_history','idx_wms_material_trace_history_business',1,3,'occurred_at',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_trace_history','idx_wms_material_trace_history_instance',1,1,'plan_instance_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_material_trace_history','idx_wms_material_trace_history_reverse',1,1,'reverse_trace_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_material_trace_history','idx_wms_material_trace_history_task',1,1,'task_key',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_material_trace_history','idx_wms_material_trace_history_task',1,2,'occurred_at',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_trace_history','PRIMARY',0,1,'trace_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_trace_history','uk_wms_material_trace_history_effect',0,1,'effect_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_trace_item','idx_wms_material_trace_item_inventory_time',1,1,'inventory_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_trace_item','idx_wms_material_trace_item_inventory_time',1,2,'recorded_at',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_trace_item','idx_wms_material_trace_item_material',1,1,'material_code',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_material_trace_item','idx_wms_material_trace_item_material',1,2,'recorded_at',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_trace_item','idx_wms_material_trace_item_production',1,1,'production_info_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_material_trace_item','idx_wms_material_trace_item_trace',1,1,'trace_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_trace_item','idx_wms_material_trace_item_trace',1,2,'inventory_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_trace_item','PRIMARY',0,1,'item_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_occupancy_effect','idx_wms_occupancy_effect_business',1,1,'business_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_occupancy_effect','idx_wms_occupancy_effect_business',1,2,'business_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_occupancy_effect','idx_wms_occupancy_effect_business',1,3,'occurred_at',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_occupancy_effect','idx_wms_occupancy_effect_instance',1,1,'plan_instance_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_occupancy_effect','idx_wms_occupancy_effect_trace',1,1,'trace_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_occupancy_effect','PRIMARY',0,1,'occupancy_effect_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_occupancy_effect','uk_wms_occupancy_effect_effect',0,1,'effect_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_production_info','idx_wms_production_info_material_batch',1,1,'material_code',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_production_info','idx_wms_production_info_material_batch',1,2,'material_batch',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_production_info','idx_wms_production_info_material_batch',1,3,'production_date',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_production_info','PRIMARY',0,1,'production_info_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_production_info','uk_wms_production_info_inventory',0,1,'inventory_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_info','idx_wms_storage_info_location',1,1,'location_kind',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_info','idx_wms_storage_info_location',1,2,'storage_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_storage_info','idx_wms_storage_info_storage',1,1,'storage_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_storage_info','idx_wms_storage_info_storage',1,2,'root_node_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_info','PRIMARY',0,1,'storage_info_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_info','uk_wms_storage_info_root',0,1,'root_node_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_occupancy','idx_wms_storage_occupancy_effect',1,1,'occupancy_effect_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_occupancy','idx_wms_storage_occupancy_root_status',1,1,'root_node_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_occupancy','idx_wms_storage_occupancy_root_status',1,2,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_occupancy','idx_wms_storage_occupancy_storage_status',1,1,'storage_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_occupancy','idx_wms_storage_occupancy_storage_status',1,2,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_occupancy','idx_wms_storage_occupancy_storage_status',1,3,'dimension_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_occupancy','PRIMARY',0,1,'occupancy_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_occupancy','uk_wms_storage_occupancy_active_fact',0,1,'storage_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_occupancy','uk_wms_storage_occupancy_active_fact',0,2,'root_node_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_occupancy','uk_wms_storage_occupancy_active_fact',0,3,'dimension_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_occupancy','uk_wms_storage_occupancy_active_fact',0,4,'unit',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_occupancy','uk_wms_storage_occupancy_active_fact',0,5,'active_marker',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_storage_occupancy','uk_wms_storage_occupancy_active_key',0,1,'active_occupancy_key',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_storage_occupancy','uk_wms_storage_occupancy_detail',0,1,'detail_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_occupancy','uk_wms_storage_occupancy_fact',0,1,'occupancy_effect_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_occupancy','uk_wms_storage_occupancy_fact',0,2,'storage_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_occupancy','uk_wms_storage_occupancy_fact',0,3,'root_node_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_occupancy','uk_wms_storage_occupancy_fact',0,4,'dimension_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_occupancy','uk_wms_storage_occupancy_fact',0,5,'unit',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_occupancy','uk_wms_storage_occupancy_fact',0,6,'generation',NULL,NULL,'','BTREE','A',NULL,'YES','');

INSERT INTO tmp_iws_material_expected_checks
    (table_name, constraint_name, check_clause, enforced)
VALUES
('wms_load_node','ck_wms_load_node_exactly_one_ref','(((`inventory_id` is not null) + (`container_id` is not null)) = 1)','YES'),
('wms_load_node','ck_wms_load_node_revision','(`revision` >= 1)','YES'),
('wms_load_node','ck_wms_load_node_type','(`node_type` in (_utf8mb4\\''MATERIAL\\'',_utf8mb4\\''CARRIER\\''))','YES'),
('wms_load_node','ck_wms_load_node_type_ref','(((`node_type` = _utf8mb4\\''MATERIAL\\'') and (`inventory_id` is not null) and (`container_id` is null)) or ((`node_type` = _utf8mb4\\''CARRIER\\'') and (`inventory_id` is null) and (`container_id` is not null)))','YES'),
('wms_load_relation','ck_wms_load_relation_not_self','(`child_node_id` <> `parent_node_id`)','YES'),
('wms_load_relation','ck_wms_load_relation_revision','(`revision` >= 1)','YES'),
('wms_load_relation_history','ck_wms_load_relation_history_effect_format','regexp_like(`effect_key`,_utf8mb4\\''^WMS:[0-9a-f]{64}$\\'',_utf8mb4\\''c\\'')','YES'),
('wms_load_relation_history','ck_wms_load_relation_history_effect_length','(char_length(`effect_key`) <= 128)','YES'),
('wms_load_relation_history','ck_wms_load_relation_history_revision','(`relation_revision` >= 1)','YES'),
('wms_material_attribute_value','ck_wms_material_attribute_value_at_most_one_value','(((((`value_text` is not null) + (`value_number` is not null)) + (`value_date` is not null)) + (`value_datetime` is not null)) <= 1)','YES'),
('wms_material_attribute_value','ck_wms_material_attribute_value_seq','(`seq_no` >= 1)','YES'),
('wms_material_trace_edge','ck_wms_material_trace_edge_group','(`group_no` >= 1)','YES'),
('wms_material_trace_edge','ck_wms_material_trace_edge_qty','(`quantity` > 0)','YES'),
('wms_material_trace_history','ck_wms_material_trace_history_effect_format','regexp_like(`effect_key`,_utf8mb4\\''^WMS:[0-9a-f]{64}$\\'',_utf8mb4\\''c\\'')','YES'),
('wms_material_trace_history','ck_wms_material_trace_history_effect_length','(char_length(`effect_key`) <= 128)','YES'),
('wms_material_trace_history','ck_wms_material_trace_history_input_hash','regexp_like(`input_hash`,_utf8mb4\\''^[0-9a-f]{64}$\\'',_utf8mb4\\''c\\'')','YES'),
('wms_material_trace_history','ck_wms_material_trace_history_kind','(`trace_kind` in (_utf8mb4\\''FACT\\'',_utf8mb4\\''COMPENSATION\\''))','YES'),
('wms_material_trace_history','ck_wms_material_trace_history_reverse_not_self','((`reverse_trace_id` is null) or (`reverse_trace_id` <> `trace_id`))','YES'),
('wms_material_trace_history','ck_wms_material_trace_history_reverse_shape','(((`trace_kind` = _utf8mb4\\''FACT\\'') and (`reverse_trace_id` is null)) or ((`trace_kind` = _utf8mb4\\''COMPENSATION\\'') and (`reverse_trace_id` is not null)))','YES'),
('wms_material_trace_item','ck_wms_material_trace_item_after_qty','((`after_qty` is null) or (`after_qty` >= 0))','YES'),
('wms_material_trace_item','ck_wms_material_trace_item_before_qty','((`before_qty` is null) or (`before_qty` >= 0))','YES'),
('wms_material_trace_item','ck_wms_material_trace_item_production_revision','((`production_revision` is null) or (`production_revision` >= 1))','YES'),
('wms_material_trace_item','ck_wms_material_trace_item_quantity','((`quantity` is null) or (`quantity` >= 0))','YES'),
('wms_occupancy_effect','ck_wms_occupancy_effect_effect_format','regexp_like(`effect_key`,_utf8mb4\\''^WMS:[0-9a-f]{64}$\\'',_utf8mb4\\''c\\'')','YES'),
('wms_occupancy_effect','ck_wms_occupancy_effect_effect_length','(char_length(`effect_key`) <= 128)','YES'),
('wms_production_info','ck_wms_production_info_revision','(`revision` >= 1)','YES'),
('wms_storage_info','ck_wms_storage_info_location_kind','(`location_kind` in (_utf8mb4\\''STORAGE\\'',_utf8mb4\\''OUTSIDE\\'',_utf8mb4\\''IN_TRANSIT\\'',_utf8mb4\\''UNKNOWN\\''))','YES'),
('wms_storage_info','ck_wms_storage_info_revision','(`revision` >= 1)','YES'),
('wms_storage_info','ck_wms_storage_info_storage_ref','(((`location_kind` = _utf8mb4\\''STORAGE\\'') and (`storage_id` is not null)) or ((`location_kind` in (_utf8mb4\\''OUTSIDE\\'',_utf8mb4\\''IN_TRANSIT\\'',_utf8mb4\\''UNKNOWN\\'')) and (`storage_id` is null)))','YES'),
('wms_storage_occupancy','ck_wms_storage_occupancy_active_key','(((`status` = _utf8mb4\\''ACTIVE\\'') and (`active_occupancy_key` is not null)) or ((`status` = _utf8mb4\\''CLOSED\\'') and (`active_occupancy_key` is null)))','YES'),
('wms_storage_occupancy','ck_wms_storage_occupancy_generation','(`generation` >= 1)','YES'),
('wms_storage_occupancy','ck_wms_storage_occupancy_qty','(`occupied_qty` >= 0)','YES'),
('wms_storage_occupancy','ck_wms_storage_occupancy_revision','(`revision` >= 1)','YES'),
('wms_storage_occupancy','ck_wms_storage_occupancy_status','(`status` in (_utf8mb4\\''ACTIVE\\'',_utf8mb4\\''CLOSED\\''))','YES');

SET @existing_table_count = (
    SELECT COUNT(*)
    FROM information_schema.TABLES
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND FIND_IN_SET(
          CAST(TABLE_NAME AS BINARY),
          CAST(@target_table_names AS BINARY)
      ) > 0
      AND TABLE_TYPE = 'BASE TABLE'
);

-- ----------------------------------------------------------------------------
-- 后置验收：重新读取同一目标 INFORMATION_SCHEMA，与同一冻结预期逐行比较
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_iws_material_actual_tables;
CREATE TEMPORARY TABLE tmp_iws_material_actual_tables AS
SELECT TABLE_NAME AS table_name, TABLE_TYPE AS table_type, ENGINE AS engine,
       TABLE_COLLATION AS table_collation, CREATE_OPTIONS AS create_options,
       TABLE_COMMENT AS table_comment
FROM information_schema.TABLES
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_material_actual_columns;
CREATE TEMPORARY TABLE tmp_iws_material_actual_columns AS
SELECT TABLE_NAME AS table_name, ORDINAL_POSITION AS ordinal_position,
       COLUMN_NAME AS column_name, COLUMN_TYPE AS column_type,
       IS_NULLABLE AS is_nullable, COLUMN_DEFAULT AS column_default,
       EXTRA AS extra, CHARACTER_SET_NAME AS character_set_name,
       COLLATION_NAME AS collation_name, GENERATION_EXPRESSION AS generation_expression,
       COLUMN_COMMENT AS column_comment
FROM information_schema.COLUMNS
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_material_actual_indexes;
CREATE TEMPORARY TABLE tmp_iws_material_actual_indexes AS
SELECT TABLE_NAME AS table_name, INDEX_NAME AS index_name,
       NON_UNIQUE AS non_unique, SEQ_IN_INDEX AS seq_in_index,
       COLUMN_NAME AS column_name, SUB_PART AS sub_part, PACKED AS packed,
       NULLABLE AS nullable, INDEX_TYPE AS index_type, COLLATION AS index_collation,
       EXPRESSION AS index_expression, IS_VISIBLE AS is_visible,
       INDEX_COMMENT AS index_comment
FROM information_schema.STATISTICS
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_material_actual_checks;
CREATE TEMPORARY TABLE tmp_iws_material_actual_checks AS
SELECT tc.TABLE_NAME AS table_name, tc.CONSTRAINT_NAME AS constraint_name,
       cc.CHECK_CLAUSE AS check_clause, tc.ENFORCED AS enforced
FROM information_schema.TABLE_CONSTRAINTS tc
JOIN information_schema.CHECK_CONSTRAINTS cc
  ON CAST(cc.CONSTRAINT_SCHEMA AS BINARY) = CAST(tc.CONSTRAINT_SCHEMA AS BINARY)
 AND CAST(cc.CONSTRAINT_NAME AS BINARY) = CAST(tc.CONSTRAINT_NAME AS BINARY)
WHERE CAST(tc.CONSTRAINT_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND tc.CONSTRAINT_TYPE = 'CHECK'
  AND FIND_IN_SET(CAST(tc.TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

SET @post_base_table_count = (
    SELECT COUNT(*) FROM tmp_iws_material_actual_tables WHERE table_type = 'BASE TABLE'
);
SET @post_object_conflict_count = (
    SELECT COUNT(*) FROM tmp_iws_material_actual_tables WHERE table_type <> 'BASE TABLE'
);
SET @post_case_conflict_count = (
    SELECT COUNT(*)
    FROM information_schema.TABLES a
    JOIN tmp_iws_material_expected_tables e
      ON LOWER(a.TABLE_NAME) = LOWER(e.table_name)
    WHERE CAST(a.TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND CAST(a.TABLE_NAME AS BINARY) <> CAST(e.table_name AS BINARY)
);
SET @post_table_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_material_actual_tables a
    LEFT JOIN tmp_iws_material_expected_tables e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE e.table_name IS NULL
       OR NOT (CAST(a.table_type AS BINARY) <=> CAST(e.table_type AS BINARY))
       OR NOT (CAST(a.engine AS BINARY) <=> CAST(e.engine AS BINARY))
       OR NOT (CAST(a.table_collation AS BINARY) <=> CAST(e.table_collation AS BINARY))
       OR NOT (CAST(a.create_options AS BINARY) <=> CAST(e.create_options AS BINARY))
       OR NOT (CAST(a.table_comment AS BINARY) <=> CAST(e.table_comment AS BINARY))
);
SET @post_table_expected_missing = (
    SELECT COUNT(*) FROM tmp_iws_material_expected_tables e
    LEFT JOIN tmp_iws_material_actual_tables a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE a.table_name IS NULL
);
SET @post_column_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_material_actual_columns a
    LEFT JOIN tmp_iws_material_expected_columns e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE e.column_name IS NULL
       OR a.ordinal_position <> e.ordinal_position
       OR NOT (CAST(a.column_type AS BINARY) <=> CAST(e.column_type AS BINARY))
       OR NOT (CAST(a.is_nullable AS BINARY) <=> CAST(e.is_nullable AS BINARY))
       OR NOT (CAST(a.column_default AS BINARY) <=> CAST(e.column_default AS BINARY))
       OR NOT (CAST(a.extra AS BINARY) <=> CAST(e.extra AS BINARY))
       OR NOT (CAST(a.character_set_name AS BINARY) <=> CAST(e.character_set_name AS BINARY))
       OR NOT (CAST(a.collation_name AS BINARY) <=> CAST(e.collation_name AS BINARY))
       OR NOT (CAST(a.generation_expression AS BINARY) <=> CAST(e.generation_expression AS BINARY))
       OR NOT (CAST(a.column_comment AS BINARY) <=> CAST(e.column_comment AS BINARY))
);
SET @post_column_expected_missing = (
    SELECT COUNT(*) FROM tmp_iws_material_expected_columns e
    LEFT JOIN tmp_iws_material_actual_columns a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE a.column_name IS NULL
);
SET @post_index_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_material_actual_indexes a
    LEFT JOIN tmp_iws_material_expected_indexes e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE e.index_name IS NULL
       OR a.non_unique <> e.non_unique
       OR NOT (CAST(a.column_name AS BINARY) <=> CAST(e.column_name AS BINARY))
       OR NOT (a.sub_part <=> e.sub_part)
       OR NOT (CAST(a.packed AS BINARY) <=> CAST(e.packed AS BINARY))
       OR NOT (CAST(a.nullable AS BINARY) <=> CAST(e.nullable AS BINARY))
       OR NOT (CAST(a.index_type AS BINARY) <=> CAST(e.index_type AS BINARY))
       OR NOT (CAST(a.index_collation AS BINARY) <=> CAST(e.index_collation AS BINARY))
       OR NOT (CAST(a.index_expression AS BINARY) <=> CAST(e.index_expression AS BINARY))
       OR NOT (CAST(a.is_visible AS BINARY) <=> CAST(e.is_visible AS BINARY))
       OR NOT (CAST(a.index_comment AS BINARY) <=> CAST(e.index_comment AS BINARY))
);
SET @post_index_expected_missing = (
    SELECT COUNT(*) FROM tmp_iws_material_expected_indexes e
    LEFT JOIN tmp_iws_material_actual_indexes a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE a.index_name IS NULL
);
SET @post_check_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_material_actual_checks a
    LEFT JOIN tmp_iws_material_expected_checks e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE e.constraint_name IS NULL
       OR NOT (CAST(a.check_clause AS BINARY) <=> CAST(e.check_clause AS BINARY))
       OR NOT (CAST(a.enforced AS BINARY) <=> CAST(e.enforced AS BINARY))
);
SET @post_check_expected_missing = (
    SELECT COUNT(*) FROM tmp_iws_material_expected_checks e
    LEFT JOIN tmp_iws_material_actual_checks a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE a.constraint_name IS NULL
);
SET @post_own_fk_count = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CAST(CONSTRAINT_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0
      AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @post_trigger_count = (
    SELECT COUNT(*) FROM information_schema.TRIGGERS
    WHERE CAST(EVENT_OBJECT_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND FIND_IN_SET(CAST(EVENT_OBJECT_TABLE AS BINARY), CAST(@target_table_names AS BINARY)) > 0
);
SET @post_inbound_fk_count = (
    SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
    WHERE CAST(REFERENCED_TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND FIND_IN_SET(CAST(REFERENCED_TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0
);
SET @expected_table_rows = (SELECT COUNT(*) FROM tmp_iws_material_expected_tables);
SET @actual_table_rows = (SELECT COUNT(*) FROM tmp_iws_material_actual_tables);
SET @expected_column_rows = (SELECT COUNT(*) FROM tmp_iws_material_expected_columns);
SET @actual_column_rows = (SELECT COUNT(*) FROM tmp_iws_material_actual_columns);
SET @expected_index_rows = (SELECT COUNT(*) FROM tmp_iws_material_expected_indexes);
SET @actual_index_rows = (SELECT COUNT(*) FROM tmp_iws_material_actual_indexes);
SET @expected_check_rows = (SELECT COUNT(*) FROM tmp_iws_material_expected_checks);
SET @actual_check_rows = (SELECT COUNT(*) FROM tmp_iws_material_actual_checks);
SET @post_structure_ok = IF(
    @metadata_visibility_ok = 1 AND @schema_ok = 1 AND @version_ok = 1
    AND @post_base_table_count = @expected_table_count
    AND @actual_table_rows = @expected_table_rows
    AND @post_table_actual_extra = 0 AND @post_table_expected_missing = 0
    AND @actual_column_rows = @expected_column_rows
    AND @post_column_actual_extra = 0 AND @post_column_expected_missing = 0
    AND @actual_index_rows = @expected_index_rows
    AND @post_index_actual_extra = 0 AND @post_index_expected_missing = 0
    AND @actual_check_rows = @expected_check_rows
    AND @post_check_actual_extra = 0 AND @post_check_expected_missing = 0
    AND @post_object_conflict_count = 0 AND @post_case_conflict_count = 0
    AND @post_own_fk_count = 0 AND @post_trigger_count = 0
    AND @post_inbound_fk_count = 0,
    1, 0
);

SELECT
    @material_release_id AS `发布标识`,
    @existing_table_count AS `执行前已存在目标表数`,
    @post_base_table_count AS `执行后目标表数`,
    CONCAT(@actual_table_rows, '/', @expected_table_rows) AS `表元数据行`,
    CONCAT(@actual_column_rows, '/', @expected_column_rows) AS `列元数据行`,
    CONCAT(@actual_index_rows, '/', @expected_index_rows) AS `索引元数据行`,
    CONCAT(@actual_check_rows, '/', @expected_check_rows) AS `CHECK元数据行`,
    @post_table_actual_extra + @post_table_expected_missing AS `表元数据差异`,
    @post_column_actual_extra + @post_column_expected_missing AS `列元数据差异`,
    @post_index_actual_extra + @post_index_expected_missing AS `索引元数据差异`,
    @post_check_actual_extra + @post_check_expected_missing AS `CHECK元数据差异`,
    @post_object_conflict_count AS `同名非表对象数`,
    @post_case_conflict_count AS `目标名大小写冲突数`,
    @post_own_fk_count AS `本表外键数`,
    @post_trigger_count AS `触发器数`,
    @post_inbound_fk_count AS `外部指入外键数`,
    IF(@post_structure_ok = 1,
       '物料 foundation 11表结构验收通过；不回填、不迁移、不宣称 P4 完成',
       '物料 foundation 执行后结构验收失败') AS `执行结果`;
SET @structure_fail_fast = IF(
    @post_structure_ok = 1,
    0,
    CAST('invalid' AS JSON)
);
SELECT IF(@post_structure_ok = 1, 1,
          JSON_EXTRACT(JSON_QUOTE('invalid'), '$')) AS `物料foundation验收硬门禁`;

SELECT
    '索引上限核算' AS `检查项`,
    '1026/3072 字节、6/16 列' AS `结果`,
    '基于冻结元数据：uk_wms_storage_occupancy_detail 与 active_fact 最长' AS `说明`
UNION ALL SELECT
    '表边界', '11 张且仅本次 11 张',
    '不创建 object_audit/command_attempt/logical_assignment/history/carrier_aggregate/location_change_history'
UNION ALL SELECT
    '后续事务边界', '未宣称',
    '环/跨根/CARRIER父校验、容量实物判定、trace 成功同事务、append-only 权限由后续服务/Review验证';

DROP TEMPORARY TABLE IF EXISTS tmp_iws_material_actual_checks;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_material_actual_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_material_actual_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_material_actual_tables;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_material_expected_checks;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_material_expected_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_material_expected_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_material_expected_tables;
SET @post_cleanup_ok = IF(@post_structure_ok = 1, 1, @structure_fail_fast);
SELECT @post_cleanup_ok AS `物料foundation验收收尾门禁`;
