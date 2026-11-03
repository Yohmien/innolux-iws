-- ============================================================================
-- IWS+ 库存空间、访问通道、位置版本、可见范围与容量 foundation
-- ============================================================================
-- 目标数据库：MySQL 8.0.45
-- 目标库参数：@target_schema，集中定义于下方；必须与连接 DATABASE() 字节一致
-- 目标对象（只新增，不修改既有 warehouse/zone/storage）：
--   wms_warehouse_type                    仓库类型
--   wms_warehouse_type_config             仓库类型配置版本
--   wms_access_channel                    设备访问通道
--   wms_storage_access_member             通道储位成员与显式访问顺序
--   wms_storage_access_edge               通道储位阻挡边
--   wms_location_version                  空间位置不可变配置版本
--   wms_location_effective                空间位置当前生效版本
--   wms_object_visibility                 对象可见范围三态头
--   wms_object_visibility_department      对象可见部门集合
--   wms_storage_capacity                  储位容量版本
-- 依据：
--   work/inventory-p1-contract-20261027.md §5.1、§5.2、§6
--   主方案 §4、§5、§12；allocation 通道合同 §4—§8；tracker INV-P2-01
-- 范围：
--   只建立独立目标结构。跨表 ID 均为逻辑引用，不建立物理外键；不修改已有
--   warehouse/zone/storage，不回填、不切换、不写 seed、不写容量、不授权。
--   本脚本不接受 S2/S3 完成，也不改变 P2 父任务状态。
-- 冻结语义：
--   稳定编码和闭合枚举使用 utf8mb4_0900_bin 与精确 CHECK；未闭合的
--   access_mode 等稳定代码保留最小 VARCHAR，不猜测业务码值。标准审计字段为
--   create_dept/create_by/create_time/update_by/update_time。
--   容量以 decimal(38,12)，非负，scale 0..12，capacity_version >= 1；
--   active_flag=1 表示当前版本，active_capacity_key 是 STORED NOT NULL 生成列：
--     活动行 A:<16位storage_id十六进制>:<dimension_key UTF-8十六进制>
--     历史行 H:<16位capacity_id十六进制>
--   因此同 storage/dimension 至多一个活动版本，历史版本仍可并存，不使用 NULL
--   哨兵或分隔符串接业务键。capacity_id 由服务分配，不使用 AUTO_INCREMENT；
--   TOP_LEVEL_LOAD=1 是未来配置默认语义，不在本脚本插容量行。
--   主代理已冻结 active_flag、活动/历史生成键及显式服务分配 capacity_id。
--   生成键最长 19 + 64 * 4 * 2 = 531 字符，索引上界 2124 字节。
--   当前唯一域为 storage_id/dimension_key，单位变化创建新版本并关闭原活动。
--   同维度 capacity_version 唯一；capacity_value 必须符合声明 scale。
-- 业务约束边界：
--   通道成员同当前通道、边同通道、无环、当前物理父链、visibility 父键有效性、
--   location config_json 内容、canonical_digest 计算和 CAS 由后续服务事务保证。
--   DDL 只拒绝自环、非正版本、已闭合枚举、公司 visibility 非 VALUE 及根对象
--   带 parent 等数据库可判定事实。
-- 幂等与冲突：
--   先读取十张目标表完整元数据，与脚本内预期逐表、逐列、逐索引、逐 CHECK
--   比较。全缺、部分已满足或全部已满足可继续；同名 VIEW、未知列/索引/CHECK、
--   表选项漂移、目标表触发器、自身外键、任意 schema 指入本目标的外键、权限不可见或
--   错误目标库均在首条 DDL 前硬失败。已漂移表不 ALTER、不删除、不覆盖。
-- 离线账号合同：
--   直接持有全局 SELECT、TRIGGER，partial_revokes=OFF；schema/table/角色授权
--   不满足跨 schema 入向外键和触发器的完整可见性证明。
-- 执行：
--   mysql --no-defaults --protocol=TCP --host=<主机> --port=<端口> \
--     --user=<账号> --default-character-set=utf8mb4 --database=innolux-iws \
--     --batch --raw < script/sql/iws_inventory_management_space_foundation.sql
--   必须使用 stdin 或等价首错退出执行器，不得添加 --force。DDL 隐式提交；
--   部分执行后依据 INFORMATION_SCHEMA 重跑，不承诺整份脚本事务回滚。
-- ============================================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 集中参数
-- ----------------------------------------------------------------------------
SET @target_schema = 'innolux-iws';
SET @foundation_release_id = 'INV-P2-01-SPACE-FOUNDATION-20261029';
SET @foundation_contract_version = 'SPACE-10T-P2-01-20261029';
SET @expected_mysql_version = '8.0.45';
SET @expected_table_count = 10;
SET @target_schema_q = CONCAT('`', REPLACE(@target_schema, '`', '``'), '`');
SET @target_table_names = 'wms_warehouse_type,wms_warehouse_type_config,wms_access_channel,wms_storage_access_member,wms_storage_access_edge,wms_location_version,wms_location_effective,wms_object_visibility,wms_object_visibility_department,wms_storage_capacity';

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
-- 预期元数据：逐对象保存完整规范化行；不使用摘要替代字段、索引和 CHECK 比较
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_expected_tables;
CREATE TEMPORARY TABLE tmp_iws_space_expected_tables (
    table_name       varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    table_type       varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    engine           varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    table_collation  varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    create_options   varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    table_comment    varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_expected_columns;
CREATE TEMPORARY TABLE tmp_iws_space_expected_columns (
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
    UNIQUE KEY uk_tmp_iws_space_expected_columns (table_name, column_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_expected_indexes;
CREATE TEMPORARY TABLE tmp_iws_space_expected_indexes (
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

DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_expected_checks;
CREATE TEMPORARY TABLE tmp_iws_space_expected_checks (
    table_name      varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    constraint_name varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    check_clause    longtext     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    enforced        varchar(3)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name, constraint_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_iws_space_expected_tables
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

INSERT INTO tmp_iws_space_expected_columns
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

INSERT INTO tmp_iws_space_expected_indexes
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

INSERT INTO tmp_iws_space_expected_checks
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

-- ----------------------------------------------------------------------------
-- 预检：读取现有目标元数据并拒绝任何未声明的漂移或旁路对象
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_actual_tables;
CREATE TEMPORARY TABLE tmp_iws_space_actual_tables AS
SELECT TABLE_NAME AS table_name, TABLE_TYPE AS table_type, ENGINE AS engine,
       TABLE_COLLATION AS table_collation, CREATE_OPTIONS AS create_options,
       TABLE_COMMENT AS table_comment
FROM information_schema.TABLES
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_actual_columns;
CREATE TEMPORARY TABLE tmp_iws_space_actual_columns AS
SELECT TABLE_NAME AS table_name, ORDINAL_POSITION AS ordinal_position,
       COLUMN_NAME AS column_name, COLUMN_TYPE AS column_type,
       IS_NULLABLE AS is_nullable, COLUMN_DEFAULT AS column_default,
       EXTRA AS extra, CHARACTER_SET_NAME AS character_set_name,
       COLLATION_NAME AS collation_name, GENERATION_EXPRESSION AS generation_expression,
       COLUMN_COMMENT AS column_comment
FROM information_schema.COLUMNS
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_actual_indexes;
CREATE TEMPORARY TABLE tmp_iws_space_actual_indexes AS
SELECT TABLE_NAME AS table_name, INDEX_NAME AS index_name,
       NON_UNIQUE AS non_unique, SEQ_IN_INDEX AS seq_in_index,
       COLUMN_NAME AS column_name, SUB_PART AS sub_part, PACKED AS packed,
       NULLABLE AS nullable, INDEX_TYPE AS index_type, COLLATION AS index_collation,
       EXPRESSION AS index_expression, IS_VISIBLE AS is_visible,
       INDEX_COMMENT AS index_comment
FROM information_schema.STATISTICS
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_actual_checks;
CREATE TEMPORARY TABLE tmp_iws_space_actual_checks AS
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
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0
      AND TABLE_TYPE <> 'BASE TABLE'
);
SET @existing_table_count = (
    SELECT COUNT(*) FROM tmp_iws_space_actual_tables WHERE table_type = 'BASE TABLE'
);
SET @existing_base_table_names = (
    SELECT IFNULL(GROUP_CONCAT(table_name ORDER BY table_name SEPARATOR ','), '')
    FROM tmp_iws_space_actual_tables WHERE table_type = 'BASE TABLE'
);
SET @pre_table_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_space_actual_tables a
    LEFT JOIN tmp_iws_space_expected_tables e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE e.table_name IS NULL
       OR CAST(a.table_type AS BINARY) <> CAST(e.table_type AS BINARY)
       OR NOT (CAST(a.engine AS BINARY) <=> CAST(e.engine AS BINARY))
       OR NOT (CAST(a.table_collation AS BINARY) <=> CAST(e.table_collation AS BINARY))
       OR CAST(a.create_options AS BINARY) <> CAST(e.create_options AS BINARY)
       OR CAST(a.table_comment AS BINARY) <> CAST(e.table_comment AS BINARY)
);
SET @pre_column_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_space_actual_columns a
    LEFT JOIN tmp_iws_space_actual_tables t
      ON CAST(t.table_name AS BINARY) = CAST(a.table_name AS BINARY) AND t.table_type = 'BASE TABLE'
    LEFT JOIN tmp_iws_space_expected_columns e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE t.table_name IS NOT NULL
      AND (e.column_name IS NULL OR a.ordinal_position <> e.ordinal_position
       OR CAST(a.column_type AS BINARY) <> CAST(e.column_type AS BINARY)
       OR CAST(a.is_nullable AS BINARY) <> CAST(e.is_nullable AS BINARY)
       OR NOT (CAST(a.column_default AS BINARY) <=> CAST(e.column_default AS BINARY))
       OR CAST(a.extra AS BINARY) <> CAST(e.extra AS BINARY)
       OR NOT (CAST(a.character_set_name AS BINARY) <=> CAST(e.character_set_name AS BINARY))
       OR NOT (CAST(a.collation_name AS BINARY) <=> CAST(e.collation_name AS BINARY))
       OR CAST(a.generation_expression AS BINARY) <> CAST(e.generation_expression AS BINARY)
       OR CAST(a.column_comment AS BINARY) <> CAST(e.column_comment AS BINARY))
);
SET @pre_column_expected_missing = (
    SELECT COUNT(*) FROM tmp_iws_space_expected_columns e
    JOIN tmp_iws_space_actual_tables t
      ON CAST(t.table_name AS BINARY) = CAST(e.table_name AS BINARY) AND t.table_type = 'BASE TABLE'
    LEFT JOIN tmp_iws_space_actual_columns a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE a.column_name IS NULL
);
SET @pre_index_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_space_actual_indexes a
    LEFT JOIN tmp_iws_space_actual_tables t
      ON CAST(t.table_name AS BINARY) = CAST(a.table_name AS BINARY) AND t.table_type = 'BASE TABLE'
    LEFT JOIN tmp_iws_space_expected_indexes e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE t.table_name IS NOT NULL
      AND (e.index_name IS NULL OR a.non_unique <> e.non_unique
       OR NOT (CAST(a.column_name AS BINARY) <=> CAST(e.column_name AS BINARY))
       OR NOT (a.sub_part <=> e.sub_part)
       OR NOT (CAST(a.packed AS BINARY) <=> CAST(e.packed AS BINARY))
       OR NOT (CAST(a.nullable AS BINARY) <=> CAST(e.nullable AS BINARY))
       OR CAST(a.index_type AS BINARY) <> CAST(e.index_type AS BINARY)
       OR NOT (CAST(a.index_collation AS BINARY) <=> CAST(e.index_collation AS BINARY))
       OR NOT (CAST(a.index_expression AS BINARY) <=> CAST(e.index_expression AS BINARY))
       OR CAST(a.is_visible AS BINARY) <> CAST(e.is_visible AS BINARY)
       OR CAST(a.index_comment AS BINARY) <> CAST(e.index_comment AS BINARY))
);
SET @pre_index_expected_missing = (
    SELECT COUNT(*) FROM tmp_iws_space_expected_indexes e
    JOIN tmp_iws_space_actual_tables t
      ON CAST(t.table_name AS BINARY) = CAST(e.table_name AS BINARY) AND t.table_type = 'BASE TABLE'
    LEFT JOIN tmp_iws_space_actual_indexes a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE a.index_name IS NULL
);
SET @pre_check_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_space_actual_checks a
    LEFT JOIN tmp_iws_space_actual_tables t
      ON CAST(t.table_name AS BINARY) = CAST(a.table_name AS BINARY) AND t.table_type = 'BASE TABLE'
    LEFT JOIN tmp_iws_space_expected_checks e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE t.table_name IS NOT NULL
      AND (e.constraint_name IS NULL
       OR CAST(a.check_clause AS BINARY) <> CAST(e.check_clause AS BINARY)
       OR CAST(a.enforced AS BINARY) <> CAST(e.enforced AS BINARY))
);
SET @pre_check_expected_missing = (
    SELECT COUNT(*) FROM tmp_iws_space_expected_checks e
    JOIN tmp_iws_space_actual_tables t
      ON CAST(t.table_name AS BINARY) = CAST(e.table_name AS BINARY) AND t.table_type = 'BASE TABLE'
    LEFT JOIN tmp_iws_space_actual_checks a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE a.constraint_name IS NULL
);
SET @pre_table_definition_mismatch =
    @pre_table_actual_extra
    + @pre_column_actual_extra + @pre_column_expected_missing
    + @pre_index_actual_extra + @pre_index_expected_missing
    + @pre_check_actual_extra + @pre_check_expected_missing;

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
-- 只拒绝外部 schema 指入本目标 schema 的 FK；其他 schema 同名表不属于本目标。
-- REFERENCED_TABLE_SCHEMA 取自 KEY_COLUMN_USAGE；REFERENTIAL_CONSTRAINTS
-- 仅提供 UNIQUE_CONSTRAINT_SCHEMA，两者不能混用。
SET @inbound_fk_count = (
    SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
    WHERE CAST(REFERENCED_TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND FIND_IN_SET(
          CAST(REFERENCED_TABLE_NAME AS BINARY),
          CAST(@target_table_names AS BINARY)
      ) > 0
);

SET @structural_preflight_ok = IF(
    @metadata_visibility_ok = 1
    AND @schema_ok = 1 AND @schema_exists = 1 AND @version_ok = 1
    AND @object_conflict_count = 0
    AND @existing_table_count <= @expected_table_count
    AND @pre_table_definition_mismatch = 0
    AND @own_fk_count = 0 AND @trigger_count = 0 AND @inbound_fk_count = 0,
    1, 0
);
-- 缺失目标表不是冲突：该表没有可比较的列/索引/CHECK，由后续 CREATE 补齐。
-- 已有表必须逐字段满足预期；partial 首跑和 DDL 中断恢复走同一分支。
SET @preflight_ok = @structural_preflight_ok;

SELECT
    @foundation_release_id AS `发布标识`,
    @foundation_contract_version AS `合同版本`,
    @target_schema AS `目标库`,
    DATABASE() AS `当前库`,
    VERSION() AS `MySQL版本`,
    @metadata_visibility_ok AS `元数据权限通过`,
    @schema_ok AS `目标库匹配`,
    @existing_table_count AS `现有目标表数`,
    @expected_table_count AS `预期目标表数`,
    @existing_base_table_names AS `现有表字节名`,
    @object_conflict_count AS `同名非表对象数`,
    @pre_table_definition_mismatch AS `目标结构冲突数`,
    @own_fk_count AS `本表外键数`,
    @trigger_count AS `触发器数`,
    @inbound_fk_count AS `外部指入外键数`,
    IF(@preflight_ok = 1, '预检通过', '预检失败，禁止任何空间 foundation DDL') AS `预检结果`;
SELECT JSON_EXTRACT(IF(@preflight_ok = 1, '{}', JSON_QUOTE('invalid')), '$') AS `空间foundation预检门禁`;
SELECT IF(@preflight_ok = 1, 1, JSON_EXTRACT(JSON_QUOTE('invalid'), '$')) AS `空间foundation预检退出码`;
-- 预检失败也必须首错退出；DO 0 只用于成功分支保持脚本顺序。
SET @fail_fast = IF(@preflight_ok = 1, 0, CAST('invalid' AS JSON));
SELECT IF(@preflight_ok = 1, 1, @fail_fast) AS `空间foundation预检硬门禁`;

-- ----------------------------------------------------------------------------
-- 目标 DDL：仅 CREATE TABLE，不 ALTER 任何存量对象
-- ----------------------------------------------------------------------------
SET @ddl = IF(@preflight_ok = 1 AND NOT EXISTS (
    SELECT 1 FROM tmp_iws_space_actual_tables
    WHERE CAST(table_name AS BINARY) = CAST('wms_warehouse_type' AS BINARY)
), 'CREATE TABLE `wms_warehouse_type` (
    `warehouse_type_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''仓库类型稳定编码'',
    `warehouse_type_name` varchar(128) NOT NULL COMMENT ''仓库类型名称'',
    `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT ''ACTIVE'' COMMENT ''状态：ACTIVE/DISABLED'',
    `contract_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''类型合同版本'',
    `config_schema_json` json NOT NULL COMMENT ''类型配置JSON Schema'',
    `revision` int NOT NULL DEFAULT 1 COMMENT ''仓库类型修订号'',
    `create_dept` bigint NULL COMMENT ''创建部门'',
    `create_by` bigint NULL COMMENT ''创建者'',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',
    `update_by` bigint NULL COMMENT ''更新者'',
    `update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',
    PRIMARY KEY (`warehouse_type_code`),
    KEY `idx_wms_warehouse_type_status` (`status`),
    CONSTRAINT `ck_wms_warehouse_type_revision` CHECK (`revision` >= 1),
    CONSTRAINT `ck_wms_warehouse_type_status` CHECK (`status` IN (''ACTIVE'',''DISABLED''))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS仓库类型''', 'DO 0');
PREPARE space_stmt FROM @ddl;
EXECUTE space_stmt;
DEALLOCATE PREPARE space_stmt;

SET @ddl = IF(@preflight_ok = 1 AND NOT EXISTS (
    SELECT 1 FROM tmp_iws_space_actual_tables
    WHERE CAST(table_name AS BINARY) = CAST('wms_warehouse_type_config' AS BINARY)
), 'CREATE TABLE `wms_warehouse_type_config` (
    `config_version_id` bigint NOT NULL COMMENT ''类型配置版本主键'',
    `warehouse_type_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''仓库类型稳定编码'',
    `version_no` int NOT NULL COMMENT ''类型配置版本号'',
    `config_json` json NOT NULL COMMENT ''类型配置内容'',
    `canonical_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''规范化配置SHA-256小写十六进制'',
    `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT ''DRAFT'' COMMENT ''状态：DRAFT/ACTIVE/RETIRED'',
    `create_dept` bigint NULL COMMENT ''创建部门'',
    `create_by` bigint NULL COMMENT ''创建者'',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',
    `update_by` bigint NULL COMMENT ''更新者'',
    `update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',
    PRIMARY KEY (`config_version_id`),
    UNIQUE KEY `uk_wms_warehouse_type_config_type_version` (`warehouse_type_code`, `version_no`),
    KEY `idx_wms_warehouse_type_config_status` (`warehouse_type_code`, `status`),
    CONSTRAINT `ck_wms_warehouse_type_config_digest` CHECK (REGEXP_LIKE(`canonical_digest`, _utf8mb4''^[0-9a-f]{64}$'', ''c'')),
    CONSTRAINT `ck_wms_warehouse_type_config_status` CHECK (`status` IN (''DRAFT'',''ACTIVE'',''RETIRED'')),
    CONSTRAINT `ck_wms_warehouse_type_config_version_no` CHECK (`version_no` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS仓库类型配置版本''', 'DO 0');
PREPARE space_stmt FROM @ddl;
EXECUTE space_stmt;
DEALLOCATE PREPARE space_stmt;

SET @ddl = IF(@preflight_ok = 1 AND NOT EXISTS (
    SELECT 1 FROM tmp_iws_space_actual_tables
    WHERE CAST(table_name AS BINARY) = CAST('wms_access_channel' AS BINARY)
), 'CREATE TABLE `wms_access_channel` (
    `channel_id` bigint NOT NULL COMMENT ''访问通道主键'',
    `device_zone_id` bigint NOT NULL COMMENT ''设备库区ID，逻辑关联 wms_warehouse_zone.zone_id'',
    `channel_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''通道稳定编码'',
    `access_mode` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''访问模式，未闭合稳定代码由目录约束'',
    `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT ''ACTIVE'' COMMENT ''状态：ACTIVE/RETIRING/RETIRED'',
    `revision` int NOT NULL DEFAULT 1 COMMENT ''访问通道修订号'',
    `create_dept` bigint NULL COMMENT ''创建部门'',
    `create_by` bigint NULL COMMENT ''创建者'',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',
    `update_by` bigint NULL COMMENT ''更新者'',
    `update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',
    PRIMARY KEY (`channel_id`),
    UNIQUE KEY `uk_wms_access_channel_zone_code` (`device_zone_id`, `channel_code`),
    KEY `idx_wms_access_channel_status` (`device_zone_id`, `status`),
    CONSTRAINT `ck_wms_access_channel_revision` CHECK (`revision` >= 1),
    CONSTRAINT `ck_wms_access_channel_status` CHECK (`status` IN (''ACTIVE'',''RETIRING'',''RETIRED''))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS设备访问通道''', 'DO 0');
PREPARE space_stmt FROM @ddl;
EXECUTE space_stmt;
DEALLOCATE PREPARE space_stmt;

SET @ddl = IF(@preflight_ok = 1 AND NOT EXISTS (
    SELECT 1 FROM tmp_iws_space_actual_tables
    WHERE CAST(table_name AS BINARY) = CAST('wms_storage_access_member' AS BINARY)
), 'CREATE TABLE `wms_storage_access_member` (
    `member_id` bigint NOT NULL COMMENT ''通道成员主键'',
    `channel_id` bigint NOT NULL COMMENT ''访问通道ID'',
    `storage_id` bigint NOT NULL COMMENT ''稳定储位ID'',
    `access_order` int NOT NULL COMMENT ''显式访问顺序'',
    `revision` int NOT NULL DEFAULT 1 COMMENT ''通道成员修订号'',
    `create_dept` bigint NULL COMMENT ''创建部门'',
    `create_by` bigint NULL COMMENT ''创建者'',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',
    `update_by` bigint NULL COMMENT ''更新者'',
    `update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',
    PRIMARY KEY (`member_id`),
    UNIQUE KEY `uk_wms_storage_access_member_storage` (`storage_id`),
    UNIQUE KEY `uk_wms_storage_access_member_channel_storage` (`channel_id`, `storage_id`),
    UNIQUE KEY `uk_wms_storage_access_member_order` (`channel_id`, `access_order`),
    CONSTRAINT `ck_wms_storage_access_member_order` CHECK (`access_order` >= 0),
    CONSTRAINT `ck_wms_storage_access_member_revision` CHECK (`revision` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS通道储位成员''', 'DO 0');
PREPARE space_stmt FROM @ddl;
EXECUTE space_stmt;
DEALLOCATE PREPARE space_stmt;

SET @ddl = IF(@preflight_ok = 1 AND NOT EXISTS (
    SELECT 1 FROM tmp_iws_space_actual_tables
    WHERE CAST(table_name AS BINARY) = CAST('wms_storage_access_edge' AS BINARY)
), 'CREATE TABLE `wms_storage_access_edge` (
    `access_edge_id` bigint NOT NULL COMMENT ''阻挡边主键'',
    `blocker_storage_id` bigint NOT NULL COMMENT ''阻挡储位ID'',
    `blocked_storage_id` bigint NOT NULL COMMENT ''被阻挡储位ID'',
    `revision` int NOT NULL DEFAULT 1 COMMENT ''阻挡边修订号'',
    `create_dept` bigint NULL COMMENT ''创建部门'',
    `create_by` bigint NULL COMMENT ''创建者'',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',
    `update_by` bigint NULL COMMENT ''更新者'',
    `update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',
    PRIMARY KEY (`access_edge_id`),
    UNIQUE KEY `uk_wms_storage_access_edge_pair` (`blocker_storage_id`, `blocked_storage_id`),
    KEY `idx_wms_storage_access_edge_blocked` (`blocked_storage_id`),
    CONSTRAINT `ck_wms_storage_access_edge_no_self` CHECK (`blocker_storage_id` <> `blocked_storage_id`),
    CONSTRAINT `ck_wms_storage_access_edge_revision` CHECK (`revision` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS通道储位阻挡边''', 'DO 0');
PREPARE space_stmt FROM @ddl;
EXECUTE space_stmt;
DEALLOCATE PREPARE space_stmt;

SET @ddl = IF(@preflight_ok = 1 AND NOT EXISTS (
    SELECT 1 FROM tmp_iws_space_actual_tables
    WHERE CAST(table_name AS BINARY) = CAST('wms_location_version' AS BINARY)
), 'CREATE TABLE `wms_location_version` (
    `location_version_id` bigint NOT NULL COMMENT ''位置版本主键'',
    `object_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''空间对象类型'',
    `object_id` bigint NOT NULL COMMENT ''空间对象内部ID'',
    `version_no` int NOT NULL COMMENT ''对象位置版本号'',
    `parent_location_version_id` bigint NULL COMMENT ''父对象位置版本ID'',
    `config_json` json NOT NULL COMMENT ''位置不可变配置'',
    `canonical_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''规范化配置SHA-256小写十六进制'',
    `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT ''DRAFT'' COMMENT ''状态：DRAFT/ACTIVE/RETIRED'',
    `create_dept` bigint NULL COMMENT ''创建部门'',
    `create_by` bigint NULL COMMENT ''创建者'',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',
    `update_by` bigint NULL COMMENT ''更新者'',
    `update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',
    PRIMARY KEY (`location_version_id`),
    UNIQUE KEY `uk_wms_location_version_object_version` (`object_type`, `object_id`, `version_no`),
    KEY `idx_wms_location_version_parent` (`parent_location_version_id`),
    KEY `idx_wms_location_version_status` (`status`, `object_type`),
    CONSTRAINT `ck_wms_location_version_digest` CHECK (REGEXP_LIKE(`canonical_digest`, _utf8mb4''^[0-9a-f]{64}$'', ''c'')),
    CONSTRAINT `ck_wms_location_version_status` CHECK (`status` IN (''DRAFT'',''ACTIVE'',''RETIRED'')),
    CONSTRAINT `ck_wms_location_version_version_no` CHECK (`version_no` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS空间位置配置版本''', 'DO 0');
PREPARE space_stmt FROM @ddl;
EXECUTE space_stmt;
DEALLOCATE PREPARE space_stmt;

SET @ddl = IF(@preflight_ok = 1 AND NOT EXISTS (
    SELECT 1 FROM tmp_iws_space_actual_tables
    WHERE CAST(table_name AS BINARY) = CAST('wms_location_effective' AS BINARY)
), 'CREATE TABLE `wms_location_effective` (
    `location_effective_id` bigint NOT NULL COMMENT ''位置生效指针主键'',
    `object_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''空间对象类型'',
    `object_id` bigint NOT NULL COMMENT ''空间对象内部ID'',
    `location_version_id` bigint NOT NULL COMMENT ''当前位置版本ID'',
    `revision` int NOT NULL DEFAULT 1 COMMENT ''生效指针CAS修订号'',
    `create_dept` bigint NULL COMMENT ''创建部门'',
    `create_by` bigint NULL COMMENT ''创建者'',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',
    `update_by` bigint NULL COMMENT ''更新者'',
    `update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',
    PRIMARY KEY (`location_effective_id`),
    UNIQUE KEY `uk_wms_location_effective_object` (`object_type`, `object_id`),
    KEY `idx_wms_location_effective_version` (`location_version_id`),
    CONSTRAINT `ck_wms_location_effective_revision` CHECK (`revision` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS空间位置当前生效版本''', 'DO 0');
PREPARE space_stmt FROM @ddl;
EXECUTE space_stmt;
DEALLOCATE PREPARE space_stmt;

SET @ddl = IF(@preflight_ok = 1 AND NOT EXISTS (
    SELECT 1 FROM tmp_iws_space_actual_tables
    WHERE CAST(table_name AS BINARY) = CAST('wms_object_visibility' AS BINARY)
), 'CREATE TABLE `wms_object_visibility` (
    `visibility_key` bigint NOT NULL COMMENT ''对象可见范围主键'',
    `parent_visibility_key` bigint NULL COMMENT ''当前物理父对象可见范围主键'',
    `object_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''空间对象类型'',
    `object_id` bigint NOT NULL COMMENT ''空间对象内部ID'',
    `mode` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''可见范围模式：INHERIT/VALUE/CLEAR'',
    `revision` int NOT NULL DEFAULT 1 COMMENT ''可见范围修订号'',
    `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''可见范围更新时间'',
    `create_dept` bigint NULL COMMENT ''创建部门'',
    `create_by` bigint NULL COMMENT ''创建者'',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',
    `update_by` bigint NULL COMMENT ''更新者'',
    `update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',
    PRIMARY KEY (`visibility_key`),
    UNIQUE KEY `uk_wms_object_visibility_object` (`object_type`, `object_id`),
    KEY `idx_wms_object_visibility_parent` (`parent_visibility_key`),
    CONSTRAINT `ck_wms_object_visibility_company_mode` CHECK (`object_type` <> ''COMPANY'' OR `mode` = ''VALUE''),
    CONSTRAINT `ck_wms_object_visibility_company_parent` CHECK (`object_type` <> ''COMPANY'' OR `parent_visibility_key` IS NULL),
    CONSTRAINT `ck_wms_object_visibility_mode` CHECK (`mode` IN (''INHERIT'',''VALUE'',''CLEAR'')),
    CONSTRAINT `ck_wms_object_visibility_revision` CHECK (`revision` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS对象可见范围三态头''', 'DO 0');
PREPARE space_stmt FROM @ddl;
EXECUTE space_stmt;
DEALLOCATE PREPARE space_stmt;

SET @ddl = IF(@preflight_ok = 1 AND NOT EXISTS (
    SELECT 1 FROM tmp_iws_space_actual_tables
    WHERE CAST(table_name AS BINARY) = CAST('wms_object_visibility_department' AS BINARY)
), 'CREATE TABLE `wms_object_visibility_department` (
    `visibility_department_id` bigint NOT NULL COMMENT ''可见部门关系主键'',
    `visibility_key` bigint NOT NULL COMMENT ''对象可见范围主键'',
    `dept_id` bigint NOT NULL COMMENT ''部门ID，逻辑关联 sys_dept.dept_id'',
    `create_dept` bigint NULL COMMENT ''创建部门'',
    `create_by` bigint NULL COMMENT ''创建者'',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',
    `update_by` bigint NULL COMMENT ''更新者'',
    `update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',
    PRIMARY KEY (`visibility_department_id`),
    UNIQUE KEY `uk_wms_object_visibility_department` (`visibility_key`, `dept_id`),
    KEY `idx_wms_object_visibility_department_dept` (`dept_id`, `visibility_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS对象可见部门集合''', 'DO 0');
PREPARE space_stmt FROM @ddl;
EXECUTE space_stmt;
DEALLOCATE PREPARE space_stmt;

SET @ddl = IF(@preflight_ok = 1 AND NOT EXISTS (
    SELECT 1 FROM tmp_iws_space_actual_tables
    WHERE CAST(table_name AS BINARY) = CAST('wms_storage_capacity' AS BINARY)
), 'CREATE TABLE `wms_storage_capacity` (
    `capacity_id` bigint NOT NULL COMMENT ''储位容量主键，由服务分配'',
    `storage_id` bigint NOT NULL COMMENT ''稳定储位ID，逻辑关联 wms_storage.storage_id'',
    `dimension_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''容量维度键'',
    `capacity_value` decimal(38,12) NOT NULL COMMENT ''容量值'',
    `unit` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''容量单位'',
    `scale` int NOT NULL COMMENT ''容量小数位'',
    `capacity_version` int NOT NULL COMMENT ''容量版本号'',
    `active_flag` tinyint(1) NOT NULL DEFAULT 1 COMMENT ''活动版本标志：1活动，0历史'',
    `active_capacity_key` varchar(531) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (
      IF(`active_flag` = 1,
         CONCAT(''A:'', LPAD(HEX(`storage_id`), 16, ''0''), '':'', HEX(CAST(`dimension_key` AS BINARY))),
         CONCAT(''H:'', LPAD(HEX(`capacity_id`), 16, ''0'')))
    ) STORED NOT NULL COMMENT ''活动或历史容量无碰撞唯一键'',
    `create_dept` bigint NULL COMMENT ''创建部门'',
    `create_by` bigint NULL COMMENT ''创建者'',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',
    `update_by` bigint NULL COMMENT ''更新者'',
    `update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',
    PRIMARY KEY (`capacity_id`),
    UNIQUE KEY `uk_wms_storage_capacity_active` (`active_capacity_key`),
    UNIQUE KEY `uk_wms_storage_capacity_version` (`storage_id`, `dimension_key`, `capacity_version`),
    KEY `idx_wms_storage_capacity_dimension` (`storage_id`, `dimension_key`, `active_flag`, `capacity_version`),
    CONSTRAINT `ck_wms_storage_capacity_active_flag` CHECK (`active_flag` IN (0,1)),
    CONSTRAINT `ck_wms_storage_capacity_scale_consistent` CHECK (`capacity_value` = TRUNCATE(`capacity_value`, `scale`)),
    CONSTRAINT `ck_wms_storage_capacity_scale` CHECK (`scale` BETWEEN 0 AND 12),
    CONSTRAINT `ck_wms_storage_capacity_value` CHECK (`capacity_value` >= 0),
    CONSTRAINT `ck_wms_storage_capacity_version` CHECK (`capacity_version` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS储位容量''', 'DO 0');
PREPARE space_stmt FROM @ddl;
EXECUTE space_stmt;
DEALLOCATE PREPARE space_stmt;

-- ----------------------------------------------------------------------------
-- DDL 后完整元数据验收：重新读取全部行并做双向精确集合比较
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_actual_checks;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_actual_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_actual_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_actual_tables;

CREATE TEMPORARY TABLE tmp_iws_space_actual_tables AS
SELECT TABLE_NAME AS table_name, TABLE_TYPE AS table_type, ENGINE AS engine,
       TABLE_COLLATION AS table_collation, CREATE_OPTIONS AS create_options,
       TABLE_COMMENT AS table_comment
FROM information_schema.TABLES
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;
CREATE TEMPORARY TABLE tmp_iws_space_actual_columns AS
SELECT TABLE_NAME AS table_name, ORDINAL_POSITION AS ordinal_position,
       COLUMN_NAME AS column_name, COLUMN_TYPE AS column_type,
       IS_NULLABLE AS is_nullable, COLUMN_DEFAULT AS column_default,
       EXTRA AS extra, CHARACTER_SET_NAME AS character_set_name,
       COLLATION_NAME AS collation_name, GENERATION_EXPRESSION AS generation_expression,
       COLUMN_COMMENT AS column_comment
FROM information_schema.COLUMNS
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;
CREATE TEMPORARY TABLE tmp_iws_space_actual_indexes AS
SELECT TABLE_NAME AS table_name, INDEX_NAME AS index_name,
       NON_UNIQUE AS non_unique, SEQ_IN_INDEX AS seq_in_index,
       COLUMN_NAME AS column_name, SUB_PART AS sub_part, PACKED AS packed,
       NULLABLE AS nullable, INDEX_TYPE AS index_type, COLLATION AS index_collation,
       EXPRESSION AS index_expression, IS_VISIBLE AS is_visible,
       INDEX_COMMENT AS index_comment
FROM information_schema.STATISTICS
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;
CREATE TEMPORARY TABLE tmp_iws_space_actual_checks AS
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
    SELECT COUNT(*) FROM tmp_iws_space_actual_tables a
    LEFT JOIN tmp_iws_space_expected_tables e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE e.table_name IS NULL
       OR CAST(a.table_type AS BINARY) <> CAST(e.table_type AS BINARY)
       OR NOT (CAST(a.engine AS BINARY) <=> CAST(e.engine AS BINARY))
       OR NOT (CAST(a.table_collation AS BINARY) <=> CAST(e.table_collation AS BINARY))
       OR CAST(a.create_options AS BINARY) <> CAST(e.create_options AS BINARY)
       OR CAST(a.table_comment AS BINARY) <> CAST(e.table_comment AS BINARY)
);
SET @post_table_expected_missing = (
    SELECT COUNT(*) FROM tmp_iws_space_expected_tables e
    LEFT JOIN tmp_iws_space_actual_tables a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE a.table_name IS NULL
);
SET @post_column_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_space_actual_columns a
    LEFT JOIN tmp_iws_space_expected_columns e
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
SET @post_column_expected_missing = (
    SELECT COUNT(*) FROM tmp_iws_space_expected_columns e
    LEFT JOIN tmp_iws_space_actual_columns a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE a.column_name IS NULL
);
SET @post_index_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_space_actual_indexes a
    LEFT JOIN tmp_iws_space_expected_indexes e
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
SET @post_index_expected_missing = (
    SELECT COUNT(*) FROM tmp_iws_space_expected_indexes e
    LEFT JOIN tmp_iws_space_actual_indexes a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE a.index_name IS NULL
);
SET @post_check_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_space_actual_checks a
    LEFT JOIN tmp_iws_space_expected_checks e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE e.constraint_name IS NULL
       OR NOT (CAST(a.check_clause AS BINARY) <=> CAST(e.check_clause AS BINARY))
       OR NOT (CAST(a.enforced AS BINARY) <=> CAST(e.enforced AS BINARY))
);
SET @post_check_expected_missing = (
    SELECT COUNT(*) FROM tmp_iws_space_expected_checks e
    LEFT JOIN tmp_iws_space_actual_checks a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE a.constraint_name IS NULL
);
SET @post_base_table_count = (
    SELECT COUNT(*) FROM tmp_iws_space_actual_tables WHERE table_type = 'BASE TABLE'
);
SET @post_object_conflict_count = (
    SELECT COUNT(*) FROM tmp_iws_space_actual_tables WHERE table_type <> 'BASE TABLE'
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
      AND FIND_IN_SET(
          CAST(REFERENCED_TABLE_NAME AS BINARY),
          CAST(@target_table_names AS BINARY)
      ) > 0
);
SET @expected_table_rows = (SELECT COUNT(*) FROM tmp_iws_space_expected_tables);
SET @actual_table_rows = (SELECT COUNT(*) FROM tmp_iws_space_actual_tables);
SET @expected_column_rows = (SELECT COUNT(*) FROM tmp_iws_space_expected_columns);
SET @actual_column_rows = (SELECT COUNT(*) FROM tmp_iws_space_actual_columns);
SET @expected_index_rows = (SELECT COUNT(*) FROM tmp_iws_space_expected_indexes);
SET @actual_index_rows = (SELECT COUNT(*) FROM tmp_iws_space_actual_indexes);
SET @expected_check_rows = (SELECT COUNT(*) FROM tmp_iws_space_expected_checks);
SET @actual_check_rows = (SELECT COUNT(*) FROM tmp_iws_space_actual_checks);

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
    AND @post_object_conflict_count = 0
    AND @post_own_fk_count = 0 AND @post_trigger_count = 0
    AND @post_inbound_fk_count = 0,
    1, 0
);

SELECT
    @foundation_release_id AS `发布标识`,
    @existing_table_count AS `执行前目标表数`,
    @post_base_table_count AS `执行后目标表数`,
    CONCAT(@actual_table_rows, '/', @expected_table_rows) AS `表元数据行`,
    CONCAT(@actual_column_rows, '/', @expected_column_rows) AS `列元数据行`,
    CONCAT(@actual_index_rows, '/', @expected_index_rows) AS `索引元数据行`,
    CONCAT(@actual_check_rows, '/', @expected_check_rows) AS `CHECK元数据行`,
    @post_table_actual_extra + @post_table_expected_missing AS `表元数据差异`,
    @post_column_actual_extra + @post_column_expected_missing AS `列元数据差异`,
    @post_index_actual_extra + @post_index_expected_missing AS `索引元数据差异`,
    @post_check_actual_extra + @post_check_expected_missing AS `CHECK元数据差异`,
    @post_own_fk_count AS `本表外键数`,
    @post_trigger_count AS `触发器数`,
    @post_inbound_fk_count AS `外部指入外键数`,
    IF(@post_structure_ok = 1,
       '空间 foundation DDL 与结构验收通过；不回填、不切换、不写 seed、不宣称 P2 完成',
       '空间 foundation DDL 后结构验收失败') AS `执行结果`;
SELECT JSON_EXTRACT(IF(@post_structure_ok = 1, '{}', JSON_QUOTE('invalid')), '$') AS `空间foundation完成门禁`;
SELECT IF(@post_structure_ok = 1, 1, JSON_EXTRACT(JSON_QUOTE('invalid'), '$')) AS `空间foundation完成退出码`;
SET @structure_fail_fast = IF(@post_structure_ok = 1, 0, CAST('invalid' AS JSON));
SELECT IF(@post_structure_ok = 1, 1, @structure_fail_fast) AS `空间foundation完成硬门禁`;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_actual_checks;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_actual_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_actual_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_actual_tables;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_expected_checks;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_expected_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_expected_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_space_expected_tables;
SET @post_cleanup_ok = IF(@post_structure_ok = 1, 1, @structure_fail_fast);
SELECT @post_cleanup_ok AS `空间foundation收尾门禁`;
