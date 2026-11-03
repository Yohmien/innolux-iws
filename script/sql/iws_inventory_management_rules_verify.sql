-- ============================================================================
-- IWS+ 库存规则、逻辑归属与物料 DSL 首切片只读验收（INV-P3-01）
-- ============================================================================
-- 目标数据库：MySQL 8.0.45
-- 目标库参数：@target_schema，必须与连接 DATABASE() 字节一致
-- 验收范围：
--   1. 十二张目标表均为 BASE TABLE，且表选项、完整列行集合、完整索引行集合、
--      完整 CHECK 行集合与冻结预期精确一致；
--   2. 两侧均核对行数及双向差集，不使用 LOWER 或其他默认大小写比较放宽，
--      不以 GROUP_CONCAT 拼接签名，避免长度截断和分隔符碰撞；
--   3. 不声明本表外键，不允许目标表触发器和外部指入外键；
--   4. 展示稳定键、摘要、指针、版本与逻辑归属核心约束。
-- 边界：
--   本脚本只证明 DDL/唯一键可判定的事实，不创建或修改业务数据，不证明
--   ACTIVE 后不可原位修改、同 seed code/version 异 digest 的业务拒绝与不覆盖、
--   发布 CAS、应用权限或 S2/S3 回填。上述由后续服务事务、执行器和真实升级验收负责。
-- 执行：
--   mysql --no-defaults --protocol=TCP --host=<主机> --port=<端口> \
--     --user=<账号> --default-character-set=utf8mb4 --database=innolux-iws \
--     --batch --raw < script/sql/iws_inventory_management_rules_verify.sql
--   只读验收；不加 --force，任一门禁失败以非零退出终止。
-- ============================================================================

SET NAMES utf8mb4;

SET @target_schema = 'innolux-iws';
SET @rules_release_id = 'INV-P3-01-20261028';
SET @rules_contract_version = 'RULES-12T-P3-01-20261028';
SET @expected_mysql_version = '8.0.45';
SET @expected_table_count = 12;
SET @target_table_names = 'wms_attribute_catalog,wms_rule_definition,wms_rule_version,wms_rule_package_version,wms_rule_package_member,wms_rule_effective_pointer,wms_rule_seed_ledger,wms_logical_scope,wms_logical_scope_version,wms_logical_scope_storage,wms_material_logical_assignment,wms_logical_assignment_history';

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
SET @version_ok = (
    CAST(VERSION() AS BINARY) = CAST(@expected_mysql_version AS BINARY)
    AND VERSION() NOT LIKE '%MariaDB%'
);

DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_verify_expected_tables;
CREATE TEMPORARY TABLE tmp_iws_rules_verify_expected_tables (
    table_name       varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    table_type       varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    engine           varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    table_collation  varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    create_options   varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    table_comment    varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_verify_expected_columns;
CREATE TEMPORARY TABLE tmp_iws_rules_verify_expected_columns (
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
    UNIQUE KEY uk_tmp_iws_rules_verify_expected_columns (table_name, column_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_verify_expected_indexes;
CREATE TEMPORARY TABLE tmp_iws_rules_verify_expected_indexes (
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

DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_verify_expected_checks;
CREATE TEMPORARY TABLE tmp_iws_rules_verify_expected_checks (
    table_name      varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    constraint_name varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    check_clause    longtext     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    enforced        varchar(3)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name, constraint_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_iws_rules_verify_expected_tables
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

INSERT INTO tmp_iws_rules_verify_expected_columns
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
('wms_rule_version',6,'canonical_digest','char(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','规范化内容SHA-256小写十六进制'),
('wms_rule_version',7,'status','varchar(16)','NO','DRAFT','','utf8mb4','utf8mb4_0900_bin','','状态：DRAFT/ACTIVE/RETIRED'),
('wms_rule_version',8,'published_at','datetime(3)','YES',NULL,'',NULL,NULL,'','发布时间'),
('wms_rule_version',9,'published_by','bigint','YES',NULL,'',NULL,NULL,'','发布人'),
('wms_rule_version',10,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_rule_version',11,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_rule_version',12,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_rule_version',13,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_rule_version',14,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间');

INSERT INTO tmp_iws_rules_verify_expected_indexes
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

INSERT INTO tmp_iws_rules_verify_expected_checks
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

DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_verify_actual_tables;
CREATE TEMPORARY TABLE tmp_iws_rules_verify_actual_tables AS
SELECT TABLE_NAME AS table_name, TABLE_TYPE AS table_type, ENGINE AS engine,
       TABLE_COLLATION AS table_collation, CREATE_OPTIONS AS create_options,
       TABLE_COMMENT AS table_comment
FROM information_schema.TABLES
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_verify_actual_columns;
CREATE TEMPORARY TABLE tmp_iws_rules_verify_actual_columns AS
SELECT TABLE_NAME AS table_name, ORDINAL_POSITION AS ordinal_position,
       COLUMN_NAME AS column_name, COLUMN_TYPE AS column_type,
       IS_NULLABLE AS is_nullable, COLUMN_DEFAULT AS column_default,
       EXTRA AS extra, CHARACTER_SET_NAME AS character_set_name,
       COLLATION_NAME AS collation_name, GENERATION_EXPRESSION AS generation_expression,
       COLUMN_COMMENT AS column_comment
FROM information_schema.COLUMNS
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_verify_actual_indexes;
CREATE TEMPORARY TABLE tmp_iws_rules_verify_actual_indexes AS
SELECT TABLE_NAME AS table_name, INDEX_NAME AS index_name,
       NON_UNIQUE AS non_unique, SEQ_IN_INDEX AS seq_in_index,
       COLUMN_NAME AS column_name, SUB_PART AS sub_part, PACKED AS packed,
       NULLABLE AS nullable, INDEX_TYPE AS index_type,
       COLLATION AS index_collation, EXPRESSION AS index_expression,
       IS_VISIBLE AS is_visible, INDEX_COMMENT AS index_comment
FROM information_schema.STATISTICS
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_verify_actual_checks;
CREATE TEMPORARY TABLE tmp_iws_rules_verify_actual_checks AS
SELECT tc.TABLE_NAME AS table_name, tc.CONSTRAINT_NAME AS constraint_name,
       cc.CHECK_CLAUSE AS check_clause, tc.ENFORCED AS enforced
FROM information_schema.TABLE_CONSTRAINTS tc
JOIN information_schema.CHECK_CONSTRAINTS cc
  ON CAST(cc.CONSTRAINT_SCHEMA AS BINARY) = CAST(tc.CONSTRAINT_SCHEMA AS BINARY)
 AND CAST(cc.CONSTRAINT_NAME AS BINARY) = CAST(tc.CONSTRAINT_NAME AS BINARY)
WHERE CAST(tc.CONSTRAINT_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND tc.CONSTRAINT_TYPE = 'CHECK'
  AND FIND_IN_SET(CAST(tc.TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

SET @actual_base_table_count = (
    SELECT COUNT(*) FROM tmp_iws_rules_verify_actual_tables
    WHERE table_type = 'BASE TABLE'
);
SET @object_conflict_count = (
    SELECT COUNT(*) FROM tmp_iws_rules_verify_actual_tables
    WHERE table_type <> 'BASE TABLE'
);
SET @table_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_rules_verify_actual_tables a
    LEFT JOIN tmp_iws_rules_verify_expected_tables e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE e.table_name IS NULL
       OR NOT (CAST(a.table_type AS BINARY) <=> CAST(e.table_type AS BINARY))
       OR NOT (CAST(a.engine AS BINARY) <=> CAST(e.engine AS BINARY))
       OR NOT (CAST(a.table_collation AS BINARY) <=> CAST(e.table_collation AS BINARY))
       OR NOT (CAST(a.create_options AS BINARY) <=> CAST(e.create_options AS BINARY))
       OR NOT (CAST(a.table_comment AS BINARY) <=> CAST(e.table_comment AS BINARY))
);
SET @table_expected_missing = (
    SELECT COUNT(*) FROM tmp_iws_rules_verify_expected_tables e
    LEFT JOIN tmp_iws_rules_verify_actual_tables a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE a.table_name IS NULL
);

SET @column_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_rules_verify_actual_columns a
    LEFT JOIN tmp_iws_rules_verify_expected_columns e
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
SET @column_expected_missing = (
    SELECT COUNT(*) FROM tmp_iws_rules_verify_expected_columns e
    LEFT JOIN tmp_iws_rules_verify_actual_columns a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE a.column_name IS NULL
);

SET @index_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_rules_verify_actual_indexes a
    LEFT JOIN tmp_iws_rules_verify_expected_indexes e
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
SET @index_expected_missing = (
    SELECT COUNT(*) FROM tmp_iws_rules_verify_expected_indexes e
    LEFT JOIN tmp_iws_rules_verify_actual_indexes a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE a.index_name IS NULL
);

SET @check_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_rules_verify_actual_checks a
    LEFT JOIN tmp_iws_rules_verify_expected_checks e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE e.constraint_name IS NULL
       OR NOT (CAST(a.check_clause AS BINARY) <=> CAST(e.check_clause AS BINARY))
       OR NOT (CAST(a.enforced AS BINARY) <=> CAST(e.enforced AS BINARY))
);
SET @check_expected_missing = (
    SELECT COUNT(*) FROM tmp_iws_rules_verify_expected_checks e
    LEFT JOIN tmp_iws_rules_verify_actual_checks a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE a.constraint_name IS NULL
);

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

SET @expected_table_rows = (SELECT COUNT(*) FROM tmp_iws_rules_verify_expected_tables);
SET @actual_table_rows = (SELECT COUNT(*) FROM tmp_iws_rules_verify_actual_tables);
SET @expected_column_rows = (SELECT COUNT(*) FROM tmp_iws_rules_verify_expected_columns);
SET @actual_column_rows = (SELECT COUNT(*) FROM tmp_iws_rules_verify_actual_columns);
SET @expected_index_rows = (SELECT COUNT(*) FROM tmp_iws_rules_verify_expected_indexes);
SET @actual_index_rows = (SELECT COUNT(*) FROM tmp_iws_rules_verify_actual_indexes);
SET @expected_check_rows = (SELECT COUNT(*) FROM tmp_iws_rules_verify_expected_checks);
SET @actual_check_rows = (SELECT COUNT(*) FROM tmp_iws_rules_verify_actual_checks);

SET @verification_ok = IF(
    @metadata_visibility_ok = 1 AND @schema_ok = 1 AND @version_ok = 1
    AND @actual_base_table_count = @expected_table_count
    AND @expected_table_rows = @expected_table_count
    AND @actual_table_rows = @expected_table_rows
    AND @table_actual_extra = 0 AND @table_expected_missing = 0
    AND @actual_column_rows = @expected_column_rows
    AND @column_actual_extra = 0 AND @column_expected_missing = 0
    AND @actual_index_rows = @expected_index_rows
    AND @index_actual_extra = 0 AND @index_expected_missing = 0
    AND @actual_check_rows = @expected_check_rows
    AND @check_actual_extra = 0 AND @check_expected_missing = 0
    AND @object_conflict_count = 0
    AND @own_fk_count = 0 AND @trigger_count = 0 AND @inbound_fk_count = 0,
    1, 0
);

SELECT
    @rules_release_id AS `发布标识`,
    @rules_contract_version AS `合同版本`,
    @target_schema AS `目标库`,
    VERSION() AS `MySQL版本`,
    @metadata_visibility_ok AS `元数据权限通过`,
    @actual_base_table_count AS `目标表数`,
    CONCAT(@actual_table_rows, '/', @expected_table_rows) AS `表元数据行`,
    CONCAT(@actual_column_rows, '/', @expected_column_rows) AS `列元数据行`,
    CONCAT(@actual_index_rows, '/', @expected_index_rows) AS `索引元数据行`,
    CONCAT(@actual_check_rows, '/', @expected_check_rows) AS `CHECK元数据行`,
    @table_actual_extra + @table_expected_missing AS `表元数据差异`,
    @column_actual_extra + @column_expected_missing AS `列元数据差异`,
    @index_actual_extra + @index_expected_missing AS `索引元数据差异`,
    @check_actual_extra + @check_expected_missing AS `CHECK元数据差异`,
    @object_conflict_count AS `同名非表对象数`,
    @own_fk_count AS `本表外键数`,
    @trigger_count AS `触发器数`,
    @inbound_fk_count AS `外部指入外键数`,
    IF(@verification_ok = 1, '规则表结构验收通过', '规则表结构验收失败') AS `验收结果`;

SELECT
    'wms_attribute_catalog' AS `表`, 'attribute_key PK' AS `稳定键`,
    'operators_json/status/contract_version/revision' AS `核心合同`
UNION ALL SELECT
    'wms_rule_definition', 'rule_key UNIQUE',
    'rule_definition_id PK/current_version_id NULL 表示尚无已发布版本'
UNION ALL SELECT
    'wms_rule_version', '(rule_definition_id,version_no) UNIQUE',
    'canonical_digest 64位小写SHA-256/status DRAFT,ACTIVE,RETIRED'
UNION ALL SELECT
    'wms_rule_package_version', '(package_code,version_no) UNIQUE',
    'canonical_digest 64位小写SHA-256/status DRAFT,ACTIVE,RETIRED'
UNION ALL SELECT
    'wms_rule_package_member', '(package_version_id,purpose) UNIQUE',
    '成员只引用 rule_version_id'
UNION ALL SELECT
    'wms_rule_effective_pointer', '(scope_type,scope_id) UNIQUE',
    'scope_id NOT NULL/revision CAS；全局作用域表达待后续合同决定'
UNION ALL SELECT
    'wms_rule_seed_ledger', '(seed_code,seed_version) UNIQUE',
    'canonical_digest 不进入唯一键；同键异摘要业务拒绝由后续事务实现'
UNION ALL SELECT
    'wms_logical_scope', 'scope_code UNIQUE',
    'current_version_id NULL 表示尚无已发布版本/lifecycle ACTIVE,RETIRING,RETIRED'
UNION ALL SELECT
    'wms_logical_scope_version', '(logical_scope_id,version_no) UNIQUE',
    'space_snapshot_digest 64位小写SHA-256/status DRAFT,ACTIVE,RETIRED'
UNION ALL SELECT
    'wms_logical_scope_storage', '(scope_version_id,storage_id) UNIQUE',
    '逻辑版本与稳定 storage_id 组合'
UNION ALL SELECT
    'wms_material_logical_assignment', 'inventory_id UNIQUE',
    'inventory_id 稳定引用/assignment_revision 与 revision'
UNION ALL SELECT
    'wms_logical_assignment_history', 'effect_key UNIQUE',
    'effect_key=WMS:+64位小写SHA-256，共68字符且不超过128/退役历史不删除';

SELECT JSON_EXTRACT(IF(@verification_ok = 1, '{}', 'invalid'), '$') AS `规则表结构验收门禁`;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_verify_actual_checks;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_verify_actual_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_verify_actual_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_verify_actual_tables;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_verify_expected_checks;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_verify_expected_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_verify_expected_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_rules_verify_expected_tables;
