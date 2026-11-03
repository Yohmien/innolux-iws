-- ============================================================================
-- IWS+ 库存规则、逻辑归属与物料 DSL 首切片（INV-P3-01）
-- ============================================================================
-- 目标数据库：MySQL 8.0.45
-- 目标库参数：@target_schema，集中定义于下方；必须与连接 DATABASE() 字节一致
-- 目标对象：
--   wms_attribute_catalog            属性目录
--   wms_rule_definition              规则定义
--   wms_rule_version                 不可变规则内容版本
--   wms_rule_package_version         规则包版本
--   wms_rule_package_member          规则包成员
--   wms_rule_effective_pointer       规则包生效指针
--   wms_rule_seed_ledger             规则种子台账
--   wms_logical_scope                逻辑归属
--   wms_logical_scope_version        逻辑归属版本
--   wms_logical_scope_storage        逻辑归属储位集合
--   wms_material_logical_assignment  物料当前逻辑归属
--   wms_logical_assignment_history   逻辑归属变更历史
-- 依据：
--   work/inventory-p1-contract-20261027.md §5.1、§5.3、§6
--   主方案 §5、§6、§11、§12 P3 与 SQL 升级专题
-- 范围：
--   只新增不依赖 S2/S3 实际回填的数据结构。库存、储位、规则包等跨表 ID
--   保持逻辑引用，不建立物理外键，不创建库存、储位、规则种子、规则版本、
--   规则包、成员、生效指针或角色授权数据。
-- DDL 边界：
--   唯一键和 CHECK 约束负责数据库可判定的事实。ACTIVE 内容不可原位修改、
--   发布 CAS、同 `(seed_code, seed_version)` 异 canonical_digest 拒绝、摘要同值
--   跳过及退役历史保留，必须由后续服务事务和执行器实现；本 DDL 不宣称完成。
-- 幂等与冲突：
--   先读取十二张目标表的完整元数据，与脚本内预期逐行集合比较。全部缺失、
--   部分目标表已满足或全部目标表已满足可继续；同名 VIEW、列、索引、表选项、
--   CHECK、本表外键、目标表触发器、外部指入外键、权限不可见或错误目标库
--   任一冲突时，在首个 DDL 前硬失败。任何表结构不做自动 ALTER、删除或覆盖。
-- 执行：
--   mysql --no-defaults --protocol=TCP --host=<主机> --port=<端口> \
--     --user=<账号> --default-character-set=utf8mb4 --database=innolux-iws \
--     --batch --raw < script/sql/iws_inventory_management_rules.sql
--   必须使用 stdin 或等价首错退出执行器，不得添加 --force。DDL 隐式提交；
--   部分执行后依据 INFORMATION_SCHEMA 重跑，不承诺整份脚本事务回滚。
-- ============================================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 集中参数
-- ----------------------------------------------------------------------------
SET @target_schema = 'innolux-iws';
SET @rules_release_id = 'INV-P3-01-20261028';
SET @rules_contract_version = 'RULES-12T-P3-01-20261028';
SET @expected_mysql_version = '8.0.45';
SET @expected_table_count = 12;
SET @target_schema_q = CONCAT('`', REPLACE(@target_schema, '`', '``'), '`');

SET @target_table_names = 'wms_attribute_catalog,wms_rule_definition,wms_rule_version,wms_rule_package_version,wms_rule_package_member,wms_rule_effective_pointer,wms_rule_seed_ledger,wms_logical_scope,wms_logical_scope_version,wms_logical_scope_storage,wms_material_logical_assignment,wms_logical_assignment_history';

