-- ============================================================================
-- IWS+ 库存空间 foundation 只读验收
-- ============================================================================
-- 目标数据库：MySQL 8.0.45
-- 目标库参数：@target_schema，必须与连接 DATABASE() 字节一致
-- 验收范围：十张目标表的表选项、完整列元数据、完整索引行、完整 CHECK 行；
--   核心唯一键、CHECK enforced、生成列表达式、默认 NULL、目标表严格尾列、
--   本表/入向 FK、trigger 和权限可见性。行数加双向差集比较，不使用摘要或
--   不区分大小写的名称比较。
-- 边界：只读；不证明通道同通道/无环应用校验、visibility 父链、location JSON
--   内容、服务 CAS、回填、切换、seed、真实库部署或完整 P2。
-- 执行：
--   mysql --no-defaults --protocol=TCP --host=<主机> --port=<端口> \
--     --user=<账号> --default-character-set=utf8mb4 --database=innolux-iws \
--     --batch --raw < script/sql/iws_inventory_management_space_foundation_verify.sql
--   不加 --force，任一门禁失败以非零退出终止。
-- ============================================================================

SET NAMES utf8mb4;

SET @target_schema = 'innolux-iws';
SET @foundation_release_id = 'INV-P2-01-SPACE-FOUNDATION-20261029';
SET @foundation_contract_version = 'SPACE-10T-P2-01-20261029';
SET @expected_mysql_version = '8.0.45';
SET @expected_table_count = 10;
SET @target_table_names = 'wms_warehouse_type,wms_warehouse_type_config,wms_access_channel,wms_storage_access_member,wms_storage_access_edge,wms_location_version,wms_location_effective,wms_object_visibility,wms_object_visibility_department,wms_storage_capacity';
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

DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_verify_expected_tables;
CREATE TEMPORARY TABLE tmp_iws_space_verify_expected_tables (
    table_name       varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    table_type       varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    engine           varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    table_collation  varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    create_options   varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    table_comment    varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_verify_expected_columns;
CREATE TEMPORARY TABLE tmp_iws_space_verify_expected_columns (
    table_name            varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ordinal_position      int          NOT NULL,
    column_name           varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    column_type           varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    is_nullable           varchar(3)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    column_default        longtext     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    extra                 varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    character_set_name    varchar(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    collation_name        varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    generation_expression longtext     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    column_comment        varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name, ordinal_position),
    UNIQUE KEY uk_tmp_iws_space_verify_expected_columns (table_name, column_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_verify_expected_indexes;
CREATE TEMPORARY TABLE tmp_iws_space_verify_expected_indexes (
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
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_verify_expected_checks;
CREATE TEMPORARY TABLE tmp_iws_space_verify_expected_checks (
    table_name      varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    constraint_name varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    check_clause    longtext     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    enforced        varchar(3)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name, constraint_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_iws_space_verify_expected_tables
    (table_name, table_type, engine, table_collation, create_options, table_comment)
VALUES
('wms_access_channel','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS设备访问通道'),
('wms_location_effective','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS空间位置当前生效版本'),
('wms_location_version','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS空间位置配置版本'),
('wms_object_visibility','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS对象可见范围三态头'),
('wms_object_visibility_department','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS对象可见部门集合'),
('wms_storage_access_edge','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS通道储位阻挡边'),
('wms_storage_access_member','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS通道储位成员'),
('wms_storage_capacity','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS储位容量'),
('wms_warehouse_type','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS仓库类型'),
('wms_warehouse_type_config','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS仓库类型配置版本');

INSERT INTO tmp_iws_space_verify_expected_columns
    (table_name, ordinal_position, column_name, column_type, is_nullable, column_default,
     extra, character_set_name, collation_name, generation_expression, column_comment)
VALUES
('wms_access_channel',1,'channel_id','bigint','NO',NULL,'',NULL,NULL,'','访问通道主键'),
('wms_access_channel',2,'device_zone_id','bigint','NO',NULL,'',NULL,NULL,'','设备库区ID，逻辑关联 wms_warehouse_zone.zone_id'),
('wms_access_channel',3,'channel_code','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','通道稳定编码'),
('wms_access_channel',4,'access_mode','varchar(32)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','访问模式，未闭合稳定代码由目录约束'),
('wms_access_channel',5,'status','varchar(16)','NO','ACTIVE','','utf8mb4','utf8mb4_0900_bin','','状态：ACTIVE/RETIRING/RETIRED'),
('wms_access_channel',6,'revision','int','NO','1','',NULL,NULL,'','访问通道修订号'),
('wms_access_channel',7,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_access_channel',8,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_access_channel',9,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_access_channel',10,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_access_channel',11,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_location_effective',1,'location_effective_id','bigint','NO',NULL,'',NULL,NULL,'','位置生效指针主键'),
('wms_location_effective',2,'object_type','varchar(32)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','空间对象类型'),
('wms_location_effective',3,'object_id','bigint','NO',NULL,'',NULL,NULL,'','空间对象内部ID'),
('wms_location_effective',4,'location_version_id','bigint','NO',NULL,'',NULL,NULL,'','当前位置版本ID'),
('wms_location_effective',5,'revision','int','NO','1','',NULL,NULL,'','生效指针CAS修订号'),
('wms_location_effective',6,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_location_effective',7,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_location_effective',8,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_location_effective',9,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_location_effective',10,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_location_version',1,'location_version_id','bigint','NO',NULL,'',NULL,NULL,'','位置版本主键'),
('wms_location_version',2,'object_type','varchar(32)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','空间对象类型'),
('wms_location_version',3,'object_id','bigint','NO',NULL,'',NULL,NULL,'','空间对象内部ID'),
('wms_location_version',4,'version_no','int','NO',NULL,'',NULL,NULL,'','对象位置版本号'),
('wms_location_version',5,'parent_location_version_id','bigint','YES',NULL,'',NULL,NULL,'','父对象位置版本ID'),
('wms_location_version',6,'config_json','json','NO',NULL,'',NULL,NULL,'','位置不可变配置'),
('wms_location_version',7,'canonical_digest','char(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','规范化配置SHA-256小写十六进制'),
('wms_location_version',8,'status','varchar(16)','NO','DRAFT','','utf8mb4','utf8mb4_0900_bin','','状态：DRAFT/ACTIVE/RETIRED'),
('wms_location_version',9,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_location_version',10,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_location_version',11,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_location_version',12,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_location_version',13,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_object_visibility',1,'visibility_key','bigint','NO',NULL,'',NULL,NULL,'','对象可见范围主键'),
('wms_object_visibility',2,'parent_visibility_key','bigint','YES',NULL,'',NULL,NULL,'','当前物理父对象可见范围主键'),
('wms_object_visibility',3,'object_type','varchar(32)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','空间对象类型'),
('wms_object_visibility',4,'object_id','bigint','NO',NULL,'',NULL,NULL,'','空间对象内部ID'),
('wms_object_visibility',5,'mode','varchar(16)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','可见范围模式：INHERIT/VALUE/CLEAR'),
('wms_object_visibility',6,'revision','int','NO','1','',NULL,NULL,'','可见范围修订号'),
('wms_object_visibility',7,'updated_at','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','可见范围更新时间'),
('wms_object_visibility',8,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_object_visibility',9,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_object_visibility',10,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_object_visibility',11,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_object_visibility',12,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_object_visibility_department',1,'visibility_department_id','bigint','NO',NULL,'',NULL,NULL,'','可见部门关系主键'),
('wms_object_visibility_department',2,'visibility_key','bigint','NO',NULL,'',NULL,NULL,'','对象可见范围主键'),
('wms_object_visibility_department',3,'dept_id','bigint','NO',NULL,'',NULL,NULL,'','部门ID，逻辑关联 sys_dept.dept_id'),
('wms_object_visibility_department',4,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_object_visibility_department',5,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_object_visibility_department',6,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_object_visibility_department',7,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_object_visibility_department',8,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_storage_access_edge',1,'access_edge_id','bigint','NO',NULL,'',NULL,NULL,'','阻挡边主键'),
('wms_storage_access_edge',2,'blocker_storage_id','bigint','NO',NULL,'',NULL,NULL,'','阻挡储位ID'),
('wms_storage_access_edge',3,'blocked_storage_id','bigint','NO',NULL,'',NULL,NULL,'','被阻挡储位ID'),
('wms_storage_access_edge',4,'revision','int','NO','1','',NULL,NULL,'','阻挡边修订号'),
('wms_storage_access_edge',5,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_storage_access_edge',6,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_storage_access_edge',7,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_storage_access_edge',8,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_storage_access_edge',9,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_storage_access_member',1,'member_id','bigint','NO',NULL,'',NULL,NULL,'','通道成员主键'),
('wms_storage_access_member',2,'channel_id','bigint','NO',NULL,'',NULL,NULL,'','访问通道ID'),
('wms_storage_access_member',3,'storage_id','bigint','NO',NULL,'',NULL,NULL,'','稳定储位ID'),
('wms_storage_access_member',4,'access_order','int','NO',NULL,'',NULL,NULL,'','显式访问顺序'),
('wms_storage_access_member',5,'revision','int','NO','1','',NULL,NULL,'','通道成员修订号'),
('wms_storage_access_member',6,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_storage_access_member',7,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_storage_access_member',8,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_storage_access_member',9,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_storage_access_member',10,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_storage_capacity',1,'capacity_id','bigint','NO',NULL,'',NULL,NULL,'','储位容量主键，由服务分配'),
('wms_storage_capacity',2,'storage_id','bigint','NO',NULL,'',NULL,NULL,'','稳定储位ID，逻辑关联 wms_storage.storage_id'),
('wms_storage_capacity',3,'dimension_key','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','容量维度键'),
('wms_storage_capacity',4,'capacity_value','decimal(38,12)','NO',NULL,'',NULL,NULL,'','容量值'),
('wms_storage_capacity',5,'unit','varchar(32)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','容量单位'),
('wms_storage_capacity',6,'scale','int','NO',NULL,'',NULL,NULL,'','容量小数位'),
('wms_storage_capacity',7,'capacity_version','int','NO',NULL,'',NULL,NULL,'','容量版本号'),
('wms_storage_capacity',8,'active_flag','tinyint(1)','NO','1','',NULL,NULL,'','活动版本标志：1活动，0历史'),
('wms_storage_capacity',9,'active_capacity_key','varchar(531)','NO',NULL,'STORED GENERATED','utf8mb4','utf8mb4_0900_bin','if((`active_flag` = 1),concat(_utf8mb4\\''A:\\'',lpad(hex(`storage_id`),16,_utf8mb4\\''0\\''),_utf8mb4\\'':\\'',hex(cast(`dimension_key` as char charset binary))),concat(_utf8mb4\\''H:\\'',lpad(hex(`capacity_id`),16,_utf8mb4\\''0\\'')))','活动或历史容量无碰撞唯一键'),
('wms_storage_capacity',10,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_storage_capacity',11,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_storage_capacity',12,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_storage_capacity',13,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_storage_capacity',14,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_warehouse_type',1,'warehouse_type_code','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','仓库类型稳定编码'),
('wms_warehouse_type',2,'warehouse_type_name','varchar(128)','NO',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','仓库类型名称'),
('wms_warehouse_type',3,'status','varchar(16)','NO','ACTIVE','','utf8mb4','utf8mb4_0900_bin','','状态：ACTIVE/DISABLED'),
('wms_warehouse_type',4,'contract_version','varchar(32)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','类型合同版本'),
('wms_warehouse_type',5,'config_schema_json','json','NO',NULL,'',NULL,NULL,'','类型配置JSON Schema'),
('wms_warehouse_type',6,'revision','int','NO','1','',NULL,NULL,'','仓库类型修订号'),
('wms_warehouse_type',7,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_warehouse_type',8,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_warehouse_type',9,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_warehouse_type',10,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_warehouse_type',11,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_warehouse_type_config',1,'config_version_id','bigint','NO',NULL,'',NULL,NULL,'','类型配置版本主键'),
('wms_warehouse_type_config',2,'warehouse_type_code','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','仓库类型稳定编码'),
('wms_warehouse_type_config',3,'version_no','int','NO',NULL,'',NULL,NULL,'','类型配置版本号'),
('wms_warehouse_type_config',4,'config_json','json','NO',NULL,'',NULL,NULL,'','类型配置内容'),
('wms_warehouse_type_config',5,'canonical_digest','char(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','规范化配置SHA-256小写十六进制'),
('wms_warehouse_type_config',6,'status','varchar(16)','NO','DRAFT','','utf8mb4','utf8mb4_0900_bin','','状态：DRAFT/ACTIVE/RETIRED'),
('wms_warehouse_type_config',7,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_warehouse_type_config',8,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_warehouse_type_config',9,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_warehouse_type_config',10,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_warehouse_type_config',11,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间');

INSERT INTO tmp_iws_space_verify_expected_indexes
    (table_name, index_name, non_unique, seq_in_index, column_name, sub_part, packed,
     nullable, index_type, index_collation, index_expression, is_visible, index_comment)
VALUES
('wms_access_channel','PRIMARY',0,1,'channel_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_access_channel','uk_wms_access_channel_zone_code',0,1,'device_zone_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_access_channel','uk_wms_access_channel_zone_code',0,2,'channel_code',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_access_channel','idx_wms_access_channel_status',1,1,'device_zone_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_access_channel','idx_wms_access_channel_status',1,2,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_location_effective','PRIMARY',0,1,'location_effective_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_location_effective','uk_wms_location_effective_object',0,1,'object_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_location_effective','uk_wms_location_effective_object',0,2,'object_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_location_effective','idx_wms_location_effective_version',1,1,'location_version_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_location_version','PRIMARY',0,1,'location_version_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_location_version','uk_wms_location_version_object_version',0,1,'object_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_location_version','uk_wms_location_version_object_version',0,2,'object_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_location_version','uk_wms_location_version_object_version',0,3,'version_no',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_location_version','idx_wms_location_version_parent',1,1,'parent_location_version_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_location_version','idx_wms_location_version_status',1,1,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_location_version','idx_wms_location_version_status',1,2,'object_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_object_visibility','PRIMARY',0,1,'visibility_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_object_visibility','uk_wms_object_visibility_object',0,1,'object_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_object_visibility','uk_wms_object_visibility_object',0,2,'object_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_object_visibility','idx_wms_object_visibility_parent',1,1,'parent_visibility_key',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_object_visibility_department','PRIMARY',0,1,'visibility_department_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_object_visibility_department','uk_wms_object_visibility_department',0,1,'visibility_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_object_visibility_department','uk_wms_object_visibility_department',0,2,'dept_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_object_visibility_department','idx_wms_object_visibility_department_dept',1,1,'dept_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_object_visibility_department','idx_wms_object_visibility_department_dept',1,2,'visibility_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_access_edge','PRIMARY',0,1,'access_edge_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_access_edge','uk_wms_storage_access_edge_pair',0,1,'blocker_storage_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_access_edge','uk_wms_storage_access_edge_pair',0,2,'blocked_storage_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_access_edge','idx_wms_storage_access_edge_blocked',1,1,'blocked_storage_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_access_member','PRIMARY',0,1,'member_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_access_member','uk_wms_storage_access_member_storage',0,1,'storage_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_access_member','uk_wms_storage_access_member_channel_storage',0,1,'channel_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_access_member','uk_wms_storage_access_member_channel_storage',0,2,'storage_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_access_member','uk_wms_storage_access_member_order',0,1,'channel_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_access_member','uk_wms_storage_access_member_order',0,2,'access_order',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_capacity','uk_wms_storage_capacity_version',0,1,'storage_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_capacity','uk_wms_storage_capacity_version',0,2,'dimension_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_capacity','uk_wms_storage_capacity_version',0,3,'capacity_version',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_capacity','PRIMARY',0,1,'capacity_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_capacity','uk_wms_storage_capacity_active',0,1,'active_capacity_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_capacity','idx_wms_storage_capacity_dimension',1,1,'storage_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_capacity','idx_wms_storage_capacity_dimension',1,2,'dimension_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_capacity','idx_wms_storage_capacity_dimension',1,3,'active_flag',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage_capacity','idx_wms_storage_capacity_dimension',1,4,'capacity_version',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_warehouse_type','PRIMARY',0,1,'warehouse_type_code',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_warehouse_type','idx_wms_warehouse_type_status',1,1,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_warehouse_type_config','PRIMARY',0,1,'config_version_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_warehouse_type_config','uk_wms_warehouse_type_config_type_version',0,1,'warehouse_type_code',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_warehouse_type_config','uk_wms_warehouse_type_config_type_version',0,2,'version_no',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_warehouse_type_config','idx_wms_warehouse_type_config_status',1,1,'warehouse_type_code',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_warehouse_type_config','idx_wms_warehouse_type_config_status',1,2,'status',NULL,NULL,'','BTREE','A',NULL,'YES','');

INSERT INTO tmp_iws_space_verify_expected_checks
    (table_name, constraint_name, check_clause, enforced)
VALUES
('wms_access_channel','ck_wms_access_channel_revision','(`revision` >= 1)','YES'),
('wms_access_channel','ck_wms_access_channel_status','(`status` in (_utf8mb4\\''ACTIVE\\'',_utf8mb4\\''RETIRING\\'',_utf8mb4\\''RETIRED\\''))','YES'),
('wms_location_effective','ck_wms_location_effective_revision','(`revision` >= 1)','YES'),
('wms_location_version','ck_wms_location_version_digest','regexp_like(`canonical_digest`,_utf8mb4\\''^[0-9a-f]{64}$\\'',_utf8mb4\\''c\\'')','YES'),
('wms_location_version','ck_wms_location_version_status','(`status` in (_utf8mb4\\''DRAFT\\'',_utf8mb4\\''ACTIVE\\'',_utf8mb4\\''RETIRED\\''))','YES'),
('wms_location_version','ck_wms_location_version_version_no','(`version_no` >= 1)','YES'),
('wms_object_visibility','ck_wms_object_visibility_company_mode','((`object_type` <> _utf8mb4\\''COMPANY\\'') or (`mode` = _utf8mb4\\''VALUE\\''))','YES'),
('wms_object_visibility','ck_wms_object_visibility_company_parent','((`object_type` <> _utf8mb4\\''COMPANY\\'') or (`parent_visibility_key` is null))','YES'),
('wms_object_visibility','ck_wms_object_visibility_mode','(`mode` in (_utf8mb4\\''INHERIT\\'',_utf8mb4\\''VALUE\\'',_utf8mb4\\''CLEAR\\''))','YES'),
('wms_object_visibility','ck_wms_object_visibility_revision','(`revision` >= 1)','YES'),
('wms_storage_access_edge','ck_wms_storage_access_edge_no_self','(`blocker_storage_id` <> `blocked_storage_id`)','YES'),
('wms_storage_access_edge','ck_wms_storage_access_edge_revision','(`revision` >= 1)','YES'),
('wms_storage_access_member','ck_wms_storage_access_member_order','(`access_order` >= 0)','YES'),
('wms_storage_access_member','ck_wms_storage_access_member_revision','(`revision` >= 1)','YES'),
('wms_storage_capacity','ck_wms_storage_capacity_active_flag','(`active_flag` in (0,1))','YES'),
('wms_storage_capacity','ck_wms_storage_capacity_scale_consistent','(`capacity_value` = truncate(`capacity_value`,`scale`))','YES'),
('wms_storage_capacity','ck_wms_storage_capacity_scale','(`scale` between 0 and 12)','YES'),
('wms_storage_capacity','ck_wms_storage_capacity_value','(`capacity_value` >= 0)','YES'),
('wms_storage_capacity','ck_wms_storage_capacity_version','(`capacity_version` >= 1)','YES'),
('wms_warehouse_type','ck_wms_warehouse_type_revision','(`revision` >= 1)','YES'),
('wms_warehouse_type','ck_wms_warehouse_type_status','(`status` in (_utf8mb4\\''ACTIVE\\'',_utf8mb4\\''DISABLED\\''))','YES'),
('wms_warehouse_type_config','ck_wms_warehouse_type_config_digest','regexp_like(`canonical_digest`,_utf8mb4\\''^[0-9a-f]{64}$\\'',_utf8mb4\\''c\\'')','YES'),
('wms_warehouse_type_config','ck_wms_warehouse_type_config_status','(`status` in (_utf8mb4\\''DRAFT\\'',_utf8mb4\\''ACTIVE\\'',_utf8mb4\\''RETIRED\\''))','YES'),
('wms_warehouse_type_config','ck_wms_warehouse_type_config_version_no','(`version_no` >= 1)','YES');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_verify_actual_tables;
CREATE TEMPORARY TABLE tmp_iws_space_verify_actual_tables AS
SELECT TABLE_NAME AS table_name, TABLE_TYPE AS table_type, ENGINE AS engine,
       TABLE_COLLATION AS table_collation, CREATE_OPTIONS AS create_options,
       TABLE_COMMENT AS table_comment
FROM information_schema.TABLES
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_verify_actual_columns;
CREATE TEMPORARY TABLE tmp_iws_space_verify_actual_columns AS
SELECT TABLE_NAME AS table_name, ORDINAL_POSITION AS ordinal_position,
       COLUMN_NAME AS column_name, COLUMN_TYPE AS column_type,
       IS_NULLABLE AS is_nullable, COLUMN_DEFAULT AS column_default,
       EXTRA AS extra, CHARACTER_SET_NAME AS character_set_name,
       COLLATION_NAME AS collation_name, GENERATION_EXPRESSION AS generation_expression,
       COLUMN_COMMENT AS column_comment
FROM information_schema.COLUMNS
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_verify_actual_indexes;
CREATE TEMPORARY TABLE tmp_iws_space_verify_actual_indexes AS
SELECT TABLE_NAME AS table_name, INDEX_NAME AS index_name,
       NON_UNIQUE AS non_unique, SEQ_IN_INDEX AS seq_in_index,
       COLUMN_NAME AS column_name, SUB_PART AS sub_part, PACKED AS packed,
       NULLABLE AS nullable, INDEX_TYPE AS index_type, COLLATION AS index_collation,
       EXPRESSION AS index_expression, IS_VISIBLE AS is_visible,
       INDEX_COMMENT AS index_comment
FROM information_schema.STATISTICS
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_verify_actual_checks;
CREATE TEMPORARY TABLE tmp_iws_space_verify_actual_checks AS
SELECT tc.TABLE_NAME AS table_name, tc.CONSTRAINT_NAME AS constraint_name,
       cc.CHECK_CLAUSE AS check_clause, tc.ENFORCED AS enforced
FROM information_schema.TABLE_CONSTRAINTS tc
JOIN information_schema.CHECK_CONSTRAINTS cc
  ON CAST(cc.CONSTRAINT_SCHEMA AS BINARY) = CAST(tc.CONSTRAINT_SCHEMA AS BINARY)
 AND CAST(cc.CONSTRAINT_NAME AS BINARY) = CAST(tc.CONSTRAINT_NAME AS BINARY)
WHERE CAST(tc.CONSTRAINT_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND tc.CONSTRAINT_TYPE = 'CHECK'
  AND FIND_IN_SET(CAST(tc.TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

SET @table_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_space_verify_actual_tables a
    LEFT JOIN tmp_iws_space_verify_expected_tables e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE e.table_name IS NULL OR a.table_type <> e.table_type
       OR NOT (CAST(a.engine AS BINARY) <=> CAST(e.engine AS BINARY))
       OR NOT (CAST(a.table_collation AS BINARY) <=> CAST(e.table_collation AS BINARY))
       OR NOT (CAST(a.create_options AS BINARY) <=> CAST(e.create_options AS BINARY))
       OR NOT (CAST(a.table_comment AS BINARY) <=> CAST(e.table_comment AS BINARY))
);
SET @table_expected_missing = (
    SELECT COUNT(*) FROM tmp_iws_space_verify_expected_tables e
    LEFT JOIN tmp_iws_space_verify_actual_tables a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE a.table_name IS NULL
);
SET @column_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_space_verify_actual_columns a
    LEFT JOIN tmp_iws_space_verify_expected_columns e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE e.column_name IS NULL OR a.ordinal_position <> e.ordinal_position
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
    SELECT COUNT(*) FROM tmp_iws_space_verify_expected_columns e
    LEFT JOIN tmp_iws_space_verify_actual_columns a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE a.column_name IS NULL
);
SET @index_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_space_verify_actual_indexes a
    LEFT JOIN tmp_iws_space_verify_expected_indexes e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE e.index_name IS NULL OR a.non_unique <> e.non_unique
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
    SELECT COUNT(*) FROM tmp_iws_space_verify_expected_indexes e
    LEFT JOIN tmp_iws_space_verify_actual_indexes a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE a.index_name IS NULL
);
SET @check_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_space_verify_actual_checks a
    LEFT JOIN tmp_iws_space_verify_expected_checks e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE e.constraint_name IS NULL
       OR NOT (CAST(a.check_clause AS BINARY) <=> CAST(e.check_clause AS BINARY))
       OR NOT (CAST(a.enforced AS BINARY) <=> CAST(e.enforced AS BINARY))
);
SET @check_expected_missing = (
    SELECT COUNT(*) FROM tmp_iws_space_verify_expected_checks e
    LEFT JOIN tmp_iws_space_verify_actual_checks a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE a.constraint_name IS NULL
);

SET @expected_table_rows = (SELECT COUNT(*) FROM tmp_iws_space_verify_expected_tables);
SET @actual_table_rows = (SELECT COUNT(*) FROM tmp_iws_space_verify_actual_tables);
SET @expected_column_rows = (SELECT COUNT(*) FROM tmp_iws_space_verify_expected_columns);
SET @actual_column_rows = (SELECT COUNT(*) FROM tmp_iws_space_verify_actual_columns);
SET @expected_index_rows = (SELECT COUNT(*) FROM tmp_iws_space_verify_expected_indexes);
SET @actual_index_rows = (SELECT COUNT(*) FROM tmp_iws_space_verify_actual_indexes);
SET @expected_check_rows = (SELECT COUNT(*) FROM tmp_iws_space_verify_expected_checks);
SET @actual_check_rows = (SELECT COUNT(*) FROM tmp_iws_space_verify_actual_checks);

-- 表对象：同名 VIEW、未知表类型和同名非表对象都失败。
SET @non_base_table_count = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0
      AND TABLE_TYPE <> 'BASE TABLE'
);
-- 默认 NULL 与字面 '<NULL>' 使用 NULL-safe 字节比较；不把字符串当作缺失。
SET @default_signature_bad = (
    SELECT COUNT(*) FROM tmp_iws_space_verify_actual_columns a
    JOIN tmp_iws_space_verify_expected_columns e USING (table_name, column_name)
    WHERE NOT (CAST(a.column_default AS BINARY) <=> CAST(e.column_default AS BINARY))
);
-- 严格核对尾列、索引尾字段和 CHECK 尾部：逐位置双向集合已覆盖，这里再单独
-- 展示列名位置、索引 seq 和 enforced 计数，失败不可被行数相同掩盖。
SET @column_tail_count = (
    SELECT COUNT(*) FROM tmp_iws_space_verify_actual_columns a
    JOIN tmp_iws_space_verify_expected_columns e USING (table_name, ordinal_position)
    WHERE CAST(a.column_name AS BINARY) <> CAST(e.column_name AS BINARY)
);
SET @index_tail_count = (
    SELECT COUNT(*) FROM tmp_iws_space_verify_actual_indexes a
    JOIN tmp_iws_space_verify_expected_indexes e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE NOT (CAST(a.column_name AS BINARY) <=> CAST(e.column_name AS BINARY))
);
SET @check_not_enforced = (
    SELECT COUNT(*) FROM tmp_iws_space_verify_actual_checks WHERE enforced <> 'YES'
);
SET @capacity_active_unique_bad = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @target_schema AND TABLE_NAME = 'wms_storage_capacity'
      AND INDEX_NAME = 'uk_wms_storage_capacity_active'
      AND (NON_UNIQUE <> 0 OR SEQ_IN_INDEX <> 1
           OR COLUMN_NAME <> 'active_capacity_key' OR IS_VISIBLE <> 'YES')
);
SET @capacity_generated_bad = (
    SELECT COUNT(*) FROM tmp_iws_space_verify_actual_columns
    WHERE table_name = 'wms_storage_capacity' AND column_name = 'active_capacity_key'
      AND (is_nullable <> 'NO' OR extra <> 'STORED GENERATED'
           OR CAST(generation_expression AS BINARY) <>
              CAST((SELECT generation_expression FROM tmp_iws_space_verify_expected_columns
                    WHERE table_name = 'wms_storage_capacity' AND column_name = 'active_capacity_key') AS BINARY))
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
    SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
    WHERE CAST(REFERENCED_TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND FIND_IN_SET(CAST(REFERENCED_TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0
);

SET @structure_diff =
    @non_base_table_count + @table_actual_extra + @table_expected_missing
    + @column_actual_extra + @column_expected_missing
    + @index_actual_extra + @index_expected_missing
    + @check_actual_extra + @check_expected_missing
    + @default_signature_bad + @column_tail_count + @index_tail_count
    + @check_not_enforced + @capacity_active_unique_bad
    + @capacity_generated_bad + @own_fk_count + @trigger_count
    + @inbound_fk_count;
SET @verify_ok = IF(
    @metadata_visibility_ok = 1 AND @schema_ok = 1 AND @version_ok = 1
    AND @actual_table_rows = @expected_table_count
    AND @actual_column_rows = @expected_column_rows
    AND @actual_index_rows = @expected_index_rows
    AND @actual_check_rows = @expected_check_rows
    AND @structure_diff = 0,
    1, 0
);

SELECT
    @foundation_release_id AS `发布标识`,
    @foundation_contract_version AS `合同版本`,
    @target_schema AS `目标库`,
    DATABASE() AS `当前库`,
    VERSION() AS `MySQL版本`,
    @metadata_visibility_ok AS `元数据权限通过`,
    @schema_ok AS `目标库匹配`,
    @version_ok AS `MySQL版本匹配`,
    IF(@verify_ok = 1, '通过', '失败') AS `验收结果`;
SELECT
    CONCAT(@actual_table_rows, '/', @expected_table_rows) AS `表元数据行`,
    CONCAT(@actual_column_rows, '/', @expected_column_rows) AS `列元数据行`,
    CONCAT(@actual_index_rows, '/', @expected_index_rows) AS `索引元数据行`,
    CONCAT(@actual_check_rows, '/', @expected_check_rows) AS `CHECK元数据行`,
    @structure_diff AS `结构差异合计`,
    @non_base_table_count AS `同名非表对象数`,
    @default_signature_bad AS `默认值字节差异数`,
    @column_tail_count AS `列尾部差异数`,
    @index_tail_count AS `索引尾字段差异数`,
    @check_not_enforced AS `未强制执行CHECK数`,
    @capacity_active_unique_bad AS `容量活动唯一键差异数`,
    @capacity_generated_bad AS `容量生成列差异数`,
    @own_fk_count AS `本表外键数`,
    @trigger_count AS `触发器数`,
    @inbound_fk_count AS `外部指入外键数`;
SELECT JSON_EXTRACT(IF(@verify_ok = 1, '{}', JSON_QUOTE('invalid')), '$') AS `空间foundation验收门禁`;
-- 门禁为 {} 时返回成功；失败时让执行器以非零退出，禁止上层把展示行当验收通过。
SELECT IF(@verify_ok = 1, 1, JSON_EXTRACT(JSON_QUOTE('invalid'), '$')) AS `空间foundation退出码`;
SET @verify_fail_fast = IF(@verify_ok = 1, 0, CAST('invalid' AS JSON));
SELECT IF(@verify_ok = 1, 1, @verify_fail_fast) AS `空间foundation硬门禁`;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_verify_actual_checks;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_verify_actual_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_verify_actual_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_verify_actual_tables;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_verify_expected_checks;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_verify_expected_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_verify_expected_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_verify_expected_tables;
SET @verify_cleanup_ok = IF(@verify_ok = 1, 1, @verify_fail_fast);
SELECT @verify_cleanup_ok AS `空间foundation验收收尾门禁`;
