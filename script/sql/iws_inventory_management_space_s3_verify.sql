-- ============================================================================
-- IWS+ 在库空间 SQL S3 只读验收（INV-P2-03-S3）
-- ============================================================================
-- 目标数据库：MySQL 8.0；已在 MySQL 8.0.45 隔离库验证
-- 验收范围：
--   1. wms_warehouse、wms_warehouse_zone 与 wms_storage 的完整表选项、列行集合、
--      索引行集合与 CHECK 行集合，必须与同版升级脚本冻结的目标结构精确一致；
--      表 3 行、列 74 行、索引 43 行、CHECK 5 行。
--   2. wms_storage.warehouse_id 已收紧为 NOT NULL，并与 zone_id 父链解析的仓库
--      一致；父链缺失、成环、跨 warehouse_code、无编码、无匹配仓库或多公司歧义
--      均为失败。
--   3. 五个地址列满足 NULL 或非空且与 ASCII TRIM 结果逐字节相等；同仓五项地址
--      （NULL 按相等参与）无重复，五项全空同仓至多一条；生成标志与比较值与存储
--      值一致。
--   4. 唯一键 uk_wms_storage_warehouse_address 恰为 11 列、非部分索引、全部列
--      非空、可见、BTREE、无前缀，按 utf8mb4 核算 1293 字节且不超过 3072 字节。
--   5. 受影响表不存在未声明触发器、本表外键或外部指入外键，也不存在同名 VIEW。
-- 成功含义：
--   只代表 S3 结构与数据合同成立。S4 全量验收（数量守恒、应用读写门禁、真实
--   cutover）与 S2 写冻结执行均未由本脚本完成，不得据此宣称 S4 或 cutover 完成。
-- 执行：
--   mysql --no-defaults --protocol=TCP --host=<主机> --port=<端口> --user=<账号> \
--     --default-character-set=utf8mb4 --database=<目标库> --batch --raw \
--     < script/sql/iws_inventory_management_space_s3_verify.sql
--   只读验收；禁止 --force；任一门禁失败以非零退出终止。本脚本只创建会话临时表，
--   不写业务表、不执行 DDL、不连接其他库。
-- 权限：
--   离线部署/验收账号须直接持有全局 SELECT、TRIGGER，且 partial_revokes=OFF
--   （离线元数据合同，不是应用账号）；另需目标库 SELECT、CREATE TEMPORARY TABLES。
-- ============================================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 集中参数（与同版升级脚本一致）
-- ----------------------------------------------------------------------------
SET @target_schema = 'innolux-iws';
SET @s3_release_id = 'INV-P2-03-S3-20261028';
SET @s3_prerequisite_release_id = 'INV-P2-01-S1-20261027';
SET @s3_contract_version = 'SPACE-3T-S3-20261028';
SET @expected_mysql_version = '8.0.45';
SET @expected_table_count = 3;
SET @expected_column_count = 74;
SET @expected_index_row_count = 43;
SET @expected_check_count = 5;
SET @s3_target_fingerprint = '059136f7ff9fb346a35a8ffda729cb2401f648d23c6a7af4f65431341e1f762a';
SET @uk_index_name = 'uk_wms_storage_warehouse_address';
SET @uk_index_size_limit = 3072;

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
SELECT IF(@metadata_visibility_ok = 1, '通过',
    '失败：离线部署验收账号须直接拥有全局 SELECT、TRIGGER，且 partial_revokes=OFF；schema/table/角色授权不受支持')
    AS '元数据可见权限门禁';
SELECT JSON_EXTRACT(IF(@metadata_visibility_ok = 1, '{}', '权限不足'), '$')
    AS '元数据权限硬门禁';

SET @saved_group_concat_max_len = @@SESSION.group_concat_max_len;
SET SESSION group_concat_max_len = 1048576;
SET SESSION cte_max_recursion_depth = 10000;

SET @schema_ok = (
    DATABASE() IS NOT NULL
    AND CAST(DATABASE() AS BINARY) = CAST(@target_schema AS BINARY)
);
SET @version_ok = (
    CAST(VERSION() AS BINARY) = CAST(@expected_mysql_version AS BINARY)
    AND VERSION() NOT LIKE '%MariaDB%'
);