-- 离线部署/验收账号契约：直接持有全局 SELECT、TRIGGER，且 partial_revokes=OFF。
-- schema/table/角色授权不能替代；脚本不读取 mysql 授权表或输出凭据。
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
-- 预期元数据：每个对象保存完整规范化行；不使用 GROUP_CONCAT 或摘要代替比较
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_expected_tables;
CREATE TEMPORARY TABLE tmp_iws_rules_expected_tables (
    table_name       varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    table_type       varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    engine           varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    table_collation  varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    create_options   varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    table_comment    varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_expected_columns;
CREATE TEMPORARY TABLE tmp_iws_rules_expected_columns (
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
    UNIQUE KEY uk_tmp_iws_rules_expected_columns (table_name, column_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_expected_indexes;
CREATE TEMPORARY TABLE tmp_iws_rules_expected_indexes (
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

DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_expected_checks;
CREATE TEMPORARY TABLE tmp_iws_rules_expected_checks (
    table_name    varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    constraint_name varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    check_clause  longtext     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    enforced      varchar(3)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name, constraint_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 以下完整预期行由目标 MySQL 8.0.45 的 INFORMATION_SCHEMA 生成并冻结。
INSERT INTO tmp_iws_rules_expected_tables
    (table_name, table_type, engine, table_collation, create_options, table_comment)
VALUES
('wms_attribute_catalog','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS属性目录'),
('wms_logical_assignment_history','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS逻辑归属变更历史'),
('wms_logical_scope','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS逻辑归属'),
('wms_logical_scope_storage','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS逻辑归属储位集合'),
('wms_logical_scope_version','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS逻辑归属版本'),
('wms_material_logical_assignment','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS物料当前逻辑归属'),
('wms_rule_definition','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS规则定义'),
('wms_rule_effective_pointer','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS规则包生效指针'),
('wms_rule_package_member','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS规则包成员'),
('wms_rule_package_version','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS规则包版本'),
('wms_rule_seed_ledger','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS规则种子台账'),
('wms_rule_version','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS规则内容版本');

INSERT INTO tmp_iws_rules_expected_columns
    (table_name, ordinal_position, column_name, column_type, is_nullable, column_default,
     extra, character_set_name, collation_name, generation_expression, column_comment)
VALUES
('wms_attribute_catalog',1,'attribute_key','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','属性键'),
('wms_attribute_catalog',2,'source_type','varchar(32)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','来源类型'),
('wms_attribute_catalog',3,'value_type','varchar(32)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','值类型'),
('wms_attribute_catalog',4,'cardinality','varchar(16)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','基数'),
('wms_attribute_catalog',5,'operators_json','json','NO',NULL,'',NULL,NULL,'','允许操作符'),
('wms_attribute_catalog',6,'unit','varchar(32)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','单位'),
('wms_attribute_catalog',7,'scale','int','YES',NULL,'',NULL,NULL,'','小数位'),
('wms_attribute_catalog',8,'groupable','tinyint(1)','NO','0','',NULL,NULL,'','是否可分组'),
('wms_attribute_catalog',9,'status','varchar(16)','NO','ACTIVE','','utf8mb4','utf8mb4_0900_bin','','状态：ACTIVE/DISABLED'),
('wms_attribute_catalog',10,'contract_version','varchar(32)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','属性合同版本'),
('wms_attribute_catalog',11,'revision','int','NO','1','',NULL,NULL,'','修订号'),
('wms_attribute_catalog',12,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_attribute_catalog',13,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_attribute_catalog',14,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_attribute_catalog',15,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_attribute_catalog',16,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_logical_assignment_history',1,'logical_assignment_history_id','bigint','NO',NULL,'auto_increment',NULL,NULL,'','逻辑归属历史主键'),
('wms_logical_assignment_history',2,'inventory_id','bigint','NO',NULL,'',NULL,NULL,'','稳定库存ID'),
('wms_logical_assignment_history',3,'from_scope_version_id','bigint','YES',NULL,'',NULL,NULL,'','变更前逻辑归属版本ID'),
('wms_logical_assignment_history',4,'to_scope_version_id','bigint','NO',NULL,'',NULL,NULL,'','变更后逻辑归属版本ID'),
('wms_logical_assignment_history',5,'task_key','varchar(96)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','WMS任务键'),
('wms_logical_assignment_history',6,'plan_instance_id','bigint','YES',NULL,'',NULL,NULL,'','计划实例ID'),
('wms_logical_assignment_history',7,'effect_key','varchar(128)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','领域效果键WMS:SHA-256'),
('wms_logical_assignment_history',8,'operator_id','bigint','YES',NULL,'',NULL,NULL,'','操作者ID快照'),
('wms_logical_assignment_history',9,'operator_name','varchar(128)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','操作者名称快照'),
('wms_logical_assignment_history',10,'operator_department_id','bigint','YES',NULL,'',NULL,NULL,'','操作者部门ID快照'),
('wms_logical_assignment_history',11,'operator_department_name','varchar(128)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','操作者部门名称快照'),
('wms_logical_assignment_history',12,'occurred_at','datetime(3)','NO',NULL,'',NULL,NULL,'','业务发生时间'),
('wms_logical_assignment_history',13,'recorded_at','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','事实记录时间'),
('wms_logical_assignment_history',14,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_logical_assignment_history',15,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_logical_assignment_history',16,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_logical_scope',1,'logical_scope_id','bigint','NO',NULL,'auto_increment',NULL,NULL,'','逻辑归属主键'),
('wms_logical_scope',2,'scope_code','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','逻辑归属编码'),
('wms_logical_scope',3,'scope_name','varchar(128)','NO',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','逻辑归属名称'),
('wms_logical_scope',4,'lifecycle_status','varchar(16)','NO','ACTIVE','','utf8mb4','utf8mb4_0900_bin','','生命周期：ACTIVE/RETIRING/RETIRED'),
('wms_logical_scope',5,'current_version_id','bigint','YES',NULL,'',NULL,NULL,'','当前版本ID'),
('wms_logical_scope',6,'revision','int','NO','1','',NULL,NULL,'','修订号'),
('wms_logical_scope',7,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_logical_scope',8,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_logical_scope',9,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_logical_scope',10,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_logical_scope',11,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_logical_scope_storage',1,'logical_scope_storage_id','bigint','NO',NULL,'auto_increment',NULL,NULL,'','逻辑归属储位关系主键'),
('wms_logical_scope_storage',2,'scope_version_id','bigint','NO',NULL,'',NULL,NULL,'','逻辑归属版本ID'),
('wms_logical_scope_storage',3,'storage_id','bigint','NO',NULL,'',NULL,NULL,'','稳定储位ID'),
('wms_logical_scope_storage',4,'revision','int','NO','1','',NULL,NULL,'','修订号'),
('wms_logical_scope_storage',5,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_logical_scope_storage',6,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_logical_scope_storage',7,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_logical_scope_storage',8,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_logical_scope_storage',9,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_logical_scope_version',1,'scope_version_id','bigint','NO',NULL,'auto_increment',NULL,NULL,'','逻辑归属版本主键'),
('wms_logical_scope_version',2,'logical_scope_id','bigint','NO',NULL,'',NULL,NULL,'','逻辑归属ID'),
('wms_logical_scope_version',3,'version_no','int','NO',NULL,'',NULL,NULL,'','版本号'),
('wms_logical_scope_version',4,'rule_package_version_id','bigint','NO',NULL,'',NULL,NULL,'','规则包版本ID'),
('wms_logical_scope_version',5,'space_snapshot_digest','char(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','空间快照SHA-256小写十六进制'),
('wms_logical_scope_version',6,'status','varchar(16)','NO','DRAFT','','utf8mb4','utf8mb4_0900_bin','','状态：DRAFT/ACTIVE/RETIRED'),
('wms_logical_scope_version',7,'published_at','datetime(3)','YES',NULL,'',NULL,NULL,'','发布时间'),
('wms_logical_scope_version',8,'published_by','bigint','YES',NULL,'',NULL,NULL,'','发布人'),
('wms_logical_scope_version',9,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_logical_scope_version',10,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_logical_scope_version',11,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_logical_scope_version',12,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_logical_scope_version',13,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_material_logical_assignment',1,'logical_assignment_id','bigint','NO',NULL,'auto_increment',NULL,NULL,'','物料逻辑归属主键'),
('wms_material_logical_assignment',2,'inventory_id','bigint','NO',NULL,'',NULL,NULL,'','稳定库存ID'),
('wms_material_logical_assignment',3,'scope_version_id','bigint','NO',NULL,'',NULL,NULL,'','逻辑归属版本ID'),
('wms_material_logical_assignment',4,'assignment_revision','int','NO','1','',NULL,NULL,'','归属修订号'),
('wms_material_logical_assignment',5,'source_business_type','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','来源业务类型'),
('wms_material_logical_assignment',6,'source_business_id','bigint','NO',NULL,'',NULL,NULL,'','来源业务内部ID'),
('wms_material_logical_assignment',7,'revision','int','NO','1','',NULL,NULL,'','对象修订号'),
('wms_material_logical_assignment',8,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_material_logical_assignment',9,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_material_logical_assignment',10,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_material_logical_assignment',11,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_material_logical_assignment',12,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_rule_definition',1,'rule_definition_id','bigint','NO',NULL,'auto_increment',NULL,NULL,'','规则定义主键'),
('wms_rule_definition',2,'rule_key','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','规则键'),
('wms_rule_definition',3,'purpose','varchar(32)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','用途'),
('wms_rule_definition',4,'rule_name','varchar(128)','NO',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','规则名称'),
('wms_rule_definition',5,'scope_type','varchar(32)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','作用域类型'),
('wms_rule_definition',6,'status','varchar(16)','NO','ACTIVE','','utf8mb4','utf8mb4_0900_bin','','状态：ACTIVE/DISABLED'),
('wms_rule_definition',7,'current_version_id','bigint','YES',NULL,'',NULL,NULL,'','当前版本ID'),
('wms_rule_definition',8,'revision','int','NO','1','',NULL,NULL,'','修订号'),
('wms_rule_definition',9,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_rule_definition',10,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_rule_definition',11,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_rule_definition',12,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_rule_definition',13,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_rule_effective_pointer',1,'rule_effective_pointer_id','bigint','NO',NULL,'auto_increment',NULL,NULL,'','生效指针主键'),
('wms_rule_effective_pointer',2,'scope_type','varchar(32)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','作用域类型'),
('wms_rule_effective_pointer',3,'scope_id','bigint','NO',NULL,'',NULL,NULL,'','作用域内部ID'),
('wms_rule_effective_pointer',4,'package_version_id','bigint','NO',NULL,'',NULL,NULL,'','规则包版本ID'),
('wms_rule_effective_pointer',5,'revision','int','NO','1','',NULL,NULL,'','CAS修订号'),
('wms_rule_effective_pointer',6,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_rule_effective_pointer',7,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_rule_effective_pointer',8,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_rule_effective_pointer',9,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_rule_effective_pointer',10,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_rule_package_member',1,'package_member_id','bigint','NO',NULL,'auto_increment',NULL,NULL,'','规则包成员主键'),
('wms_rule_package_member',2,'package_version_id','bigint','NO',NULL,'',NULL,NULL,'','规则包版本ID'),
('wms_rule_package_member',3,'purpose','varchar(32)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','成员用途'),
('wms_rule_package_member',4,'rule_version_id','bigint','NO',NULL,'',NULL,NULL,'','规则版本ID'),
('wms_rule_package_member',5,'revision','int','NO','1','',NULL,NULL,'','修订号'),
('wms_rule_package_member',6,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_rule_package_member',7,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_rule_package_member',8,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_rule_package_member',9,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_rule_package_member',10,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_rule_package_version',1,'package_version_id','bigint','NO',NULL,'auto_increment',NULL,NULL,'','规则包版本主键'),
('wms_rule_package_version',2,'package_code','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','规则包编码'),
('wms_rule_package_version',3,'version_no','int','NO',NULL,'',NULL,NULL,'','版本号'),
('wms_rule_package_version',4,'canonical_digest','char(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','规范化内容SHA-256小写十六进制'),
('wms_rule_package_version',5,'status','varchar(16)','NO','DRAFT','','utf8mb4','utf8mb4_0900_bin','','状态：DRAFT/ACTIVE/RETIRED'),
('wms_rule_package_version',6,'published_at','datetime(3)','YES',NULL,'',NULL,NULL,'','发布时间'),
('wms_rule_package_version',7,'published_by','bigint','YES',NULL,'',NULL,NULL,'','发布人'),
('wms_rule_package_version',8,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_rule_package_version',9,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_rule_package_version',10,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_rule_package_version',11,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_rule_package_version',12,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_rule_seed_ledger',1,'rule_seed_ledger_id','bigint','NO',NULL,'auto_increment',NULL,NULL,'','种子台账主键'),
('wms_rule_seed_ledger',2,'seed_code','varchar(128)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','种子编码'),
('wms_rule_seed_ledger',3,'seed_version','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','种子版本'),
('wms_rule_seed_ledger',4,'canonical_digest','char(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','规范化内容SHA-256小写十六进制'),
('wms_rule_seed_ledger',5,'status','varchar(16)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','状态：APPLIED'),
('wms_rule_seed_ledger',6,'applied_at','datetime(3)','NO',NULL,'',NULL,NULL,'','应用时间'),
('wms_rule_seed_ledger',7,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_rule_seed_ledger',8,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_rule_seed_ledger',9,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_rule_seed_ledger',10,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_rule_seed_ledger',11,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_rule_version',1,'rule_version_id','bigint','NO',NULL,'auto_increment',NULL,NULL,'','规则版本主键'),
('wms_rule_version',2,'rule_definition_id','bigint','NO',NULL,'',NULL,NULL,'','规则定义ID'),
('wms_rule_version',3,'version_no','int','NO',NULL,'',NULL,NULL,'','版本号'),
('wms_rule_version',4,'condition_json','json','NO',NULL,'',NULL,NULL,'','条件表达式'),
('wms_rule_version',5,'result_json','json','NO',NULL,'',NULL,NULL,'','结果表达式'),
('wms_rule_version',6,'attribute_bindings_json','json','YES',NULL,'',NULL,NULL,'','服务端属性绑定、用途和限额快照'),
('wms_rule_version',7,'canonical_digest','char(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','规范化内容SHA-256小写十六进制'),
('wms_rule_version',8,'status','varchar(16)','NO','DRAFT','','utf8mb4','utf8mb4_0900_bin','','状态：DRAFT/ACTIVE/RETIRED'),
('wms_rule_version',9,'published_at','datetime(3)','YES',NULL,'',NULL,NULL,'','发布时间'),
('wms_rule_version',10,'published_by','bigint','YES',NULL,'',NULL,NULL,'','发布人'),
('wms_rule_version',11,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_rule_version',12,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_rule_version',13,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_rule_version',14,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_rule_version',15,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间');

INSERT INTO tmp_iws_rules_expected_indexes
    (table_name, index_name, non_unique, seq_in_index, column_name, sub_part, packed,
     nullable, index_type, index_collation, index_expression, is_visible, index_comment)
VALUES
('wms_attribute_catalog','idx_wms_attribute_catalog_status',1,1,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_attribute_catalog','idx_wms_attribute_catalog_status',1,2,'source_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_attribute_catalog','PRIMARY',0,1,'attribute_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_logical_assignment_history','idx_wms_logical_assignment_history_instance',1,1,'plan_instance_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_logical_assignment_history','idx_wms_logical_assignment_history_inventory_time',1,1,'inventory_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_logical_assignment_history','idx_wms_logical_assignment_history_inventory_time',1,2,'occurred_at',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_logical_assignment_history','idx_wms_logical_assignment_history_to_scope',1,1,'to_scope_version_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_logical_assignment_history','PRIMARY',0,1,'logical_assignment_history_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_logical_assignment_history','uk_wms_logical_assignment_history_effect',0,1,'effect_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_logical_scope','idx_wms_logical_scope_current_version',1,1,'current_version_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_logical_scope','idx_wms_logical_scope_status',1,1,'lifecycle_status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_logical_scope','idx_wms_logical_scope_status',1,2,'scope_code',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_logical_scope','PRIMARY',0,1,'logical_scope_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_logical_scope','uk_wms_logical_scope_code',0,1,'scope_code',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_logical_scope_storage','idx_wms_logical_scope_storage_storage',1,1,'storage_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_logical_scope_storage','idx_wms_logical_scope_storage_storage',1,2,'scope_version_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_logical_scope_storage','PRIMARY',0,1,'logical_scope_storage_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_logical_scope_storage','uk_wms_logical_scope_storage',0,1,'scope_version_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_logical_scope_storage','uk_wms_logical_scope_storage',0,2,'storage_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_logical_scope_version','idx_wms_logical_scope_version_package',1,1,'rule_package_version_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_logical_scope_version','idx_wms_logical_scope_version_package',1,2,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_logical_scope_version','PRIMARY',0,1,'scope_version_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_logical_scope_version','uk_wms_logical_scope_version_owner_version',0,1,'logical_scope_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_logical_scope_version','uk_wms_logical_scope_version_owner_version',0,2,'version_no',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_logical_assignment','idx_wms_material_logical_assignment_scope',1,1,'scope_version_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_logical_assignment','idx_wms_material_logical_assignment_source',1,1,'source_business_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_logical_assignment','idx_wms_material_logical_assignment_source',1,2,'source_business_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_logical_assignment','PRIMARY',0,1,'logical_assignment_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_material_logical_assignment','uk_wms_material_logical_assignment_inventory',0,1,'inventory_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_definition','idx_wms_rule_definition_current_version',1,1,'current_version_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_rule_definition','idx_wms_rule_definition_resolve',1,1,'purpose',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_definition','idx_wms_rule_definition_resolve',1,2,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_definition','PRIMARY',0,1,'rule_definition_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_definition','uk_wms_rule_definition_key',0,1,'rule_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_effective_pointer','idx_wms_rule_effective_pointer_package',1,1,'package_version_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_effective_pointer','PRIMARY',0,1,'rule_effective_pointer_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_effective_pointer','uk_wms_rule_effective_pointer_scope',0,1,'scope_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_effective_pointer','uk_wms_rule_effective_pointer_scope',0,2,'scope_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_package_member','idx_wms_rule_package_member_rule',1,1,'rule_version_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_package_member','PRIMARY',0,1,'package_member_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_package_member','uk_wms_rule_package_member_purpose',0,1,'package_version_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_package_member','uk_wms_rule_package_member_purpose',0,2,'purpose',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_package_version','idx_wms_rule_package_version_digest',1,1,'canonical_digest',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_package_version','idx_wms_rule_package_version_status',1,1,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_package_version','idx_wms_rule_package_version_status',1,2,'package_code',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_package_version','PRIMARY',0,1,'package_version_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_package_version','uk_wms_rule_package_version_code_version',0,1,'package_code',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_package_version','uk_wms_rule_package_version_code_version',0,2,'version_no',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_seed_ledger','idx_wms_rule_seed_ledger_status',1,1,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_seed_ledger','idx_wms_rule_seed_ledger_status',1,2,'applied_at',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_seed_ledger','PRIMARY',0,1,'rule_seed_ledger_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_seed_ledger','uk_wms_rule_seed_ledger_code_version',0,1,'seed_code',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_seed_ledger','uk_wms_rule_seed_ledger_code_version',0,2,'seed_version',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_version','idx_wms_rule_version_digest',1,1,'canonical_digest',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_version','idx_wms_rule_version_status',1,1,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_version','idx_wms_rule_version_status',1,2,'rule_definition_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_version','PRIMARY',0,1,'rule_version_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_version','uk_wms_rule_version_owner_version',0,1,'rule_definition_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_rule_version','uk_wms_rule_version_owner_version',0,2,'version_no',NULL,NULL,'','BTREE','A',NULL,'YES','');

INSERT INTO tmp_iws_rules_expected_checks
    (table_name, constraint_name, check_clause, enforced)
VALUES
('wms_attribute_catalog','ck_wms_attribute_catalog_groupable','(`groupable` in (0,1))','YES'),
('wms_attribute_catalog','ck_wms_attribute_catalog_revision','(`revision` >= 1)','YES'),
('wms_attribute_catalog','ck_wms_attribute_catalog_scale','((`scale` is null) or (`scale` >= 0))','YES'),
('wms_attribute_catalog','ck_wms_attribute_catalog_status','(`status` in (_utf8mb4\\\'ACTIVE\\\',_utf8mb4\\\'DISABLED\\\'))','YES'),
('wms_logical_assignment_history','ck_wms_logical_assignment_history_effect_format','regexp_like(`effect_key`,_utf8mb4\\\'^WMS:[0-9a-f]{64}$\\\',_utf8mb4\\\'c\\\')','YES'),
('wms_logical_assignment_history','ck_wms_logical_assignment_history_effect_length','(char_length(`effect_key`) <= 128)','YES'),
('wms_logical_scope','ck_wms_logical_scope_lifecycle_status','(`lifecycle_status` in (_utf8mb4\\\'ACTIVE\\\',_utf8mb4\\\'RETIRING\\\',_utf8mb4\\\'RETIRED\\\'))','YES'),
('wms_logical_scope','ck_wms_logical_scope_revision','(`revision` >= 1)','YES'),
('wms_logical_scope_storage','ck_wms_logical_scope_storage_revision','(`revision` >= 1)','YES'),
('wms_logical_scope_version','ck_wms_logical_scope_version_digest','regexp_like(`space_snapshot_digest`,_utf8mb4\\\'^[0-9a-f]{64}$\\\',_utf8mb4\\\'c\\\')','YES'),
('wms_logical_scope_version','ck_wms_logical_scope_version_status','(`status` in (_utf8mb4\\\'DRAFT\\\',_utf8mb4\\\'ACTIVE\\\',_utf8mb4\\\'RETIRED\\\'))','YES'),
('wms_logical_scope_version','ck_wms_logical_scope_version_version_no','(`version_no` >= 1)','YES'),
('wms_material_logical_assignment','ck_wms_material_logical_assignment_assignment_revision','(`assignment_revision` >= 1)','YES'),
('wms_material_logical_assignment','ck_wms_material_logical_assignment_revision','(`revision` >= 1)','YES'),
('wms_rule_definition','ck_wms_rule_definition_revision','(`revision` >= 1)','YES'),
('wms_rule_definition','ck_wms_rule_definition_status','(`status` in (_utf8mb4\\\'ACTIVE\\\',_utf8mb4\\\'DISABLED\\\'))','YES'),
('wms_rule_effective_pointer','ck_wms_rule_effective_pointer_revision','(`revision` >= 1)','YES'),
('wms_rule_package_member','ck_wms_rule_package_member_revision','(`revision` >= 1)','YES'),
('wms_rule_package_version','ck_wms_rule_package_version_digest','regexp_like(`canonical_digest`,_utf8mb4\\\'^[0-9a-f]{64}$\\\',_utf8mb4\\\'c\\\')','YES'),
('wms_rule_package_version','ck_wms_rule_package_version_status','(`status` in (_utf8mb4\\\'DRAFT\\\',_utf8mb4\\\'ACTIVE\\\',_utf8mb4\\\'RETIRED\\\'))','YES'),
('wms_rule_package_version','ck_wms_rule_package_version_version_no','(`version_no` >= 1)','YES'),
('wms_rule_seed_ledger','ck_wms_rule_seed_ledger_digest','regexp_like(`canonical_digest`,_utf8mb4\\\'^[0-9a-f]{64}$\\\',_utf8mb4\\\'c\\\')','YES'),
('wms_rule_seed_ledger','ck_wms_rule_seed_ledger_status','(`status` = _utf8mb4\\\'APPLIED\\\')','YES'),
('wms_rule_version','ck_wms_rule_version_digest','regexp_like(`canonical_digest`,_utf8mb4\\\'^[0-9a-f]{64}$\\\',_utf8mb4\\\'c\\\')','YES'),
('wms_rule_version','ck_wms_rule_version_status','(`status` in (_utf8mb4\\\'DRAFT\\\',_utf8mb4\\\'ACTIVE\\\',_utf8mb4\\\'RETIRED\\\'))','YES'),
('wms_rule_version','ck_wms_rule_version_version_no','(`version_no` >= 1)','YES');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_actual_tables;
CREATE TEMPORARY TABLE tmp_iws_rules_actual_tables AS
SELECT TABLE_NAME AS table_name, TABLE_TYPE AS table_type, ENGINE AS engine,
       TABLE_COLLATION AS table_collation, CREATE_OPTIONS AS create_options,
       TABLE_COMMENT AS table_comment
FROM information_schema.TABLES
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_actual_columns;
CREATE TEMPORARY TABLE tmp_iws_rules_actual_columns AS
SELECT TABLE_NAME AS table_name, ORDINAL_POSITION AS ordinal_position,
       COLUMN_NAME AS column_name, COLUMN_TYPE AS column_type,
       IS_NULLABLE AS is_nullable, COLUMN_DEFAULT AS column_default,
       EXTRA AS extra, CHARACTER_SET_NAME AS character_set_name,
       COLLATION_NAME AS collation_name, GENERATION_EXPRESSION AS generation_expression,
       COLUMN_COMMENT AS column_comment
FROM information_schema.COLUMNS
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_actual_indexes;
CREATE TEMPORARY TABLE tmp_iws_rules_actual_indexes AS
SELECT TABLE_NAME AS table_name, INDEX_NAME AS index_name,
       NON_UNIQUE AS non_unique, SEQ_IN_INDEX AS seq_in_index,
       COLUMN_NAME AS column_name, SUB_PART AS sub_part, PACKED AS packed,
       NULLABLE AS nullable, INDEX_TYPE AS index_type,
       COLLATION AS index_collation, EXPRESSION AS index_expression,
       IS_VISIBLE AS is_visible, INDEX_COMMENT AS index_comment
FROM information_schema.STATISTICS
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_actual_checks;
CREATE TEMPORARY TABLE tmp_iws_rules_actual_checks AS
SELECT tc.TABLE_NAME AS table_name, tc.CONSTRAINT_NAME AS constraint_name,
       cc.CHECK_CLAUSE AS check_clause, tc.ENFORCED AS enforced
FROM information_schema.TABLE_CONSTRAINTS tc
JOIN information_schema.CHECK_CONSTRAINTS cc
  ON CAST(cc.CONSTRAINT_SCHEMA AS BINARY) = CAST(tc.CONSTRAINT_SCHEMA AS BINARY)
 AND CAST(cc.CONSTRAINT_NAME AS BINARY) = CAST(tc.CONSTRAINT_NAME AS BINARY)
WHERE CAST(tc.CONSTRAINT_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND tc.CONSTRAINT_TYPE = 'CHECK'
  AND FIND_IN_SET(CAST(tc.TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

SET @object_conflict_count = (
    SELECT COUNT(*)
    FROM information_schema.TABLES
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0
      AND TABLE_TYPE <> 'BASE TABLE'
);
SET @existing_table_count = (
    SELECT COUNT(*)
    FROM tmp_iws_rules_actual_tables
    WHERE table_type = 'BASE TABLE'
);

SET @table_definition_mismatch = (
    SELECT COUNT(*)
    FROM tmp_iws_rules_actual_tables a
    LEFT JOIN tmp_iws_rules_expected_tables e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE a.table_type = 'BASE TABLE'
      AND (e.table_name IS NULL
           OR CAST(a.table_type AS BINARY) <> CAST(e.table_type AS BINARY)
           OR NOT (CAST(a.engine AS BINARY) <=> CAST(e.engine AS BINARY))
           OR NOT (CAST(a.table_collation AS BINARY) <=> CAST(e.table_collation AS BINARY))
           OR CAST(a.create_options AS BINARY) <> CAST(e.create_options AS BINARY)
           OR CAST(a.table_comment AS BINARY) <> CAST(e.table_comment AS BINARY))
);

SET @column_actual_mismatch = (
    SELECT COUNT(*)
    FROM tmp_iws_rules_actual_columns a
    JOIN tmp_iws_rules_actual_tables t
      ON CAST(t.table_name AS BINARY) = CAST(a.table_name AS BINARY)
     AND t.table_type = 'BASE TABLE'
    LEFT JOIN tmp_iws_rules_expected_columns e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE e.table_name IS NULL
       OR a.ordinal_position <> e.ordinal_position
       OR CAST(a.column_type AS BINARY) <> CAST(e.column_type AS BINARY)
       OR CAST(a.is_nullable AS BINARY) <> CAST(e.is_nullable AS BINARY)
       OR NOT (CAST(a.column_default AS BINARY) <=> CAST(e.column_default AS BINARY))
       OR CAST(a.extra AS BINARY) <> CAST(e.extra AS BINARY)
       OR NOT (CAST(a.character_set_name AS BINARY) <=> CAST(e.character_set_name AS BINARY))
       OR NOT (CAST(a.collation_name AS BINARY) <=> CAST(e.collation_name AS BINARY))
       OR CAST(a.generation_expression AS BINARY) <> CAST(e.generation_expression AS BINARY)
       OR CAST(a.column_comment AS BINARY) <> CAST(e.column_comment AS BINARY)
);
SET @column_expected_missing = (
    SELECT COUNT(*)
    FROM tmp_iws_rules_expected_columns e
    JOIN tmp_iws_rules_actual_tables t
      ON CAST(t.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND t.table_type = 'BASE TABLE'
    LEFT JOIN tmp_iws_rules_actual_columns a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE a.column_name IS NULL
);
SET @column_mismatch = @column_actual_mismatch + @column_expected_missing;

SET @index_actual_mismatch = (
    SELECT COUNT(*)
    FROM tmp_iws_rules_actual_indexes a
    JOIN tmp_iws_rules_actual_tables t
      ON CAST(t.table_name AS BINARY) = CAST(a.table_name AS BINARY)
     AND t.table_type = 'BASE TABLE'
    LEFT JOIN tmp_iws_rules_expected_indexes e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE e.index_name IS NULL
       OR a.non_unique <> e.non_unique
       OR NOT (CAST(a.column_name AS BINARY) <=> CAST(e.column_name AS BINARY))
       OR NOT (a.sub_part <=> e.sub_part)
       OR NOT (CAST(a.packed AS BINARY) <=> CAST(e.packed AS BINARY))
       OR NOT (CAST(a.nullable AS BINARY) <=> CAST(e.nullable AS BINARY))
       OR CAST(a.index_type AS BINARY) <> CAST(e.index_type AS BINARY)
       OR NOT (CAST(a.index_collation AS BINARY) <=> CAST(e.index_collation AS BINARY))
       OR NOT (CAST(a.index_expression AS BINARY) <=> CAST(e.index_expression AS BINARY))
       OR CAST(a.is_visible AS BINARY) <> CAST(e.is_visible AS BINARY)
       OR CAST(a.index_comment AS BINARY) <> CAST(e.index_comment AS BINARY)
);
SET @index_expected_missing = (
    SELECT COUNT(*)
    FROM tmp_iws_rules_expected_indexes e
    JOIN tmp_iws_rules_actual_tables t
      ON CAST(t.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND t.table_type = 'BASE TABLE'
    LEFT JOIN tmp_iws_rules_actual_indexes a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE a.index_name IS NULL
);
SET @index_mismatch = @index_actual_mismatch + @index_expected_missing;

SET @check_actual_mismatch = (
    SELECT COUNT(*)
    FROM tmp_iws_rules_actual_checks a
    LEFT JOIN tmp_iws_rules_expected_checks e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE e.constraint_name IS NULL
       OR CAST(a.check_clause AS BINARY) <> CAST(e.check_clause AS BINARY)
       OR CAST(a.enforced AS BINARY) <> CAST(e.enforced AS BINARY)
);
SET @check_expected_missing = (
    SELECT COUNT(*)
    FROM tmp_iws_rules_expected_checks e
    JOIN tmp_iws_rules_actual_tables t
      ON CAST(t.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND t.table_type = 'BASE TABLE'
    LEFT JOIN tmp_iws_rules_actual_checks a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE a.constraint_name IS NULL
);
SET @check_definition_mismatch = @check_actual_mismatch + @check_expected_missing;

SET @own_fk_count = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CAST(CONSTRAINT_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0
      AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @trigger_count = (
    SELECT COUNT(*) FROM information_schema.TRIGGERS
    WHERE CAST(EVENT_OBJECT_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND FIND_IN_SET(CAST(EVENT_OBJECT_TABLE AS BINARY), CAST(@target_table_names AS BINARY)) > 0
);
SET @inbound_fk_count = (
    SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
    WHERE CAST(UNIQUE_CONSTRAINT_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND FIND_IN_SET(CAST(REFERENCED_TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0
);

SET @preflight_ok = IF(
    @metadata_visibility_ok = 1
    AND @schema_ok = 1 AND @schema_exists = 1 AND @version_ok = 1
    AND @object_conflict_count = 0
    AND @existing_table_count <= @expected_table_count
    AND @table_definition_mismatch = 0
    AND @column_mismatch = 0
    AND @index_mismatch = 0
    AND @check_definition_mismatch = 0
    AND @own_fk_count = 0 AND @trigger_count = 0 AND @inbound_fk_count = 0,
    1, 0
);

SELECT
    @rules_release_id AS `发布标识`,
    @rules_contract_version AS `合同版本`,
    @target_schema AS `目标库`,
    VERSION() AS `MySQL版本`,
    @metadata_visibility_ok AS `元数据权限通过`,
    @schema_ok AS `目标库匹配`,
    @existing_table_count AS `现有目标表数`,
    @expected_table_count AS `预期目标表数`,
    @object_conflict_count AS `同名非表对象数`,
    @table_definition_mismatch AS `表定义冲突数`,
    @column_mismatch AS `列定义冲突数`,
    @index_mismatch AS `索引定义冲突数`,
    @check_definition_mismatch AS `CHECK定义冲突数`,
    @own_fk_count AS `本表外键数`,
    @trigger_count AS `触发器数`,
    @inbound_fk_count AS `外部指入外键数`,
    IF(@preflight_ok = 1, '预检通过', '预检失败，禁止任何规则表 DDL') AS `预检结果`;

SELECT JSON_EXTRACT(IF(@preflight_ok = 1, '{}', 'invalid'), '$') AS `规则表预检门禁`;

-- ----------------------------------------------------------------------------
-- 目标 DDL
-- ----------------------------------------------------------------------------
SET @ddl = IF(@preflight_ok = 1, CONCAT(
    'CREATE TABLE IF NOT EXISTS ', @target_schema_q, '.`wms_attribute_catalog` (',
    '`attribute_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''属性键'',',
    '`source_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''来源类型'',',
    '`value_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''值类型'',',
    '`cardinality` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''基数'',',
    '`operators_json` json NOT NULL COMMENT ''允许操作符'',',
    '`unit` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL COMMENT ''单位'',',
    '`scale` int NULL COMMENT ''小数位'',',
    '`groupable` tinyint(1) NOT NULL DEFAULT 0 COMMENT ''是否可分组'',',
    '`status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT ''ACTIVE'' COMMENT ''状态：ACTIVE/DISABLED'',',
    '`contract_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''属性合同版本'',',
    '`revision` int NOT NULL DEFAULT 1 COMMENT ''修订号'',',
    '`create_dept` bigint NULL COMMENT ''创建部门'',',
    '`create_by` bigint NULL COMMENT ''创建者'',',
    '`create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',',
    '`update_by` bigint NULL COMMENT ''更新者'',',
    '`update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',',
    'PRIMARY KEY (`attribute_key`),',
    'KEY `idx_wms_attribute_catalog_status` (`status`, `source_type`),',
    'CONSTRAINT `ck_wms_attribute_catalog_status` CHECK (`status` IN (''ACTIVE'',''DISABLED'')),',
    'CONSTRAINT `ck_wms_attribute_catalog_groupable` CHECK (`groupable` IN (0,1)),',
    'CONSTRAINT `ck_wms_attribute_catalog_scale` CHECK (`scale` IS NULL OR `scale` >= 0),',
    'CONSTRAINT `ck_wms_attribute_catalog_revision` CHECK (`revision` >= 1)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS属性目录'''
), 'DO 0');
PREPARE rules_stmt FROM @ddl;
EXECUTE rules_stmt;
DEALLOCATE PREPARE rules_stmt;

INSERT INTO `wms_attribute_catalog`
    (`attribute_key`, `source_type`, `value_type`, `cardinality`,
     `operators_json`, `unit`, `scale`, `groupable`, `status`,
     `contract_version`, `revision`)
VALUES
    ('storageCode', 'STORAGE', 'STRING', 'SCALAR',
     JSON_ARRAY('EQ', 'NE', 'IN', 'IS_NULL'), NULL, NULL, 1, 'ACTIVE', 'STORAGE-1', 1),
    ('warehouseCode', 'STORAGE', 'STRING', 'SCALAR',
     JSON_ARRAY('EQ', 'NE', 'IN', 'IS_NULL'), NULL, NULL, 1, 'ACTIVE', 'STORAGE-1', 1),
    ('zoneCode', 'STORAGE', 'STRING', 'SCALAR',
     JSON_ARRAY('EQ', 'NE', 'IN', 'IS_NULL'), NULL, NULL, 1, 'ACTIVE', 'STORAGE-1', 1),
    ('storageType', 'STORAGE', 'INTEGER', 'SCALAR',
     JSON_ARRAY('EQ', 'NE', 'IN', 'LT', 'LE', 'GT', 'GE', 'IS_NULL'),
     NULL, NULL, 1, 'ACTIVE', 'STORAGE-1', 1),
    ('allowInbound', 'STORAGE', 'BOOLEAN', 'SCALAR',
     JSON_ARRAY('EQ', 'NE', 'IN', 'IS_NULL'), NULL, NULL, 1, 'ACTIVE', 'STORAGE-1', 1),
    ('allowOutbound', 'STORAGE', 'BOOLEAN', 'SCALAR',
     JSON_ARRAY('EQ', 'NE', 'IN', 'IS_NULL'), NULL, NULL, 1, 'ACTIVE', 'STORAGE-1', 1)
ON DUPLICATE KEY UPDATE
    `source_type` = VALUES(`source_type`),
    `value_type` = VALUES(`value_type`),
    `cardinality` = VALUES(`cardinality`),
    `operators_json` = VALUES(`operators_json`),
    `unit` = VALUES(`unit`),
    `scale` = VALUES(`scale`),
    `groupable` = VALUES(`groupable`),
    `status` = VALUES(`status`),
    `contract_version` = VALUES(`contract_version`),
    `revision` = VALUES(`revision`);

SET @ddl = IF(@preflight_ok = 1, CONCAT(
    'CREATE TABLE IF NOT EXISTS ', @target_schema_q, '.`wms_rule_definition` (',
    '`rule_definition_id` bigint NOT NULL AUTO_INCREMENT COMMENT ''规则定义主键'',',
    '`rule_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''规则键'',',
    '`purpose` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''用途'',',
    '`rule_name` varchar(128) NOT NULL COMMENT ''规则名称'',',
    '`scope_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''作用域类型'',',
    '`status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT ''ACTIVE'' COMMENT ''状态：ACTIVE/DISABLED'',',
    '`current_version_id` bigint NULL COMMENT ''当前版本ID'',',
    '`revision` int NOT NULL DEFAULT 1 COMMENT ''修订号'',',
    '`create_dept` bigint NULL COMMENT ''创建部门'',',
    '`create_by` bigint NULL COMMENT ''创建者'',',
    '`create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',',
    '`update_by` bigint NULL COMMENT ''更新者'',',
    '`update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',',
    'PRIMARY KEY (`rule_definition_id`),',
    'UNIQUE KEY `uk_wms_rule_definition_key` (`rule_key`),',
    'KEY `idx_wms_rule_definition_resolve` (`purpose`, `status`),',
    'KEY `idx_wms_rule_definition_current_version` (`current_version_id`),',
    'CONSTRAINT `ck_wms_rule_definition_status` CHECK (`status` IN (''ACTIVE'',''DISABLED'')),',
    'CONSTRAINT `ck_wms_rule_definition_revision` CHECK (`revision` >= 1)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS规则定义'''
), 'DO 0');
PREPARE rules_stmt FROM @ddl;
EXECUTE rules_stmt;
DEALLOCATE PREPARE rules_stmt;

SET @ddl = IF(@preflight_ok = 1, CONCAT(
    'CREATE TABLE IF NOT EXISTS ', @target_schema_q, '.`wms_rule_version` (',
    '`rule_version_id` bigint NOT NULL AUTO_INCREMENT COMMENT ''规则版本主键'',',
    '`rule_definition_id` bigint NOT NULL COMMENT ''规则定义ID'',',
    '`version_no` int NOT NULL COMMENT ''版本号'',',
    '`condition_json` json NOT NULL COMMENT ''条件表达式'',',
    '`result_json` json NOT NULL COMMENT ''结果表达式'',',
    '`attribute_bindings_json` json DEFAULT NULL COMMENT ''服务端属性绑定、用途和限额快照'',',
    '`canonical_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''规范化内容SHA-256小写十六进制'',',
    '`status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT ''DRAFT'' COMMENT ''状态：DRAFT/ACTIVE/RETIRED'',',
    '`published_at` datetime(3) NULL COMMENT ''发布时间'',',
    '`published_by` bigint NULL COMMENT ''发布人'',',
    '`create_dept` bigint NULL COMMENT ''创建部门'',',
    '`create_by` bigint NULL COMMENT ''创建者'',',
    '`create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',',
    '`update_by` bigint NULL COMMENT ''更新者'',',
    '`update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',',
    'PRIMARY KEY (`rule_version_id`),',
    'UNIQUE KEY `uk_wms_rule_version_owner_version` (`rule_definition_id`, `version_no`),',
    'KEY `idx_wms_rule_version_status` (`status`, `rule_definition_id`),',
    'KEY `idx_wms_rule_version_digest` (`canonical_digest`),',
    'CONSTRAINT `ck_wms_rule_version_version_no` CHECK (`version_no` >= 1),',
    'CONSTRAINT `ck_wms_rule_version_digest` CHECK (REGEXP_LIKE(`canonical_digest`, _utf8mb4''^[0-9a-f]{64}$'', ''c'')),',
    'CONSTRAINT `ck_wms_rule_version_status` CHECK (`status` IN (''DRAFT'',''ACTIVE'',''RETIRED''))',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS规则内容版本'''
), 'DO 0');
PREPARE rules_stmt FROM @ddl;
EXECUTE rules_stmt;
DEALLOCATE PREPARE rules_stmt;

SET @ddl = IF(@preflight_ok = 1, CONCAT(
    'CREATE TABLE IF NOT EXISTS ', @target_schema_q, '.`wms_rule_package_version` (',
    '`package_version_id` bigint NOT NULL AUTO_INCREMENT COMMENT ''规则包版本主键'',',
    '`package_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''规则包编码'',',
    '`version_no` int NOT NULL COMMENT ''版本号'',',
    '`canonical_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''规范化内容SHA-256小写十六进制'',',
    '`status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT ''DRAFT'' COMMENT ''状态：DRAFT/ACTIVE/RETIRED'',',
    '`published_at` datetime(3) NULL COMMENT ''发布时间'',',
    '`published_by` bigint NULL COMMENT ''发布人'',',
    '`create_dept` bigint NULL COMMENT ''创建部门'',',
    '`create_by` bigint NULL COMMENT ''创建者'',',
    '`create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',',
    '`update_by` bigint NULL COMMENT ''更新者'',',
    '`update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',',
    'PRIMARY KEY (`package_version_id`),',
    'UNIQUE KEY `uk_wms_rule_package_version_code_version` (`package_code`, `version_no`),',
    'KEY `idx_wms_rule_package_version_status` (`status`, `package_code`),',
    'KEY `idx_wms_rule_package_version_digest` (`canonical_digest`),',
    'CONSTRAINT `ck_wms_rule_package_version_version_no` CHECK (`version_no` >= 1),',
    'CONSTRAINT `ck_wms_rule_package_version_digest` CHECK (REGEXP_LIKE(`canonical_digest`, _utf8mb4''^[0-9a-f]{64}$'', ''c'')),',
    'CONSTRAINT `ck_wms_rule_package_version_status` CHECK (`status` IN (''DRAFT'',''ACTIVE'',''RETIRED''))',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS规则包版本'''
), 'DO 0');
PREPARE rules_stmt FROM @ddl;
EXECUTE rules_stmt;
DEALLOCATE PREPARE rules_stmt;

SET @ddl = IF(@preflight_ok = 1, CONCAT(
    'CREATE TABLE IF NOT EXISTS ', @target_schema_q, '.`wms_rule_package_member` (',
    '`package_member_id` bigint NOT NULL AUTO_INCREMENT COMMENT ''规则包成员主键'',',
    '`package_version_id` bigint NOT NULL COMMENT ''规则包版本ID'',',
    '`purpose` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''成员用途'',',
    '`rule_version_id` bigint NOT NULL COMMENT ''规则版本ID'',',
    '`revision` int NOT NULL DEFAULT 1 COMMENT ''修订号'',',
    '`create_dept` bigint NULL COMMENT ''创建部门'',',
    '`create_by` bigint NULL COMMENT ''创建者'',',
    '`create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',',
    '`update_by` bigint NULL COMMENT ''更新者'',',
    '`update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',',
    'PRIMARY KEY (`package_member_id`),',
    'UNIQUE KEY `uk_wms_rule_package_member_purpose` (`package_version_id`, `purpose`),',
    'KEY `idx_wms_rule_package_member_rule` (`rule_version_id`),',
    'CONSTRAINT `ck_wms_rule_package_member_revision` CHECK (`revision` >= 1)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS规则包成员'''
), 'DO 0');
PREPARE rules_stmt FROM @ddl;
EXECUTE rules_stmt;
DEALLOCATE PREPARE rules_stmt;

SET @ddl = IF(@preflight_ok = 1, CONCAT(
    'CREATE TABLE IF NOT EXISTS ', @target_schema_q, '.`wms_rule_effective_pointer` (',
    '`rule_effective_pointer_id` bigint NOT NULL AUTO_INCREMENT COMMENT ''生效指针主键'',',
    '`scope_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''作用域类型'',',
    '`scope_id` bigint NOT NULL COMMENT ''作用域内部ID'',',
    '`package_version_id` bigint NOT NULL COMMENT ''规则包版本ID'',',
    '`revision` int NOT NULL DEFAULT 1 COMMENT ''CAS修订号'',',
    '`create_dept` bigint NULL COMMENT ''创建部门'',',
    '`create_by` bigint NULL COMMENT ''创建者'',',
    '`create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',',
    '`update_by` bigint NULL COMMENT ''更新者'',',
    '`update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',',
    'PRIMARY KEY (`rule_effective_pointer_id`),',
    'UNIQUE KEY `uk_wms_rule_effective_pointer_scope` (`scope_type`, `scope_id`),',
    'KEY `idx_wms_rule_effective_pointer_package` (`package_version_id`),',
    'CONSTRAINT `ck_wms_rule_effective_pointer_revision` CHECK (`revision` >= 1)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS规则包生效指针'''
), 'DO 0');
PREPARE rules_stmt FROM @ddl;
EXECUTE rules_stmt;
DEALLOCATE PREPARE rules_stmt;

SET @ddl = IF(@preflight_ok = 1, CONCAT(
    'CREATE TABLE IF NOT EXISTS ', @target_schema_q, '.`wms_rule_seed_ledger` (',
    '`rule_seed_ledger_id` bigint NOT NULL AUTO_INCREMENT COMMENT ''种子台账主键'',',
    '`seed_code` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''种子编码'',',
    '`seed_version` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''种子版本'',',
    '`canonical_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''规范化内容SHA-256小写十六进制'',',
    '`status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''状态：APPLIED'',',
    '`applied_at` datetime(3) NOT NULL COMMENT ''应用时间'',',
    '`create_dept` bigint NULL COMMENT ''创建部门'',',
    '`create_by` bigint NULL COMMENT ''创建者'',',
    '`create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',',
    '`update_by` bigint NULL COMMENT ''更新者'',',
    '`update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',',
    'PRIMARY KEY (`rule_seed_ledger_id`),',
    'UNIQUE KEY `uk_wms_rule_seed_ledger_code_version` (`seed_code`, `seed_version`),',
    'KEY `idx_wms_rule_seed_ledger_status` (`status`, `applied_at`),',
    'CONSTRAINT `ck_wms_rule_seed_ledger_digest` CHECK (REGEXP_LIKE(`canonical_digest`, _utf8mb4''^[0-9a-f]{64}$'', ''c'')),',
    'CONSTRAINT `ck_wms_rule_seed_ledger_status` CHECK (`status` IN (''APPLIED''))',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS规则种子台账'''
), 'DO 0');
PREPARE rules_stmt FROM @ddl;
EXECUTE rules_stmt;
DEALLOCATE PREPARE rules_stmt;

SET @ddl = IF(@preflight_ok = 1, CONCAT(
    'CREATE TABLE IF NOT EXISTS ', @target_schema_q, '.`wms_logical_scope` (',
    '`logical_scope_id` bigint NOT NULL AUTO_INCREMENT COMMENT ''逻辑归属主键'',',
    '`scope_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''逻辑归属编码'',',
    '`scope_name` varchar(128) NOT NULL COMMENT ''逻辑归属名称'',',
    '`lifecycle_status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT ''ACTIVE'' COMMENT ''生命周期：ACTIVE/RETIRING/RETIRED'',',
    '`current_version_id` bigint NULL COMMENT ''当前版本ID'',',
    '`revision` int NOT NULL DEFAULT 1 COMMENT ''修订号'',',
    '`create_dept` bigint NULL COMMENT ''创建部门'',',
    '`create_by` bigint NULL COMMENT ''创建者'',',
    '`create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',',
    '`update_by` bigint NULL COMMENT ''更新者'',',
    '`update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',',
    'PRIMARY KEY (`logical_scope_id`),',
    'UNIQUE KEY `uk_wms_logical_scope_code` (`scope_code`),',
    'KEY `idx_wms_logical_scope_status` (`lifecycle_status`, `scope_code`),',
    'KEY `idx_wms_logical_scope_current_version` (`current_version_id`),',
    'CONSTRAINT `ck_wms_logical_scope_lifecycle_status` CHECK (`lifecycle_status` IN (''ACTIVE'',''RETIRING'',''RETIRED'')),',
    'CONSTRAINT `ck_wms_logical_scope_revision` CHECK (`revision` >= 1)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS逻辑归属'''
), 'DO 0');
PREPARE rules_stmt FROM @ddl;
EXECUTE rules_stmt;
DEALLOCATE PREPARE rules_stmt;

SET @ddl = IF(@preflight_ok = 1, CONCAT(
    'CREATE TABLE IF NOT EXISTS ', @target_schema_q, '.`wms_logical_scope_version` (',
    '`scope_version_id` bigint NOT NULL AUTO_INCREMENT COMMENT ''逻辑归属版本主键'',',
    '`logical_scope_id` bigint NOT NULL COMMENT ''逻辑归属ID'',',
    '`version_no` int NOT NULL COMMENT ''版本号'',',
    '`rule_package_version_id` bigint NOT NULL COMMENT ''规则包版本ID'',',
    '`space_snapshot_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''空间快照SHA-256小写十六进制'',',
    '`status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT ''DRAFT'' COMMENT ''状态：DRAFT/ACTIVE/RETIRED'',',
    '`published_at` datetime(3) NULL COMMENT ''发布时间'',',
    '`published_by` bigint NULL COMMENT ''发布人'',',
    '`create_dept` bigint NULL COMMENT ''创建部门'',',
    '`create_by` bigint NULL COMMENT ''创建者'',',
    '`create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',',
    '`update_by` bigint NULL COMMENT ''更新者'',',
    '`update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',',
    'PRIMARY KEY (`scope_version_id`),',
    'UNIQUE KEY `uk_wms_logical_scope_version_owner_version` (`logical_scope_id`, `version_no`),',
    'KEY `idx_wms_logical_scope_version_package` (`rule_package_version_id`, `status`),',
    'CONSTRAINT `ck_wms_logical_scope_version_version_no` CHECK (`version_no` >= 1),',
    'CONSTRAINT `ck_wms_logical_scope_version_digest` CHECK (REGEXP_LIKE(`space_snapshot_digest`, _utf8mb4''^[0-9a-f]{64}$'', ''c'')),',
    'CONSTRAINT `ck_wms_logical_scope_version_status` CHECK (`status` IN (''DRAFT'',''ACTIVE'',''RETIRED''))',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS逻辑归属版本'''
), 'DO 0');
PREPARE rules_stmt FROM @ddl;
EXECUTE rules_stmt;
DEALLOCATE PREPARE rules_stmt;

SET @ddl = IF(@preflight_ok = 1, CONCAT(
    'CREATE TABLE IF NOT EXISTS ', @target_schema_q, '.`wms_logical_scope_storage` (',
    '`logical_scope_storage_id` bigint NOT NULL AUTO_INCREMENT COMMENT ''逻辑归属储位关系主键'',',
    '`scope_version_id` bigint NOT NULL COMMENT ''逻辑归属版本ID'',',
    '`storage_id` bigint NOT NULL COMMENT ''稳定储位ID'',',
    '`revision` int NOT NULL DEFAULT 1 COMMENT ''修订号'',',
    '`create_dept` bigint NULL COMMENT ''创建部门'',',
    '`create_by` bigint NULL COMMENT ''创建者'',',
    '`create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',',
    '`update_by` bigint NULL COMMENT ''更新者'',',
    '`update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',',
    'PRIMARY KEY (`logical_scope_storage_id`),',
    'UNIQUE KEY `uk_wms_logical_scope_storage` (`scope_version_id`, `storage_id`),',
    'KEY `idx_wms_logical_scope_storage_storage` (`storage_id`, `scope_version_id`),',
    'CONSTRAINT `ck_wms_logical_scope_storage_revision` CHECK (`revision` >= 1)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS逻辑归属储位集合'''
), 'DO 0');
PREPARE rules_stmt FROM @ddl;
EXECUTE rules_stmt;
DEALLOCATE PREPARE rules_stmt;

SET @ddl = IF(@preflight_ok = 1, CONCAT(
    'CREATE TABLE IF NOT EXISTS ', @target_schema_q, '.`wms_material_logical_assignment` (',
    '`logical_assignment_id` bigint NOT NULL AUTO_INCREMENT COMMENT ''物料逻辑归属主键'',',
    '`inventory_id` bigint NOT NULL COMMENT ''稳定库存ID'',',
    '`scope_version_id` bigint NOT NULL COMMENT ''逻辑归属版本ID'',',
    '`assignment_revision` int NOT NULL DEFAULT 1 COMMENT ''归属修订号'',',
    '`source_business_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''来源业务类型'',',
    '`source_business_id` bigint NOT NULL COMMENT ''来源业务内部ID'',',
    '`revision` int NOT NULL DEFAULT 1 COMMENT ''对象修订号'',',
    '`create_dept` bigint NULL COMMENT ''创建部门'',',
    '`create_by` bigint NULL COMMENT ''创建者'',',
    '`create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',',
    '`update_by` bigint NULL COMMENT ''更新者'',',
    '`update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',',
    'PRIMARY KEY (`logical_assignment_id`),',
    'UNIQUE KEY `uk_wms_material_logical_assignment_inventory` (`inventory_id`),',
    'KEY `idx_wms_material_logical_assignment_scope` (`scope_version_id`),',
    'KEY `idx_wms_material_logical_assignment_source` (`source_business_type`, `source_business_id`),',
    'CONSTRAINT `ck_wms_material_logical_assignment_assignment_revision` CHECK (`assignment_revision` >= 1),',
    'CONSTRAINT `ck_wms_material_logical_assignment_revision` CHECK (`revision` >= 1)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS物料当前逻辑归属'''
), 'DO 0');
PREPARE rules_stmt FROM @ddl;
EXECUTE rules_stmt;
DEALLOCATE PREPARE rules_stmt;

SET @ddl = IF(@preflight_ok = 1, CONCAT(
    'CREATE TABLE IF NOT EXISTS ', @target_schema_q, '.`wms_logical_assignment_history` (',
    '`logical_assignment_history_id` bigint NOT NULL AUTO_INCREMENT COMMENT ''逻辑归属历史主键'',',
    '`inventory_id` bigint NOT NULL COMMENT ''稳定库存ID'',',
    '`from_scope_version_id` bigint NULL COMMENT ''变更前逻辑归属版本ID'',',
    '`to_scope_version_id` bigint NOT NULL COMMENT ''变更后逻辑归属版本ID'',',
    '`task_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL COMMENT ''WMS任务键'',',
    '`plan_instance_id` bigint NULL COMMENT ''计划实例ID'',',
    '`effect_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''领域效果键WMS:SHA-256'',',
    '`operator_id` bigint NULL COMMENT ''操作者ID快照'',',
    '`operator_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL COMMENT ''操作者名称快照'',',
    '`operator_department_id` bigint NULL COMMENT ''操作者部门ID快照'',',
    '`operator_department_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL COMMENT ''操作者部门名称快照'',',
    '`occurred_at` datetime(3) NOT NULL COMMENT ''业务发生时间'',',
    '`recorded_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''事实记录时间'',',
    '`create_dept` bigint NULL COMMENT ''创建部门'',',
    '`create_by` bigint NULL COMMENT ''创建者'',',
    '`create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',',
    'PRIMARY KEY (`logical_assignment_history_id`),',
    'UNIQUE KEY `uk_wms_logical_assignment_history_effect` (`effect_key`),',
    'KEY `idx_wms_logical_assignment_history_inventory_time` (`inventory_id`, `occurred_at`),',
    'KEY `idx_wms_logical_assignment_history_instance` (`plan_instance_id`),',
    'KEY `idx_wms_logical_assignment_history_to_scope` (`to_scope_version_id`),',
    'CONSTRAINT `ck_wms_logical_assignment_history_effect_format` CHECK (REGEXP_LIKE(`effect_key`, _utf8mb4''^WMS:[0-9a-f]{64}$'', ''c'')),',
    'CONSTRAINT `ck_wms_logical_assignment_history_effect_length` CHECK (CHAR_LENGTH(`effect_key`) <= 128)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS逻辑归属变更历史'''
), 'DO 0');
PREPARE rules_stmt FROM @ddl;
EXECUTE rules_stmt;
DEALLOCATE PREPARE rules_stmt;

-- ----------------------------------------------------------------------------
-- DDL 后硬验收：重新读取完整元数据，两侧行数和双向差集都必须为零
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_actual_checks;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_actual_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_actual_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_actual_tables;

CREATE TEMPORARY TABLE tmp_iws_rules_actual_tables AS
SELECT TABLE_NAME AS table_name, TABLE_TYPE AS table_type, ENGINE AS engine,
       TABLE_COLLATION AS table_collation, CREATE_OPTIONS AS create_options,
       TABLE_COMMENT AS table_comment
FROM information_schema.TABLES
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

CREATE TEMPORARY TABLE tmp_iws_rules_actual_columns AS
SELECT TABLE_NAME AS table_name, ORDINAL_POSITION AS ordinal_position,
       COLUMN_NAME AS column_name, COLUMN_TYPE AS column_type,
       IS_NULLABLE AS is_nullable, COLUMN_DEFAULT AS column_default,
       EXTRA AS extra, CHARACTER_SET_NAME AS character_set_name,
       COLLATION_NAME AS collation_name, GENERATION_EXPRESSION AS generation_expression,
       COLUMN_COMMENT AS column_comment
FROM information_schema.COLUMNS
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

CREATE TEMPORARY TABLE tmp_iws_rules_actual_indexes AS
SELECT TABLE_NAME AS table_name, INDEX_NAME AS index_name,
       NON_UNIQUE AS non_unique, SEQ_IN_INDEX AS seq_in_index,
       COLUMN_NAME AS column_name, SUB_PART AS sub_part, PACKED AS packed,
       NULLABLE AS nullable, INDEX_TYPE AS index_type,
       COLLATION AS index_collation, EXPRESSION AS index_expression,
       IS_VISIBLE AS is_visible, INDEX_COMMENT AS index_comment
FROM information_schema.STATISTICS
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

CREATE TEMPORARY TABLE tmp_iws_rules_actual_checks AS
SELECT tc.TABLE_NAME AS table_name, tc.CONSTRAINT_NAME AS constraint_name,
       cc.CHECK_CLAUSE AS check_clause, tc.ENFORCED AS enforced
FROM information_schema.TABLE_CONSTRAINTS tc
JOIN information_schema.CHECK_CONSTRAINTS cc
  ON CAST(cc.CONSTRAINT_SCHEMA AS BINARY) = CAST(tc.CONSTRAINT_SCHEMA AS BINARY)
 AND CAST(cc.CONSTRAINT_NAME AS BINARY) = CAST(tc.CONSTRAINT_NAME AS BINARY)
WHERE CAST(tc.CONSTRAINT_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND tc.CONSTRAINT_TYPE = 'CHECK'
  AND FIND_IN_SET(CAST(tc.TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

SET @post_table_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_rules_actual_tables a
    LEFT JOIN tmp_iws_rules_expected_tables e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE e.table_name IS NULL
       OR NOT (CAST(a.table_type AS BINARY) <=> CAST(e.table_type AS BINARY))
       OR NOT (CAST(a.engine AS BINARY) <=> CAST(e.engine AS BINARY))
       OR NOT (CAST(a.table_collation AS BINARY) <=> CAST(e.table_collation AS BINARY))
       OR NOT (CAST(a.create_options AS BINARY) <=> CAST(e.create_options AS BINARY))
       OR NOT (CAST(a.table_comment AS BINARY) <=> CAST(e.table_comment AS BINARY))
);
SET @post_table_expected_missing = (
    SELECT COUNT(*) FROM tmp_iws_rules_expected_tables e
    LEFT JOIN tmp_iws_rules_actual_tables a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE a.table_name IS NULL
);
SET @post_column_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_rules_actual_columns a
    LEFT JOIN tmp_iws_rules_expected_columns e
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
    SELECT COUNT(*) FROM tmp_iws_rules_expected_columns e
    LEFT JOIN tmp_iws_rules_actual_columns a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE a.column_name IS NULL
);
SET @post_index_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_rules_actual_indexes a
    LEFT JOIN tmp_iws_rules_expected_indexes e
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
    SELECT COUNT(*) FROM tmp_iws_rules_expected_indexes e
    LEFT JOIN tmp_iws_rules_actual_indexes a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE a.index_name IS NULL
);
SET @post_check_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_rules_actual_checks a
    LEFT JOIN tmp_iws_rules_expected_checks e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE e.constraint_name IS NULL
       OR NOT (CAST(a.check_clause AS BINARY) <=> CAST(e.check_clause AS BINARY))
       OR NOT (CAST(a.enforced AS BINARY) <=> CAST(e.enforced AS BINARY))
);
SET @post_check_expected_missing = (
    SELECT COUNT(*) FROM tmp_iws_rules_expected_checks e
    LEFT JOIN tmp_iws_rules_actual_checks a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE a.constraint_name IS NULL
);
SET @post_base_table_count = (
    SELECT COUNT(*) FROM tmp_iws_rules_actual_tables WHERE table_type = 'BASE TABLE'
);
SET @post_object_conflict_count = (
    SELECT COUNT(*) FROM tmp_iws_rules_actual_tables WHERE table_type <> 'BASE TABLE'
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
    SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
    WHERE CAST(UNIQUE_CONSTRAINT_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND FIND_IN_SET(CAST(REFERENCED_TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0
);
SET @post_expected_table_rows = (SELECT COUNT(*) FROM tmp_iws_rules_expected_tables);
SET @post_actual_table_rows = (SELECT COUNT(*) FROM tmp_iws_rules_actual_tables);
SET @post_expected_column_rows = (SELECT COUNT(*) FROM tmp_iws_rules_expected_columns);
SET @post_actual_column_rows = (SELECT COUNT(*) FROM tmp_iws_rules_actual_columns);
SET @post_expected_index_rows = (SELECT COUNT(*) FROM tmp_iws_rules_expected_indexes);
SET @post_actual_index_rows = (SELECT COUNT(*) FROM tmp_iws_rules_actual_indexes);
SET @post_expected_check_rows = (SELECT COUNT(*) FROM tmp_iws_rules_expected_checks);
SET @post_actual_check_rows = (SELECT COUNT(*) FROM tmp_iws_rules_actual_checks);

SET @post_structure_ok = IF(
    @metadata_visibility_ok = 1 AND @schema_ok = 1 AND @version_ok = 1
    AND @post_base_table_count = @expected_table_count
    AND @post_actual_table_rows = @post_expected_table_rows
    AND @post_table_actual_extra = 0 AND @post_table_expected_missing = 0
    AND @post_actual_column_rows = @post_expected_column_rows
    AND @post_column_actual_extra = 0 AND @post_column_expected_missing = 0
    AND @post_actual_index_rows = @post_expected_index_rows
    AND @post_index_actual_extra = 0 AND @post_index_expected_missing = 0
    AND @post_actual_check_rows = @post_expected_check_rows
    AND @post_check_actual_extra = 0 AND @post_check_expected_missing = 0
    AND @post_object_conflict_count = 0
    AND @post_own_fk_count = 0 AND @post_trigger_count = 0
    AND @post_inbound_fk_count = 0,
    1, 0
);

SELECT
    @rules_release_id AS `发布标识`,
    @existing_table_count AS `执行前目标表数`,
    @post_base_table_count AS `执行后目标表数`,
    CONCAT(@post_actual_table_rows, '/', @post_expected_table_rows) AS `表元数据行`,
    CONCAT(@post_actual_column_rows, '/', @post_expected_column_rows) AS `列元数据行`,
    CONCAT(@post_actual_index_rows, '/', @post_expected_index_rows) AS `索引元数据行`,
    CONCAT(@post_actual_check_rows, '/', @post_expected_check_rows) AS `CHECK元数据行`,
    @post_table_actual_extra + @post_table_expected_missing AS `表元数据差异`,
    @post_column_actual_extra + @post_column_expected_missing AS `列元数据差异`,
    @post_index_actual_extra + @post_index_expected_missing AS `索引元数据差异`,
    @post_check_actual_extra + @post_check_expected_missing AS `CHECK元数据差异`,
    @post_own_fk_count AS `本表外键数`,
    @post_trigger_count AS `触发器数`,
    @post_inbound_fk_count AS `外部指入外键数`,
    IF(@post_structure_ok = 1,
       '规则表 DDL 与结构验收通过；不表示回填、发布或旧库迁移完成',
       '规则表 DDL 后结构验收失败') AS `执行结果`;

SELECT JSON_EXTRACT(IF(@post_structure_ok = 1, '{}', 'invalid'), '$')
    AS `规则表DDL完成门禁`;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_actual_checks;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_actual_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_actual_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_actual_tables;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_expected_checks;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_expected_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_expected_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_expected_tables;