-- ----------------------------------------------------------------------------
-- 一、冻结预期元数据（与升级脚本同版、同行数）
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_expected_tables;
CREATE TEMPORARY TABLE tmp_iws_s3_expected_tables (
    table_name       varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    table_type       varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    engine           varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    table_collation  varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    create_options   varchar(255)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    table_comment    varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_expected_columns;
CREATE TEMPORARY TABLE tmp_iws_s3_expected_columns (
    table_name            varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ordinal_position      int           NOT NULL,
    ordinal_check         tinyint       NOT NULL,
    column_name           varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    column_type           varchar(128)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    is_nullable           varchar(3)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    column_default        longtext      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    extra                 varchar(128)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    character_set_name    varchar(32)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    collation_name        varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    generation_expression longtext      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    column_comment        varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name, column_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_expected_indexes;
CREATE TEMPORARY TABLE tmp_iws_s3_expected_indexes (
    table_name       varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    index_name       varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    non_unique       int           NOT NULL,
    seq_in_index     int           NOT NULL,
    column_name      varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    sub_part         bigint        NULL,
    packed           varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    nullable         varchar(3)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    index_type       varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    index_collation  varchar(3)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    index_expression longtext      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    is_visible       varchar(3)    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    index_comment    varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name, index_name, seq_in_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_expected_checks;
CREATE TEMPORARY TABLE tmp_iws_s3_expected_checks (
    table_name      varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    constraint_name varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    check_clause    longtext     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    enforced        varchar(3)   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name, constraint_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_iws_s3_expected_tables
    (table_name, table_type, engine, table_collation, create_options, table_comment)
VALUES
('wms_storage','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS储位表'),
('wms_warehouse','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS仓库主表'),
('wms_warehouse_zone','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS库区表');

INSERT INTO tmp_iws_s3_expected_columns
    (table_name, ordinal_position, ordinal_check, column_name, column_type, is_nullable, column_default,
     extra, character_set_name, collation_name, generation_expression, column_comment)
VALUES
('wms_storage',1,1,'storage_id','bigint','NO',NULL,'',NULL,NULL,'','储位主键，系统生成'),
('wms_storage',2,1,'storage_code','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','储位编码，业务唯一'),
('wms_storage',3,1,'zone_id','bigint','NO',NULL,'',NULL,NULL,'','所属库区主键，逻辑关联 wms_warehouse_zone.zone_id'),
('wms_storage',4,1,'storage_type','tinyint','NO','1','',NULL,NULL,'','储位类型：1普通储位，2亮灯货架位，3播种墙位，4端口位'),
('wms_storage',5,1,'height_type','tinyint','YES',NULL,'',NULL,NULL,'','高度类型，用于容器高度匹配'),
('wms_storage',6,1,'height_desc','varchar(50)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','高度描述'),
('wms_storage',7,1,'row_no','int','YES',NULL,'',NULL,NULL,'','排/行号'),
('wms_storage',8,1,'col_no','int','YES',NULL,'',NULL,NULL,'','列号'),
('wms_storage',9,1,'level_no','int','YES',NULL,'',NULL,NULL,'','层号'),
('wms_storage',10,1,'x_coordinate','decimal(12,3)','YES',NULL,'',NULL,NULL,'','X坐标，用于WCS/地图展示'),
('wms_storage',11,1,'y_coordinate','decimal(12,3)','YES',NULL,'',NULL,NULL,'','Y坐标，用于WCS/地图展示'),
('wms_storage',12,1,'port_code','varchar(64)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','端口编码，端口位或设备交互位使用'),
('wms_storage',13,1,'wcs_point_code','varchar(64)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','WCS点位编码'),
('wms_storage',14,1,'status','tinyint','NO','0','',NULL,NULL,'','储位状态：0空闲，1占用，2预占，3维护'),
('wms_storage',15,1,'lock_status','tinyint','NO','0','',NULL,NULL,'','锁定状态：0未锁定，1已锁定'),
('wms_storage',16,1,'disable_status','tinyint','NO','0','',NULL,NULL,'','禁用状态：0启用，1禁用'),
('wms_storage',17,1,'version','int','NO','0','',NULL,NULL,'','乐观锁版本号'),
('wms_storage',18,1,'del_flag','char(1)','NO','0','','utf8mb4','utf8mb4_0900_ai_ci','','删除标志：0存在，1删除'),
('wms_storage',19,1,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_storage',20,1,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_storage',21,1,'create_time','datetime','NO','CURRENT_TIMESTAMP','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_storage',22,1,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_storage',23,1,'update_time','datetime','YES',NULL,'on update CURRENT_TIMESTAMP',NULL,NULL,'','更新时间'),
('wms_storage',24,1,'remark','varchar(500)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','备注'),
('wms_storage',25,1,'warehouse_id','bigint','NO',NULL,'',NULL,NULL,'','所属仓库主键，逻辑关联 wms_warehouse.warehouse_id；S3回填后非空，须与库区父链所属仓库一致'),
('wms_storage',26,1,'aisle_text','varchar(64)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','巷道文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL'),
('wms_storage',27,1,'row_text','varchar(64)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','排文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL'),
('wms_storage',28,1,'level_text','varchar(64)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','层文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL'),
('wms_storage',29,1,'column_text','varchar(64)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','列文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL'),
('wms_storage',30,1,'depth_text','varchar(64)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','深文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL'),
('wms_storage',31,0,'aisle_text_is_null','tinyint','NO',NULL,'STORED GENERATED',NULL,NULL,'(`aisle_text` is null)','巷道地址空值标志；NULL按相等参与同仓地址唯一键'),
('wms_storage',32,0,'row_text_is_null','tinyint','NO',NULL,'STORED GENERATED',NULL,NULL,'(`row_text` is null)','排地址空值标志；NULL按相等参与同仓地址唯一键'),
('wms_storage',33,0,'level_text_is_null','tinyint','NO',NULL,'STORED GENERATED',NULL,NULL,'(`level_text` is null)','层地址空值标志；NULL按相等参与同仓地址唯一键'),
('wms_storage',34,0,'column_text_is_null','tinyint','NO',NULL,'STORED GENERATED',NULL,NULL,'(`column_text` is null)','列地址空值标志；NULL按相等参与同仓地址唯一键'),
('wms_storage',35,0,'depth_text_is_null','tinyint','NO',NULL,'STORED GENERATED',NULL,NULL,'(`depth_text` is null)','深地址空值标志；NULL按相等参与同仓地址唯一键'),
('wms_storage',36,0,'aisle_text_cmp','varchar(64)','NO',NULL,'STORED GENERATED','utf8mb4','utf8mb4_0900_bin','ifnull(`aisle_text`,_utf8mb4\\\'\\\')','巷道地址精确比较值；NULL统一为空串且使用二进制排序规则'),
('wms_storage',37,0,'row_text_cmp','varchar(64)','NO',NULL,'STORED GENERATED','utf8mb4','utf8mb4_0900_bin','ifnull(`row_text`,_utf8mb4\\\'\\\')','排地址精确比较值；NULL统一为空串且使用二进制排序规则'),
('wms_storage',38,0,'level_text_cmp','varchar(64)','NO',NULL,'STORED GENERATED','utf8mb4','utf8mb4_0900_bin','ifnull(`level_text`,_utf8mb4\\\'\\\')','层地址精确比较值；NULL统一为空串且使用二进制排序规则'),
('wms_storage',39,0,'column_text_cmp','varchar(64)','NO',NULL,'STORED GENERATED','utf8mb4','utf8mb4_0900_bin','ifnull(`column_text`,_utf8mb4\\\'\\\')','列地址精确比较值；NULL统一为空串且使用二进制排序规则'),
('wms_storage',40,0,'depth_text_cmp','varchar(64)','NO',NULL,'STORED GENERATED','utf8mb4','utf8mb4_0900_bin','ifnull(`depth_text`,_utf8mb4\\\'\\\')','深地址精确比较值；NULL统一为空串且使用二进制排序规则'),
('wms_warehouse',1,1,'warehouse_id','bigint','NO',NULL,'',NULL,NULL,'','仓库主键，系统生成'),
('wms_warehouse',2,1,'company_id','bigint','NO',NULL,'',NULL,NULL,'','所属公司主键，逻辑关联 wms_company.company_id'),
('wms_warehouse',3,1,'warehouse_code','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_bin','','仓库编码，同公司内唯一且使用精确比较'),
('wms_warehouse',4,1,'warehouse_name','varchar(100)','NO',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','仓库名称，创建后可按受控业务修改'),
('wms_warehouse',5,1,'warehouse_type_code','varchar(32)','NO',NULL,'','utf8mb4','utf8mb4_bin','','仓库类型编码，如 GENERAL/FLOOR；楼层只存于类型配置'),
('wms_warehouse',6,1,'type_config_version_id','bigint','YES',NULL,'',NULL,NULL,'','仓库类型配置版本主键；配置版本表后续建立'),
('wms_warehouse',7,1,'lifecycle_status','varchar(16)','NO','ACTIVE','','utf8mb4','utf8mb4_bin','','生命周期：ACTIVE/RETIRING/RETIRED'),
('wms_warehouse',8,1,'current_location_version_id','bigint','YES',NULL,'',NULL,NULL,'','当前空间版本主键；位置版本表后续建立'),
('wms_warehouse',9,1,'revision','int','NO','1','',NULL,NULL,'','仓库乐观锁版本，创建为1'),
('wms_warehouse',10,1,'remark','varchar(500)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','备注'),
('wms_warehouse',11,1,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_warehouse',12,1,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_warehouse',13,1,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_warehouse',14,1,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_warehouse',15,1,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_warehouse_zone',1,1,'zone_id','bigint','NO',NULL,'',NULL,NULL,'','库区主键，系统生成'),
('wms_warehouse_zone',2,1,'zone_code','varchar(50)','NO',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','库区编码，业务唯一'),
('wms_warehouse_zone',3,1,'zone_name','varchar(100)','NO',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','库区名称'),
('wms_warehouse_zone',4,1,'zone_type','tinyint','NO','1','',NULL,NULL,'','库区类型：1普通库区，2混料区，3品质区，4退料区，5播种墙，6亮灯货架区，7虚拟区'),
('wms_warehouse_zone',5,1,'plant_code','varchar(50)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','厂别编码，跨厂区部署时使用'),
('wms_warehouse_zone',6,1,'warehouse_code','varchar(50)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','仓库编码，对应现场仓别或仓库类型'),
('wms_warehouse_zone',7,1,'parent_zone_id','bigint','YES',NULL,'',NULL,NULL,'','上级库区主键，逻辑关联 wms_warehouse_zone.zone_id'),
('wms_warehouse_zone',8,1,'allow_inbound','tinyint','NO','1','',NULL,NULL,'','是否允许入库：0否，1是'),
('wms_warehouse_zone',9,1,'allow_outbound','tinyint','NO','1','',NULL,NULL,'','是否允许出库：0否，1是'),
('wms_warehouse_zone',10,1,'status','tinyint','NO','1','',NULL,NULL,'','状态：0停用，1启用'),
('wms_warehouse_zone',11,1,'sort_no','int','NO','0','',NULL,NULL,'','显示顺序'),
('wms_warehouse_zone',12,1,'del_flag','char(1)','NO','0','','utf8mb4','utf8mb4_0900_ai_ci','','删除标志：0存在，1删除'),
('wms_warehouse_zone',13,1,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_warehouse_zone',14,1,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_warehouse_zone',15,1,'create_time','datetime','NO','CURRENT_TIMESTAMP','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_warehouse_zone',16,1,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_warehouse_zone',17,1,'update_time','datetime','YES',NULL,'on update CURRENT_TIMESTAMP',NULL,NULL,'','更新时间'),
('wms_warehouse_zone',18,1,'remark','varchar(500)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','备注'),
('wms_warehouse_zone',19,1,'warehouse_id','bigint','YES',NULL,'',NULL,NULL,'','所属仓库主键，逻辑关联 wms_warehouse.warehouse_id；S1暂可空，S3回填后收紧');

INSERT INTO tmp_iws_s3_expected_indexes
    (table_name, index_name, non_unique, seq_in_index, column_name, sub_part, packed,
     nullable, index_type, index_collation, index_expression, is_visible, index_comment)
VALUES
('wms_storage','idx_wms_storage_matrix',1,1,'zone_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage','idx_wms_storage_matrix',1,2,'row_no',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_storage','idx_wms_storage_matrix',1,3,'col_no',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_storage','idx_wms_storage_matrix',1,4,'level_no',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_storage','idx_wms_storage_port',1,1,'port_code',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_storage','idx_wms_storage_port',1,2,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage','idx_wms_storage_warehouse_status',1,1,'warehouse_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage','idx_wms_storage_warehouse_status',1,2,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage','idx_wms_storage_wcs_point',1,1,'wcs_point_code',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_storage','idx_wms_storage_zone_status',1,1,'zone_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage','idx_wms_storage_zone_status',1,2,'storage_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage','idx_wms_storage_zone_status',1,3,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage','idx_wms_storage_zone_status',1,4,'lock_status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage','idx_wms_storage_zone_status',1,5,'disable_status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage','PRIMARY',0,1,'storage_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage','uk_wms_storage_code',0,1,'storage_code',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage','uk_wms_storage_warehouse_address',0,1,'warehouse_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage','uk_wms_storage_warehouse_address',0,2,'aisle_text_is_null',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage','uk_wms_storage_warehouse_address',0,3,'row_text_is_null',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage','uk_wms_storage_warehouse_address',0,4,'level_text_is_null',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage','uk_wms_storage_warehouse_address',0,5,'column_text_is_null',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage','uk_wms_storage_warehouse_address',0,6,'depth_text_is_null',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage','uk_wms_storage_warehouse_address',0,7,'aisle_text_cmp',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage','uk_wms_storage_warehouse_address',0,8,'row_text_cmp',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage','uk_wms_storage_warehouse_address',0,9,'level_text_cmp',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage','uk_wms_storage_warehouse_address',0,10,'column_text_cmp',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_storage','uk_wms_storage_warehouse_address',0,11,'depth_text_cmp',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_warehouse','idx_wms_warehouse_company_status',1,1,'company_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_warehouse','idx_wms_warehouse_company_status',1,2,'lifecycle_status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_warehouse','idx_wms_warehouse_company_status',1,3,'warehouse_type_code',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_warehouse','PRIMARY',0,1,'warehouse_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_warehouse','uk_wms_warehouse_company_code',0,1,'company_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_warehouse','uk_wms_warehouse_company_code',0,2,'warehouse_code',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_warehouse_zone','idx_wms_zone_parent',1,1,'parent_zone_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_warehouse_zone','idx_wms_zone_plant_wh_status',1,1,'plant_code',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_warehouse_zone','idx_wms_zone_plant_wh_status',1,2,'warehouse_code',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_warehouse_zone','idx_wms_zone_plant_wh_status',1,3,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_warehouse_zone','idx_wms_zone_type_status',1,1,'zone_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_warehouse_zone','idx_wms_zone_type_status',1,2,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_warehouse_zone','idx_wms_zone_warehouse_status',1,1,'warehouse_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_warehouse_zone','idx_wms_zone_warehouse_status',1,2,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_warehouse_zone','PRIMARY',0,1,'zone_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_warehouse_zone','uk_wms_zone_code',0,1,'zone_code',NULL,NULL,'','BTREE','A',NULL,'YES','');

INSERT INTO tmp_iws_s3_expected_checks
    (table_name, constraint_name, check_clause, enforced)
VALUES
('wms_storage','ck_wms_storage_aisle_text_trim','((`aisle_text` is null) or ((`aisle_text` <> _utf8mb4\\\'\\\') and (cast(`aisle_text` as char charset binary) = cast(trim(`aisle_text`) as char charset binary))))','YES'),
('wms_storage','ck_wms_storage_column_text_trim','((`column_text` is null) or ((`column_text` <> _utf8mb4\\\'\\\') and (cast(`column_text` as char charset binary) = cast(trim(`column_text`) as char charset binary))))','YES'),
('wms_storage','ck_wms_storage_depth_text_trim','((`depth_text` is null) or ((`depth_text` <> _utf8mb4\\\'\\\') and (cast(`depth_text` as char charset binary) = cast(trim(`depth_text`) as char charset binary))))','YES'),
('wms_storage','ck_wms_storage_level_text_trim','((`level_text` is null) or ((`level_text` <> _utf8mb4\\\'\\\') and (cast(`level_text` as char charset binary) = cast(trim(`level_text`) as char charset binary))))','YES'),
('wms_storage','ck_wms_storage_row_text_trim','((`row_text` is null) or ((`row_text` <> _utf8mb4\\\'\\\') and (cast(`row_text` as char charset binary) = cast(trim(`row_text`) as char charset binary))))','YES');

-- ----------------------------------------------------------------------------
-- 二、实际元数据与结构差异
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3v_actual_tables;
CREATE TEMPORARY TABLE tmp_iws_s3v_actual_tables AS
SELECT TABLE_NAME AS table_name, TABLE_TYPE AS table_type, ENGINE AS engine,
       TABLE_COLLATION AS table_collation, CREATE_OPTIONS AS create_options,
       TABLE_COMMENT AS table_comment
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3v_actual_columns;
CREATE TEMPORARY TABLE tmp_iws_s3v_actual_columns AS
SELECT TABLE_NAME AS table_name, ORDINAL_POSITION AS ordinal_position,
       COLUMN_NAME AS column_name, COLUMN_TYPE AS column_type,
       IS_NULLABLE AS is_nullable, COLUMN_DEFAULT AS column_default,
       EXTRA AS extra, CHARACTER_SET_NAME AS character_set_name,
       COLLATION_NAME AS collation_name, GENERATION_EXPRESSION AS generation_expression,
       COLUMN_COMMENT AS column_comment
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3v_actual_indexes;
CREATE TEMPORARY TABLE tmp_iws_s3v_actual_indexes AS
SELECT TABLE_NAME AS table_name, INDEX_NAME AS index_name,
       NON_UNIQUE AS non_unique, SEQ_IN_INDEX AS seq_in_index,
       COLUMN_NAME AS column_name, SUB_PART AS sub_part, PACKED AS packed,
       NULLABLE AS nullable, INDEX_TYPE AS index_type,
       COLLATION AS index_collation, EXPRESSION AS index_expression,
       IS_VISIBLE AS is_visible, INDEX_COMMENT AS index_comment
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3v_actual_checks;
CREATE TEMPORARY TABLE tmp_iws_s3v_actual_checks AS
SELECT tc.TABLE_NAME AS table_name, tc.CONSTRAINT_NAME AS constraint_name,
       cc.CHECK_CLAUSE AS check_clause, tc.ENFORCED AS enforced
FROM information_schema.TABLE_CONSTRAINTS tc
JOIN information_schema.CHECK_CONSTRAINTS cc
  ON CAST(cc.CONSTRAINT_SCHEMA AS BINARY) = CAST(tc.CONSTRAINT_SCHEMA AS BINARY)
 AND CAST(cc.CONSTRAINT_NAME AS BINARY) = CAST(tc.CONSTRAINT_NAME AS BINARY)
WHERE tc.CONSTRAINT_SCHEMA = DATABASE()
  AND tc.CONSTRAINT_TYPE = 'CHECK'
  AND tc.TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage');

SET @actual_table_row_count = (SELECT COUNT(*) FROM tmp_iws_s3v_actual_tables);
SET @actual_column_row_count = (SELECT COUNT(*) FROM tmp_iws_s3v_actual_columns);
SET @actual_index_row_count = (SELECT COUNT(*) FROM tmp_iws_s3v_actual_indexes);
SET @actual_check_row_count = (SELECT COUNT(*) FROM tmp_iws_s3v_actual_checks);
SET @non_base_table_count = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
      AND TABLE_TYPE <> 'BASE TABLE'
);

SET @table_missing_count = (
    SELECT COUNT(*) FROM tmp_iws_s3_expected_tables e
    LEFT JOIN tmp_iws_s3v_actual_tables a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE a.table_name IS NULL
);
SET @table_mismatch_count = (
    SELECT COUNT(*) FROM tmp_iws_s3v_actual_tables a
    JOIN tmp_iws_s3_expected_tables e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE CAST(a.table_type AS BINARY) <> CAST(e.table_type AS BINARY)
       OR NOT (CAST(a.engine AS BINARY) <=> CAST(e.engine AS BINARY))
       OR NOT (CAST(a.table_collation AS BINARY) <=> CAST(e.table_collation AS BINARY))
       OR CAST(a.create_options AS BINARY) <> CAST(e.create_options AS BINARY)
       OR CAST(a.table_comment AS BINARY) <> CAST(e.table_comment AS BINARY)
);
SET @table_extra_count = (
    SELECT COUNT(*) FROM tmp_iws_s3v_actual_tables a
    LEFT JOIN tmp_iws_s3_expected_tables e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE e.table_name IS NULL
);

SET @column_missing_count = (
    SELECT COUNT(*) FROM tmp_iws_s3_expected_columns e
    LEFT JOIN tmp_iws_s3v_actual_columns a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE a.column_name IS NULL
);
SET @column_extra_count = (
    SELECT COUNT(*) FROM tmp_iws_s3v_actual_columns a
    LEFT JOIN tmp_iws_s3_expected_columns e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE e.column_name IS NULL
);
SET @column_mismatch_count = (
    SELECT COUNT(*) FROM tmp_iws_s3v_actual_columns a
    JOIN tmp_iws_s3_expected_columns e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE (e.ordinal_check = 1 AND a.ordinal_position <> e.ordinal_position)
       OR CAST(a.column_type AS BINARY) <> CAST(e.column_type AS BINARY)
       OR CAST(a.is_nullable AS BINARY) <> CAST(e.is_nullable AS BINARY)
       OR NOT (CAST(a.column_default AS BINARY) <=> CAST(e.column_default AS BINARY))
       OR CAST(a.extra AS BINARY) <> CAST(e.extra AS BINARY)
       OR NOT (CAST(a.character_set_name AS BINARY) <=> CAST(e.character_set_name AS BINARY))
       OR NOT (CAST(a.collation_name AS BINARY) <=> CAST(e.collation_name AS BINARY))
       OR CAST(a.generation_expression AS BINARY) <> CAST(e.generation_expression AS BINARY)
       OR CAST(a.column_comment AS BINARY) <> CAST(e.column_comment AS BINARY)
);

SET @index_missing_count = (
    SELECT COUNT(*) FROM tmp_iws_s3_expected_indexes e
    LEFT JOIN tmp_iws_s3v_actual_indexes a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE a.index_name IS NULL
);
SET @index_extra_count = (
    SELECT COUNT(*) FROM tmp_iws_s3v_actual_indexes a
    LEFT JOIN tmp_iws_s3_expected_indexes e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE e.index_name IS NULL
);
SET @index_mismatch_count = (
    SELECT COUNT(*) FROM tmp_iws_s3v_actual_indexes a
    JOIN tmp_iws_s3_expected_indexes e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE a.non_unique <> e.non_unique
       OR NOT (CAST(a.column_name AS BINARY) <=> CAST(e.column_name AS BINARY))
       OR NOT (a.sub_part <=> e.sub_part)
       OR NOT (CAST(a.packed AS BINARY) <=> CAST(e.packed AS BINARY))
       OR CAST(a.index_type AS BINARY) <> CAST(e.index_type AS BINARY)
       OR NOT (CAST(a.index_collation AS BINARY) <=> CAST(e.index_collation AS BINARY))
       OR NOT (CAST(a.index_expression AS BINARY) <=> CAST(e.index_expression AS BINARY))
       OR CAST(a.is_visible AS BINARY) <> CAST(e.is_visible AS BINARY)
       OR CAST(a.index_comment AS BINARY) <> CAST(e.index_comment AS BINARY)
       OR (NOT (a.table_name = 'wms_storage' AND a.column_name = 'warehouse_id')
           AND NOT (CAST(a.nullable AS BINARY) <=> CAST(e.nullable AS BINARY)))
);

SET @check_missing_count = (
    SELECT COUNT(*) FROM tmp_iws_s3_expected_checks e
    LEFT JOIN tmp_iws_s3v_actual_checks a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE a.constraint_name IS NULL
);
SET @check_extra_count = (
    SELECT COUNT(*) FROM tmp_iws_s3v_actual_checks a
    LEFT JOIN tmp_iws_s3_expected_checks e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE e.constraint_name IS NULL
);
SET @check_mismatch_count = (
    SELECT COUNT(*) FROM tmp_iws_s3v_actual_checks a
    JOIN tmp_iws_s3_expected_checks e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE CAST(a.check_clause AS BINARY) <> CAST(e.check_clause AS BINARY)
       OR CAST(a.enforced AS BINARY) <> CAST(e.enforced AS BINARY)
);

SET @structure_diff = @non_base_table_count
    + @table_missing_count + @table_mismatch_count + @table_extra_count
    + @column_missing_count + @column_extra_count + @column_mismatch_count
    + @index_missing_count + @index_extra_count + @index_mismatch_count
    + @check_missing_count + @check_extra_count + @check_mismatch_count;

SET @computed_fingerprint = (
    SELECT SHA2(CONCAT_WS('#',
        (SELECT IFNULL(GROUP_CONCAT(CONCAT_WS('|', table_name, table_type, engine,
                    table_collation, create_options, table_comment)
                ORDER BY table_name SEPARATOR '~'), '')
           FROM tmp_iws_s3_expected_tables),
        (SELECT IFNULL(GROUP_CONCAT(CONCAT_WS('|', table_name, ordinal_position,
                    ordinal_check, column_name, column_type, is_nullable,
                    IFNULL(column_default, '<NULL>'), extra,
                    IFNULL(character_set_name, '<NULL>'),
                    IFNULL(collation_name, '<NULL>'),
                    generation_expression, column_comment)
                ORDER BY table_name, column_name SEPARATOR '~'), '')
           FROM tmp_iws_s3_expected_columns),
        (SELECT IFNULL(GROUP_CONCAT(CONCAT_WS('|', table_name, index_name, non_unique,
                    seq_in_index, IFNULL(column_name, '<NULL>'),
                    IFNULL(sub_part, '<NULL>'), IFNULL(packed, '<NULL>'),
                    IFNULL(nullable, '<NULL>'), index_type,
                    IFNULL(index_collation, '<NULL>'),
                    IFNULL(index_expression, '<NULL>'), is_visible, index_comment)
                ORDER BY table_name, index_name, seq_in_index SEPARATOR '~'), '')
           FROM tmp_iws_s3_expected_indexes),
        (SELECT IFNULL(GROUP_CONCAT(CONCAT_WS('|', table_name, constraint_name,
                    check_clause, enforced)
                ORDER BY table_name, constraint_name SEPARATOR '~'), '')
           FROM tmp_iws_s3_expected_checks)
    ), 256)
);
SET @fingerprint_ok = (
    @computed_fingerprint IS NOT NULL
    AND CAST(@computed_fingerprint AS BINARY) = CAST(@s3_target_fingerprint AS BINARY)
);
SET @structure_ok = IF(
    @metadata_visibility_ok = 1 AND @schema_ok = 1 AND @version_ok = 1
    AND @fingerprint_ok = 1 AND @structure_diff = 0,
    1, 0
);

SELECT
    @s3_release_id AS '发布标识',
    @s3_prerequisite_release_id AS 'S1前置发布',
    @s3_contract_version AS '合同版本',
    @target_schema AS '目标库参数',
    DATABASE() AS '当前库',
    VERSION() AS 'MySQL版本',
    IF(@metadata_visibility_ok = 1, '通过', '权限不足') AS '元数据权限',
    IF(@fingerprint_ok = 1, '匹配', '不匹配') AS '目标结构指纹',
    @s3_target_fingerprint AS '声明指纹',
    @computed_fingerprint AS '计算指纹';
SELECT
    @expected_table_count AS '预期表行数', @actual_table_row_count AS '实际表行数',
    @expected_column_count AS '预期列行数', @actual_column_row_count AS '实际列行数',
    @expected_index_row_count AS '预期索引行数', @actual_index_row_count AS '实际索引行数',
    @expected_check_count AS '预期CHECK行数', @actual_check_row_count AS '实际CHECK行数';
SELECT
    @structure_diff AS '结构差异合计',
    @table_missing_count + @table_mismatch_count + @table_extra_count AS '表差异',
    @column_missing_count + @column_extra_count + @column_mismatch_count AS '列差异',
    @index_missing_count + @index_extra_count + @index_mismatch_count AS '索引差异',
    @check_missing_count + @check_extra_count + @check_mismatch_count AS 'CHECK差异',
    @non_base_table_count AS '同名非表对象数',
    IF(@structure_ok = 1, '结构验收通过', '结构验收失败：禁止数据验收') AS '结构结果';
SELECT JSON_EXTRACT(IF(@structure_ok = 1, '{}', 'invalid'), '$') AS 'S3结构验收门禁';

-- ----------------------------------------------------------------------------
-- 三、父链、仓库一致性与地址数据验收
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3v_zone_chain;
CREATE TEMPORARY TABLE tmp_iws_s3v_zone_chain AS
WITH RECURSIVE walk (origin_zone_id, current_zone_id, parent_zone_id,
                     warehouse_code, path_text, depth, is_cycle, is_missing_parent) AS (
    SELECT z.zone_id, z.zone_id, z.parent_zone_id, z.warehouse_code,
           CAST(CONCAT(',', z.zone_id, ',') AS CHAR(4000)), 1, 0,
           IF(z.parent_zone_id IS NOT NULL AND p.zone_id IS NULL, 1, 0)
    FROM wms_warehouse_zone z
    LEFT JOIN wms_warehouse_zone p ON p.zone_id = z.parent_zone_id
    UNION ALL
    SELECT w.origin_zone_id, p.zone_id, p.parent_zone_id, p.warehouse_code,
           CONCAT(w.path_text, p.zone_id, ','), w.depth + 1,
           IF(p.parent_zone_id IS NOT NULL
              AND LOCATE(CONCAT(',', p.parent_zone_id, ','), w.path_text) > 0, 1, 0),
           IF(p.parent_zone_id IS NOT NULL AND pp.zone_id IS NULL, 1, 0)
    FROM walk w
    JOIN wms_warehouse_zone p ON p.zone_id = w.parent_zone_id
    LEFT JOIN wms_warehouse_zone pp ON pp.zone_id = p.parent_zone_id
    WHERE w.is_cycle = 0 AND w.is_missing_parent = 0
      AND CHAR_LENGTH(w.path_text) < 3900
)
SELECT * FROM walk;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3v_zone_resolution;
CREATE TEMPORARY TABLE tmp_iws_s3v_zone_resolution AS
SELECT origin_zone_id,
       MAX(is_missing_parent) AS has_missing_parent,
       MAX(is_cycle) AS has_cycle,
       MAX(CASE WHEN is_cycle = 0 AND is_missing_parent = 0
                 AND CHAR_LENGTH(path_text) >= 3900 AND parent_zone_id IS NOT NULL
                THEN 1 ELSE 0 END) AS is_truncated,
       COUNT(DISTINCT CASE WHEN warehouse_code IS NOT NULL
                           THEN CAST(warehouse_code AS BINARY) END) AS code_count,
       MAX(warehouse_code) AS code_value
FROM tmp_iws_s3v_zone_chain
GROUP BY origin_zone_id;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3v_wh_code;
CREATE TEMPORARY TABLE tmp_iws_s3v_wh_code AS
SELECT CAST(warehouse_code AS BINARY) AS code_bin,
       COUNT(*) AS warehouse_count,
       MIN(warehouse_id) AS warehouse_id
FROM wms_warehouse
GROUP BY CAST(warehouse_code AS BINARY);

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3v_zone_status;
CREATE TEMPORARY TABLE tmp_iws_s3v_zone_status AS
SELECT r.origin_zone_id AS zone_id,
       CASE
           WHEN r.has_missing_parent = 1 THEN 'MISSING_PARENT'
           WHEN r.has_cycle = 1 THEN 'CYCLE'
           WHEN r.is_truncated = 1 THEN 'TRUNCATED'
           WHEN r.code_count = 0 THEN 'NO_CODE'
           WHEN r.code_count > 1 THEN 'MULTI_CODE'
           WHEN w.warehouse_count IS NULL OR w.warehouse_count = 0 THEN 'NO_WAREHOUSE'
           WHEN w.warehouse_count > 1 THEN 'AMBIGUOUS_WAREHOUSE'
           ELSE 'OK'
       END AS resolution_status,
       r.code_count, r.code_value, w.warehouse_count, w.warehouse_id
FROM tmp_iws_s3v_zone_resolution r
LEFT JOIN tmp_iws_s3v_wh_code w
  ON r.code_count = 1
 AND CAST(r.code_value AS BINARY) = w.code_bin;

SET @zone_blocker_count = (SELECT COUNT(*) FROM tmp_iws_s3v_zone_status WHERE resolution_status <> 'OK');
SET @storage_orphan_zone_count = (
    SELECT COUNT(*) FROM wms_storage s
    LEFT JOIN wms_warehouse_zone z ON z.zone_id = s.zone_id
    WHERE z.zone_id IS NULL
);
SET @storage_zone_unresolved_count = (
    SELECT COUNT(*) FROM wms_storage s
    JOIN tmp_iws_s3v_zone_status zs ON zs.zone_id = s.zone_id
    WHERE zs.resolution_status <> 'OK'
);
SET @storage_null_warehouse_count = (
    SELECT COUNT(*) FROM wms_storage WHERE warehouse_id IS NULL
);
SET @storage_warehouse_mismatch_count = (
    SELECT COUNT(*) FROM wms_storage s
    JOIN tmp_iws_s3v_zone_status zs ON zs.zone_id = s.zone_id
    WHERE zs.resolution_status = 'OK'
      AND (s.warehouse_id IS NULL OR s.warehouse_id <> zs.warehouse_id)
);
SET @zone_warehouse_value_count = (
    SELECT COUNT(*) FROM wms_warehouse_zone WHERE warehouse_id IS NOT NULL
);

SET @address_trim_violation_row_count = (
    SELECT COUNT(*) FROM (
        SELECT storage_id FROM wms_storage
        WHERE aisle_text IS NOT NULL
          AND (aisle_text = '' OR CAST(aisle_text AS BINARY) <> CAST(TRIM(aisle_text) AS BINARY))
        UNION ALL
        SELECT storage_id FROM wms_storage
        WHERE row_text IS NOT NULL
          AND (row_text = '' OR CAST(row_text AS BINARY) <> CAST(TRIM(row_text) AS BINARY))
        UNION ALL
        SELECT storage_id FROM wms_storage
        WHERE level_text IS NOT NULL
          AND (level_text = '' OR CAST(level_text AS BINARY) <> CAST(TRIM(level_text) AS BINARY))
        UNION ALL
        SELECT storage_id FROM wms_storage
        WHERE column_text IS NOT NULL
          AND (column_text = '' OR CAST(column_text AS BINARY) <> CAST(TRIM(column_text) AS BINARY))
        UNION ALL
        SELECT storage_id FROM wms_storage
        WHERE depth_text IS NOT NULL
          AND (depth_text = '' OR CAST(depth_text AS BINARY) <> CAST(TRIM(depth_text) AS BINARY))
    ) v
);
SET @address_dup_group_count = (
    SELECT COUNT(*) FROM (
        SELECT warehouse_id, aisle_text, row_text, level_text, column_text, depth_text
        FROM wms_storage
        WHERE warehouse_id IS NOT NULL
        GROUP BY warehouse_id, aisle_text, row_text, level_text, column_text, depth_text
        HAVING COUNT(*) > 1
    ) d
);
SET @address_allnull_group_count = (
    SELECT COUNT(*) FROM (
        SELECT warehouse_id
        FROM wms_storage
        WHERE warehouse_id IS NOT NULL AND aisle_text IS NULL AND row_text IS NULL
          AND level_text IS NULL AND column_text IS NULL AND depth_text IS NULL
        GROUP BY warehouse_id
        HAVING COUNT(*) > 1
    ) d
);
SET @generated_mismatch_count = (
    SELECT COUNT(*)
    FROM wms_storage
    WHERE warehouse_id IS NOT NULL
      AND (
          aisle_text_is_null <> IF(aisle_text IS NULL, 1, 0)
       OR row_text_is_null <> IF(row_text IS NULL, 1, 0)
       OR level_text_is_null <> IF(level_text IS NULL, 1, 0)
       OR column_text_is_null <> IF(column_text IS NULL, 1, 0)
       OR depth_text_is_null <> IF(depth_text IS NULL, 1, 0)
       OR CAST(aisle_text_cmp AS BINARY) <> CAST(IFNULL(aisle_text, '') AS BINARY)
       OR CAST(row_text_cmp AS BINARY) <> CAST(IFNULL(row_text, '') AS BINARY)
       OR CAST(level_text_cmp AS BINARY) <> CAST(IFNULL(level_text, '') AS BINARY)
       OR CAST(column_text_cmp AS BINARY) <> CAST(IFNULL(column_text, '') AS BINARY)
       OR CAST(depth_text_cmp AS BINARY) <> CAST(IFNULL(depth_text, '') AS BINARY)
      )
);

SET @uk_part_count = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_storage'
      AND INDEX_NAME = @uk_index_name
);
SET @uk_not_null_part_count = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS s
    JOIN information_schema.COLUMNS c
      ON c.TABLE_SCHEMA = s.TABLE_SCHEMA AND c.TABLE_NAME = s.TABLE_NAME
     AND c.COLUMN_NAME = s.COLUMN_NAME
    WHERE s.TABLE_SCHEMA = DATABASE() AND s.TABLE_NAME = 'wms_storage'
      AND s.INDEX_NAME = @uk_index_name
      AND c.IS_NULLABLE = 'NO'
);
SET @uk_invalid_part_count = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS s
    WHERE s.TABLE_SCHEMA = DATABASE() AND s.TABLE_NAME = 'wms_storage'
      AND s.INDEX_NAME = @uk_index_name
      AND (s.NON_UNIQUE <> 0 OR s.SUB_PART IS NOT NULL
           OR s.INDEX_TYPE <> 'BTREE' OR s.IS_VISIBLE <> 'YES')
);
SET @uk_index_bytes = (
    SELECT SUM(CASE
                   WHEN c.DATA_TYPE = 'bigint' THEN 8
                   WHEN c.DATA_TYPE = 'tinyint' THEN 1
                   WHEN c.CHARACTER_OCTET_LENGTH IS NOT NULL THEN c.CHARACTER_OCTET_LENGTH
                   ELSE 0
               END)
    FROM information_schema.STATISTICS s
    JOIN information_schema.COLUMNS c
      ON c.TABLE_SCHEMA = s.TABLE_SCHEMA AND c.TABLE_NAME = s.TABLE_NAME
     AND c.COLUMN_NAME = s.COLUMN_NAME
    WHERE s.TABLE_SCHEMA = DATABASE() AND s.TABLE_NAME = 'wms_storage'
      AND s.INDEX_NAME = @uk_index_name
);
SET @uk_ok = IF(
    @uk_part_count = 11
    AND @uk_not_null_part_count = 11
    AND @uk_invalid_part_count = 0
    AND @uk_index_bytes = 1293
    AND @uk_index_bytes <= @uk_index_size_limit,
    1, 0
);

SET @trigger_count = (
    SELECT COUNT(*) FROM information_schema.TRIGGERS
    WHERE TRIGGER_SCHEMA = DATABASE()
      AND EVENT_OBJECT_TABLE IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
);
SET @own_fk_count = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
      AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @inbound_fk_count = (
    SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
    WHERE UNIQUE_CONSTRAINT_SCHEMA = DATABASE()
      AND REFERENCED_TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
);
SET @unknown_object_count = @trigger_count + @own_fk_count + @inbound_fk_count;

SET @data_ok = IF(
    @structure_ok = 1
    AND @zone_blocker_count = 0
    AND @storage_orphan_zone_count = 0
    AND @storage_zone_unresolved_count = 0
    AND @storage_null_warehouse_count = 0
    AND @storage_warehouse_mismatch_count = 0
    AND @address_trim_violation_row_count = 0
    AND @address_dup_group_count = 0
    AND @address_allnull_group_count = 0
    AND @generated_mismatch_count = 0
    AND @uk_ok = 1
    AND @unknown_object_count = 0,
    1, 0
);

SELECT
    @zone_blocker_count AS '库区父链阻碍数',
    @storage_orphan_zone_count AS '储位孤立库区数',
    @storage_zone_unresolved_count AS '储位库区父链不可解析数',
    @storage_null_warehouse_count AS '仓库ID空值数',
    @storage_warehouse_mismatch_count AS '仓库与父链不一致数',
    @zone_warehouse_value_count AS '库区warehouse_id现状值数';
SELECT
    @address_trim_violation_row_count AS '地址规范违规行数',
    @address_dup_group_count AS '同仓地址重复组数',
    @address_allnull_group_count AS '五项全空重复组数',
    @generated_mismatch_count AS '生成键与存储值不一致数';
SELECT
    @uk_part_count AS '唯一键列数',
    @uk_not_null_part_count AS '唯一键非空列数',
    @uk_invalid_part_count AS '唯一键非法列数',
    @uk_index_bytes AS '唯一键字节数',
    @uk_index_size_limit AS 'InnoDB单索引字节上限',
    IF(@uk_ok = 1, '结构满足', '结构不满足') AS '唯一键核算结果';
SELECT
    @trigger_count AS '受影响表触发器数',
    @own_fk_count AS '受影响表外键数',
    @inbound_fk_count AS '外部指入外键数',
    @unknown_object_count AS '未声明对象合计';
SELECT
    @s3_release_id AS '发布标识',
    @s3_contract_version AS '合同版本',
    IF(@structure_ok = 1, '通过', '失败') AS '结构验收',
    IF(@data_ok = 1, '通过', '失败') AS '数据验收',
    IF(@data_ok = 1,
       'S3只读验收通过；S4全量验收与cutover未实现',
       CASE
           WHEN @structure_ok = 0 THEN '失败：结构验收未通过'
           WHEN @zone_blocker_count > 0 THEN '失败：库区父链仍存在阻碍'
           WHEN @storage_orphan_zone_count + @storage_zone_unresolved_count > 0 THEN '失败：储位仓库来源不可解析'
           WHEN @storage_null_warehouse_count > 0 THEN '失败：仍有仓库ID空值'
           WHEN @storage_warehouse_mismatch_count > 0 THEN '失败：仓库与父链不一致'
           WHEN @address_trim_violation_row_count > 0 THEN '失败：地址规范化违规'
           WHEN @address_dup_group_count > 0 THEN '失败：同仓地址重复'
           WHEN @generated_mismatch_count > 0 THEN '失败：生成键与存储值不一致'
           WHEN @uk_ok = 0 THEN '失败：唯一键结构或字节核算不满足'
           WHEN @unknown_object_count > 0 THEN '失败：存在未声明触发器或外键'
           ELSE '失败：数据验收未通过'
       END) AS '执行结果';
SELECT JSON_EXTRACT(IF(@data_ok = 1, '{}', 'invalid'), '$') AS 'S3只读验收门禁';

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3v_zone_status;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3v_wh_code;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3v_zone_resolution;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3v_zone_chain;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3v_actual_checks;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3v_actual_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3v_actual_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3v_actual_tables;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_expected_checks;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_expected_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_expected_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_expected_tables;

SET SESSION group_concat_max_len = @saved_group_concat_max_len;
