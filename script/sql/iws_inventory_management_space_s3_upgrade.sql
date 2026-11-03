-- ============================================================================
-- IWS+ 在库空间 SQL S3 旧行回填与地址唯一键收紧升级（INV-P2-03-S3）
-- ============================================================================
-- 目标数据库：MySQL 8.0；已在 MySQL 8.0.45 隔离库验证
-- 目标对象（仅 wms_storage 与既有 S1 目标结构）：
--   1. 旧行回填：wms_storage.warehouse_id 从 wms_warehouse_zone 父链与
--      wms_warehouse.warehouse_code 唯一解析结果回填；row_text/level_text/
--      column_text 从旧 row_no/level_no/col_no 按规范十进制文本回填（不补零），
--      aisle_text/depth_text 无旧来源时保持 NULL；不修改旧数值列。
--   2. 五个地址列各自 CHECK：value IS NULL OR (value <> '' AND
--      CAST(value AS BINARY) = CAST(TRIM(value) AS BINARY))，即字节级 ASCII
--      TRIM 检查。
--   3. 十个 STORED NOT NULL 生成列：五个 TINYINT 空值标志
--      (col IS NULL)、五个 VARCHAR(64) utf8mb4_0900_bin 比较值
--      (IFNULL(col,''))。
--   4. 同仓五项地址唯一键 uk_wms_storage_warehouse_address：
--      (warehouse_id, 五个标志, 五个比较值)；含 ACTIVE/RETIRING/RETIRED、
--      停用与逻辑删除等全部生命周期行，不做部分索引，退役储位继续占组合。
--   5. 回填与数据预检通过后收紧 wms_storage.warehouse_id 为 NOT NULL，并保证
--      每行 warehouse_id 与 zone_id 父链所属仓库一致（A45 的结构侧前提）。
-- 本切片边界：
--   不实现 S2 写冻结令牌，不写 wms_upgrade_run/checkpoint/issue 或任何升级台账；
--   不创建公司/仓库/库区映射，不为缺少来源的库区或储位伪造仓库、地址或编码；
--   不回填 wms_warehouse_zone.warehouse_id（只核对结构并报告现状值数）；
--   不修改旧 row_no/col_no/level_no、库区 warehouse_code、储位编码或生命周期；
--   不自动重编号、补零、合并、删除或按 storage_id 保留冲突行；
--   不创建 wms_house 类型配置、空间版本表或其他 S4 对象，不做 cutover 和应用门禁。
--   S4 全量验收（数量守恒、应用读写门禁、真实 dev cutover）未实现，本脚本只到
--   S3 结构收紧与只读验收合同；不得据此宣称 S4 完成。
-- 前置：
--   S1 发布（INV-P2-01-S1）已把仓库表、库区/储位 S1 列与索引建成目标可空结构；
--   wms_warehouse 已存在且同一 warehouse_code 在 wms_warehouse 中只能匹配一行
--   （多公司同码视为歧义阻碍，不猜公司）；S2 写冻结须由部署流程在此之前生效，
--   本脚本不检测、不设置、不代替写冻结。
-- 幂等与冲突：
--   每次执行先读取三张受影响表的完整列/索引/CHECK/表选项行集合，与脚本内冻结
--   预期逐行比较。完全缺失的 S3 对象按 ADD 补齐，完全匹配按 EXISTS 跳过；
--   错误生成表达式、错误字符集/排序规则、缺失或额外列、索引、CHECK、同名 VIEW、
--   未声明触发器或外键、错误仓库列状态、目标结构指纹不符，均在首个业务写或
--   DDL 前硬失败。PARTIAL（删除生成列/索引/CHECK 之一）重跑补齐；不 ALTER 覆盖、
--   不 DROP 既有对象、不删表重建。已提交的 DDL 在 checkpoint 缺失时由下一次
--   元数据比较识别，不重复 ALTER。
--   多语句 DDL 与回填不承诺整体事务回滚；首个 SQL 错误必须停止并保留现场。
-- 执行：
--   mysql --no-defaults --protocol=TCP --host=<主机> --port=<端口> --user=<账号> \
--     --default-character-set=utf8mb4 --database=<目标库> --batch --raw \
--     < script/sql/iws_inventory_management_space_s3_upgrade.sql
--   禁止 --force；必须同一连接串行执行。MySQL 8.0.45 客户端通过 stdin 在首个
--   SQL 错误处终止并返回非 0。任意 GUI 或其他自行续错的执行器不在支持范围。
-- 权限：
--   离线部署/验收账号须直接持有全局 SELECT、TRIGGER，且 partial_revokes=OFF
--   （离线元数据合同，不是应用账号）；运行本脚本另需目标库 SELECT、UPDATE、
--   ALTER、INDEX、CREATE TEMPORARY TABLES。schema/table/角色授权不替代全局合同。
--   本脚本不创建存储过程、触发器、事件或外键。
-- 索引字节核算：
--   BIGINT warehouse_id 8 字节 + 5 个 TINYINT 标志 5 字节 + 5 个
--   VARCHAR(64) utf8mb4 比较值（64 × 4 = 256 字节/列）1280 字节 = 1293 字节，
--   低于 InnoDB 单索引 3072 字节上限；11 列也低于单索引 16 列上限。
--   若调整列长、字符集或键组成须重新核算并在脚本内阻断。
-- 参数：
--   目标库、发布标识、合同版本、目标 MySQL 版本、预期元数据行数和目标结构
--   指纹集中定义于下一节；执行前只改 @target_schema。其他参数必须与批准的
--   同版合同一致。
-- ============================================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 集中参数
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
SET @s3_wh_comment = '所属仓库主键，逻辑关联 wms_warehouse.warehouse_id；S3回填后非空，须与库区父链所属仓库一致';
SET @s1_wh_comment = '所属仓库主键，逻辑关联 wms_warehouse.warehouse_id；S1暂可空，S3回填后收紧';

-- 离线部署/验收账号契约（不是应用账号）：必须直接持有全局 SELECT、TRIGGER，
-- 且 partial_revokes=OFF，才能证明跨库入向外键和目标表触发器元数据完整可见。
-- schema/table grants、角色（包括已激活角色）不替代本契约；脚本不激活角色、
-- 不读取 mysql 授权表，不输出凭据，不向其他 schema 写入。
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
-- 父链遍历最多 3900 字符；预算递归深度以避免 1 位 ID 的深链触发默认 1000 限制。
SET SESSION cte_max_recursion_depth = 10000;

-- ----------------------------------------------------------------------------
-- 一、目标库与版本前置
-- ----------------------------------------------------------------------------
SET @schema_ok = (
    DATABASE() IS NOT NULL
    AND CAST(DATABASE() AS BINARY) = CAST(@target_schema AS BINARY)
);
SET @version_ok = (
    CAST(VERSION() AS BINARY) = CAST(@expected_mysql_version AS BINARY)
    AND VERSION() NOT LIKE '%MariaDB%'
);

-- ----------------------------------------------------------------------------
-- 二、冻结预期元数据（完整行集合；不使用 GROUP_CONCAT 代替比较）
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

-- 以下完整预期行由 MySQL 8.0.45 的 INFORMATION_SCHEMA 生成并冻结；
-- 生成列/CHECK 表达式保留服务器存储形式（内部以反斜杠转义单引号）。
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
-- 三、实际元数据与对象状态
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_actual_tables;
CREATE TEMPORARY TABLE tmp_iws_s3_actual_tables AS
SELECT TABLE_NAME AS table_name, TABLE_TYPE AS table_type, ENGINE AS engine,
       TABLE_COLLATION AS table_collation, CREATE_OPTIONS AS create_options,
       TABLE_COMMENT AS table_comment
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_actual_columns;
CREATE TEMPORARY TABLE tmp_iws_s3_actual_columns AS
SELECT TABLE_NAME AS table_name, ORDINAL_POSITION AS ordinal_position,
       COLUMN_NAME AS column_name, COLUMN_TYPE AS column_type,
       IS_NULLABLE AS is_nullable, COLUMN_DEFAULT AS column_default,
       EXTRA AS extra, CHARACTER_SET_NAME AS character_set_name,
       COLLATION_NAME AS collation_name, GENERATION_EXPRESSION AS generation_expression,
       COLUMN_COMMENT AS column_comment
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_actual_indexes;
CREATE TEMPORARY TABLE tmp_iws_s3_actual_indexes AS
SELECT TABLE_NAME AS table_name, INDEX_NAME AS index_name,
       NON_UNIQUE AS non_unique, SEQ_IN_INDEX AS seq_in_index,
       COLUMN_NAME AS column_name, SUB_PART AS sub_part, PACKED AS packed,
       NULLABLE AS nullable, INDEX_TYPE AS index_type,
       COLLATION AS index_collation, EXPRESSION AS index_expression,
       IS_VISIBLE AS is_visible, INDEX_COMMENT AS index_comment
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_actual_checks;
CREATE TEMPORARY TABLE tmp_iws_s3_actual_checks AS
SELECT tc.TABLE_NAME AS table_name, tc.CONSTRAINT_NAME AS constraint_name,
       cc.CHECK_CLAUSE AS check_clause, tc.ENFORCED AS enforced
FROM information_schema.TABLE_CONSTRAINTS tc
JOIN information_schema.CHECK_CONSTRAINTS cc
  ON CAST(cc.CONSTRAINT_SCHEMA AS BINARY) = CAST(tc.CONSTRAINT_SCHEMA AS BINARY)
 AND CAST(cc.CONSTRAINT_NAME AS BINARY) = CAST(tc.CONSTRAINT_NAME AS BINARY)
WHERE tc.CONSTRAINT_SCHEMA = DATABASE()
  AND tc.CONSTRAINT_TYPE = 'CHECK'
  AND tc.TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage');

-- 三张表必须是 BASE TABLE；同名 VIEW 或其他对象直接判为结构冲突。
SET @non_base_table_count = (
    SELECT COUNT(*)
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
      AND TABLE_TYPE <> 'BASE TABLE'
);

SET @table_missing_count = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_expected_tables e
    LEFT JOIN tmp_iws_s3_actual_tables a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE a.table_name IS NULL
);
SET @table_mismatch_count = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_actual_tables a
    JOIN tmp_iws_s3_expected_tables e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE CAST(a.table_type AS BINARY) <> CAST(e.table_type AS BINARY)
       OR NOT (CAST(a.engine AS BINARY) <=> CAST(e.engine AS BINARY))
       OR NOT (CAST(a.table_collation AS BINARY) <=> CAST(e.table_collation AS BINARY))
       OR CAST(a.create_options AS BINARY) <> CAST(e.create_options AS BINARY)
       OR CAST(a.table_comment AS BINARY) <> CAST(e.table_comment AS BINARY)
);
SET @table_extra_count = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_actual_tables a
    LEFT JOIN tmp_iws_s3_expected_tables e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE e.table_name IS NULL
);

SET @column_missing_base_count = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_expected_columns e
    LEFT JOIN tmp_iws_s3_actual_columns a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE e.ordinal_check = 1 AND a.column_name IS NULL
);
SET @column_missing_gen_count = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_expected_columns e
    LEFT JOIN tmp_iws_s3_actual_columns a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE e.ordinal_check = 0 AND a.column_name IS NULL
);
SET @column_extra_count = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_actual_columns a
    LEFT JOIN tmp_iws_s3_expected_columns e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE e.column_name IS NULL
);
SET @column_base_mismatch_count = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_actual_columns a
    JOIN tmp_iws_s3_expected_columns e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE e.ordinal_check = 1
      AND NOT (a.table_name = 'wms_storage' AND a.column_name = 'warehouse_id')
      AND (a.ordinal_position <> e.ordinal_position
           OR CAST(a.column_type AS BINARY) <> CAST(e.column_type AS BINARY)
           OR CAST(a.is_nullable AS BINARY) <> CAST(e.is_nullable AS BINARY)
           OR NOT (CAST(a.column_default AS BINARY) <=> CAST(e.column_default AS BINARY))
           OR CAST(a.extra AS BINARY) <> CAST(e.extra AS BINARY)
           OR NOT (CAST(a.character_set_name AS BINARY) <=> CAST(e.character_set_name AS BINARY))
           OR NOT (CAST(a.collation_name AS BINARY) <=> CAST(e.collation_name AS BINARY))
           OR CAST(a.generation_expression AS BINARY) <> CAST(e.generation_expression AS BINARY)
           OR CAST(a.column_comment AS BINARY) <> CAST(e.column_comment AS BINARY))
);
-- 生成列的物理顺序不作为合同：PARTIAL 补齐会追加到表尾。其余元数据必须完整一致。
SET @column_gen_mismatch_count = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_actual_columns a
    JOIN tmp_iws_s3_expected_columns e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE e.ordinal_check = 0
      AND (CAST(a.column_type AS BINARY) <> CAST(e.column_type AS BINARY)
           OR CAST(a.is_nullable AS BINARY) <> CAST(e.is_nullable AS BINARY)
           OR NOT (CAST(a.column_default AS BINARY) <=> CAST(e.column_default AS BINARY))
           OR CAST(a.extra AS BINARY) <> CAST(e.extra AS BINARY)
           OR NOT (CAST(a.character_set_name AS BINARY) <=> CAST(e.character_set_name AS BINARY))
           OR NOT (CAST(a.collation_name AS BINARY) <=> CAST(e.collation_name AS BINARY))
           OR CAST(a.generation_expression AS BINARY) <> CAST(e.generation_expression AS BINARY)
           OR CAST(a.column_comment AS BINARY) <> CAST(e.column_comment AS BINARY))
);

-- wms_storage.warehouse_id 只接受两种合同状态：S1 可空旧注释、S3 收紧新注释。
SET @storage_wh_action = (
    SELECT CASE
        WHEN COUNT(*) <> 1 THEN 'CONFLICT'
        WHEN MAX(ordinal_position <> 25) = 1 THEN 'CONFLICT'
        WHEN MAX(CAST(LOWER(column_type) AS BINARY) <> CAST('bigint' AS BINARY)) = 1 THEN 'CONFLICT'
        WHEN MAX(CAST(extra AS BINARY) <> CAST('' AS BINARY)) = 1 THEN 'CONFLICT'
        WHEN MAX(character_set_name IS NOT NULL) = 1 THEN 'CONFLICT'
        WHEN MAX(collation_name IS NOT NULL) = 1 THEN 'CONFLICT'
        WHEN MAX(CAST(generation_expression AS BINARY) <> CAST('' AS BINARY)) = 1 THEN 'CONFLICT'
        WHEN MAX(is_nullable = 'NO') = 1
         AND MAX(CAST(column_comment AS BINARY) = CAST(@s3_wh_comment AS BINARY)) = 1 THEN 'EXISTS'
        WHEN MAX(is_nullable = 'YES') = 1
         AND MAX(CAST(column_comment AS BINARY) = CAST(@s1_wh_comment AS BINARY)) = 1 THEN 'TIGHTEN'
        ELSE 'CONFLICT'
    END
    FROM tmp_iws_s3_actual_columns
    WHERE table_name = 'wms_storage' AND column_name = 'warehouse_id'
);
SET @storage_wh_pending = IF(@storage_wh_action = 'TIGHTEN', 1, 0);
SET @storage_wh_conflict = IF(@storage_wh_action = 'CONFLICT', 1, 0);

SET @index_missing_count = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_expected_indexes e
    LEFT JOIN tmp_iws_s3_actual_indexes a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE a.index_name IS NULL
);
SET @index_extra_count = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_actual_indexes a
    LEFT JOIN tmp_iws_s3_expected_indexes e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE e.index_name IS NULL
);
-- 唯一键内 warehouse_id 的可空性由列门禁单独收紧；其余索引属性必须精确一致。
SET @index_mismatch_count = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_actual_indexes a
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
SET @uk_actual_part_count = (
    SELECT COUNT(*) FROM tmp_iws_s3_actual_indexes
    WHERE table_name = 'wms_storage' AND index_name = @uk_index_name
);
SET @uk_missing_part_count = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_expected_indexes e
    LEFT JOIN tmp_iws_s3_actual_indexes a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE e.table_name = 'wms_storage' AND e.index_name = @uk_index_name
      AND a.index_name IS NULL
);
SET @uk_pending = IF(@uk_actual_part_count = 0, 1, 0);
SET @uk_incomplete_conflict = IF(@uk_actual_part_count > 0 AND @uk_missing_part_count > 0, 1, 0);
SET @index_missing_other_count = @index_missing_count - @uk_missing_part_count;
SET @index_conflict_count = @index_missing_other_count + @index_extra_count
    + @index_mismatch_count + @uk_incomplete_conflict;

SET @check_missing_count = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_expected_checks e
    LEFT JOIN tmp_iws_s3_actual_checks a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE a.constraint_name IS NULL
);
SET @check_extra_count = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_actual_checks a
    LEFT JOIN tmp_iws_s3_expected_checks e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE e.constraint_name IS NULL
);
SET @check_mismatch_count = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_actual_checks a
    JOIN tmp_iws_s3_expected_checks e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE CAST(a.check_clause AS BINARY) <> CAST(e.check_clause AS BINARY)
       OR CAST(a.enforced AS BINARY) <> CAST(e.enforced AS BINARY)
);
SET @check_conflict_count = @check_extra_count + @check_mismatch_count;

-- 受影响表上的触发器、本表外键和外部指入外键都属于本切片未声明结构，
-- 必须在首个业务写或 DDL 前拒绝。
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

SET @actual_table_row_count = (SELECT COUNT(*) FROM tmp_iws_s3_actual_tables);
SET @actual_column_row_count = (SELECT COUNT(*) FROM tmp_iws_s3_actual_columns);
SET @actual_index_row_count = (SELECT COUNT(*) FROM tmp_iws_s3_actual_indexes);
SET @actual_check_row_count = (SELECT COUNT(*) FROM tmp_iws_s3_actual_checks);

SET @structure_conflict_count = @non_base_table_count
    + @table_missing_count + @table_mismatch_count + @table_extra_count
    + @column_missing_base_count + @column_extra_count
    + @column_base_mismatch_count + @column_gen_mismatch_count
    + @storage_wh_conflict + @index_conflict_count + @check_conflict_count
    + @unknown_object_count;
SET @s3_pending_count = @column_missing_gen_count + @check_missing_count
    + @uk_pending + @storage_wh_pending;

-- 目标结构指纹：对冻结预期行集合做规范化串联后取 SHA-256；用于发现预期行
-- 被误改，以及部署包与验收包是否同一合同。空值统一写入 <NULL> 标记。
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

SET @preflight_ok = (
    @metadata_visibility_ok = 1
    AND @schema_ok = 1
    AND @version_ok = 1
    AND @fingerprint_ok = 1
    AND @structure_conflict_count = 0
);

-- ----------------------------------------------------------------------------
-- 四、结构与对象动作报告（只读；失败时在首个业务写之前终止）
-- ----------------------------------------------------------------------------
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
SELECT 'S1基底列' AS '对象组', @column_missing_base_count AS '待补', @column_base_mismatch_count AS '冲突'
UNION ALL SELECT 'S1外多余列', 0, @column_extra_count
UNION ALL SELECT '生成列', @column_missing_gen_count, @column_gen_mismatch_count
UNION ALL SELECT '地址CHECK', @check_missing_count, @check_conflict_count
UNION ALL SELECT '索引(含地址唯一键)', @uk_pending, @index_conflict_count
UNION ALL SELECT '仓库列NOT NULL', @storage_wh_pending, @storage_wh_conflict
UNION ALL SELECT '表定义/同名非表对象', @table_missing_count, @table_mismatch_count + @table_extra_count + @non_base_table_count
UNION ALL SELECT '未声明触发器/外键', 0, @unknown_object_count;
SELECT
    @structure_conflict_count AS '结构冲突合计',
    @s3_pending_count AS 'S3待补对象合计',
    @trigger_count AS '受影响表触发器数',
    @own_fk_count AS '受影响表外键数',
    @inbound_fk_count AS '外部指入外键数',
    IF(@preflight_ok = 1, '预检通过',
       CASE
           WHEN @metadata_visibility_ok = 0 THEN '预检失败：元数据权限不足，禁止回填与DDL'
           WHEN @schema_ok = 0 THEN '预检失败：目标库参数与连接库不一致'
           WHEN @version_ok = 0 THEN '预检失败：目标 MySQL 版本不符'
           WHEN @fingerprint_ok = 0 THEN '预检失败：目标结构指纹不符，预期元数据可能被误改'
           WHEN @unknown_object_count > 0 THEN '预检失败：存在未声明触发器/外键，禁止回填与DDL'
           ELSE '预检失败：存在结构冲突，禁止回填与DDL'
       END) AS '预检结果';

-- 结构冲突明细：收集到会话临时表后一次输出，避免同一临时表在 UNION 中重复打开。
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_conflict_detail;
CREATE TEMPORARY TABLE tmp_iws_s3_conflict_detail (
    problem     varchar(64)   NOT NULL,
    table_name  varchar(64)   NULL,
    object_name varchar(128)  NULL,
    detail      varchar(2048) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_iws_s3_conflict_detail (problem, table_name, object_name, detail)
SELECT CASE WHEN a.column_name IS NULL THEN '列缺失' ELSE '列定义冲突' END,
       e.table_name, e.column_name,
       CASE WHEN a.column_name IS NULL THEN ''
            ELSE CONCAT_WS('|', a.column_type, a.is_nullable,
                           IFNULL(a.collation_name, ''),
                           IFNULL(a.generation_expression, ''), a.column_comment)
       END
FROM tmp_iws_s3_expected_columns e
LEFT JOIN tmp_iws_s3_actual_columns a
  ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
 AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
WHERE (e.ordinal_check = 1 AND a.column_name IS NULL)
   OR (e.ordinal_check = 0 AND a.column_name IS NOT NULL
       AND (CAST(a.column_type AS BINARY) <> CAST(e.column_type AS BINARY)
            OR CAST(a.is_nullable AS BINARY) <> CAST(e.is_nullable AS BINARY)
            OR CAST(a.extra AS BINARY) <> CAST(e.extra AS BINARY)
            OR NOT (CAST(a.character_set_name AS BINARY) <=> CAST(e.character_set_name AS BINARY))
            OR NOT (CAST(a.collation_name AS BINARY) <=> CAST(e.collation_name AS BINARY))
            OR CAST(a.generation_expression AS BINARY) <> CAST(e.generation_expression AS BINARY)
            OR CAST(a.column_comment AS BINARY) <> CAST(e.column_comment AS BINARY)));

INSERT INTO tmp_iws_s3_conflict_detail (problem, table_name, object_name, detail)
SELECT '列多余', a.table_name, a.column_name, a.column_type
FROM tmp_iws_s3_actual_columns a
LEFT JOIN tmp_iws_s3_expected_columns e
  ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
 AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
WHERE e.column_name IS NULL;

INSERT INTO tmp_iws_s3_conflict_detail (problem, table_name, object_name, detail)
SELECT '索引缺失', e.table_name, CONCAT(e.index_name, '#', e.seq_in_index), ''
FROM tmp_iws_s3_expected_indexes e
LEFT JOIN tmp_iws_s3_actual_indexes a
  ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
 AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
 AND a.seq_in_index = e.seq_in_index
WHERE a.index_name IS NULL
  AND (e.index_name <> @uk_index_name OR @uk_actual_part_count > 0);

INSERT INTO tmp_iws_s3_conflict_detail (problem, table_name, object_name, detail)
SELECT '索引多余/定义冲突', a.table_name, CONCAT(a.index_name, '#', a.seq_in_index),
       CONCAT_WS('|', a.column_name, a.non_unique, a.index_type, a.is_visible)
FROM tmp_iws_s3_actual_indexes a
LEFT JOIN tmp_iws_s3_expected_indexes e
  ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
 AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
 AND a.seq_in_index = e.seq_in_index
WHERE e.index_name IS NULL
   OR a.non_unique <> e.non_unique
   OR NOT (CAST(a.column_name AS BINARY) <=> CAST(e.column_name AS BINARY))
   OR NOT (a.sub_part <=> e.sub_part)
   OR CAST(a.index_type AS BINARY) <> CAST(e.index_type AS BINARY)
   OR NOT (CAST(a.index_collation AS BINARY) <=> CAST(e.index_collation AS BINARY))
   OR NOT (CAST(a.index_expression AS BINARY) <=> CAST(e.index_expression AS BINARY))
   OR CAST(a.is_visible AS BINARY) <> CAST(e.is_visible AS BINARY)
   OR CAST(a.index_comment AS BINARY) <> CAST(e.index_comment AS BINARY);

INSERT INTO tmp_iws_s3_conflict_detail (problem, table_name, object_name, detail)
SELECT 'CHECK多余/定义冲突', a.table_name, a.constraint_name, a.check_clause
FROM tmp_iws_s3_actual_checks a
LEFT JOIN tmp_iws_s3_expected_checks e
  ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
 AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
WHERE e.constraint_name IS NULL
   OR CAST(a.check_clause AS BINARY) <> CAST(e.check_clause AS BINARY)
   OR CAST(a.enforced AS BINARY) <> CAST(e.enforced AS BINARY);

INSERT INTO tmp_iws_s3_conflict_detail (problem, table_name, object_name, detail)
SELECT '表缺失或定义冲突', e.table_name, '', e.table_comment
FROM tmp_iws_s3_expected_tables e
LEFT JOIN tmp_iws_s3_actual_tables a
  ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
WHERE a.table_name IS NULL
   OR CAST(a.table_type AS BINARY) <> CAST(e.table_type AS BINARY)
   OR NOT (CAST(a.engine AS BINARY) <=> CAST(e.engine AS BINARY))
   OR NOT (CAST(a.table_collation AS BINARY) <=> CAST(e.table_collation AS BINARY))
   OR CAST(a.create_options AS BINARY) <> CAST(e.create_options AS BINARY)
   OR CAST(a.table_comment AS BINARY) <> CAST(e.table_comment AS BINARY);

INSERT INTO tmp_iws_s3_conflict_detail (problem, table_name, object_name, detail)
SELECT '同名非表对象', t.TABLE_NAME, t.TABLE_TYPE, ''
FROM information_schema.TABLES t
WHERE t.TABLE_SCHEMA = DATABASE()
  AND t.TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
  AND t.TABLE_TYPE <> 'BASE TABLE';

SELECT problem AS '问题', table_name AS '表', object_name AS '对象', detail AS '说明'
FROM tmp_iws_s3_conflict_detail
ORDER BY problem, table_name, object_name
LIMIT 60;


SELECT
    rc.CONSTRAINT_SCHEMA AS '来源库',
    rc.TABLE_NAME AS '来源表',
    rc.CONSTRAINT_NAME AS '外键约束',
    rc.REFERENCED_TABLE_NAME AS '目标表'
FROM information_schema.REFERENTIAL_CONSTRAINTS rc
WHERE rc.UNIQUE_CONSTRAINT_SCHEMA = DATABASE()
  AND rc.REFERENCED_TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
ORDER BY rc.CONSTRAINT_SCHEMA, rc.TABLE_NAME, rc.CONSTRAINT_NAME;

SELECT
    t.TRIGGER_NAME AS '触发器',
    t.EVENT_OBJECT_TABLE AS '触发表',
    t.ACTION_TIMING AS '时机',
    t.EVENT_MANIPULATION AS '事件'
FROM information_schema.TRIGGERS t
WHERE t.TRIGGER_SCHEMA = DATABASE()
  AND t.EVENT_OBJECT_TABLE IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
ORDER BY t.EVENT_OBJECT_TABLE, t.TRIGGER_NAME;
SELECT
    tc.TABLE_NAME AS '本表',
    tc.CONSTRAINT_NAME AS '外键约束',
    kcu.COLUMN_NAME AS '本表列',
    kcu.REFERENCED_TABLE_NAME AS '引用表',
    kcu.REFERENCED_COLUMN_NAME AS '引用列'
FROM information_schema.TABLE_CONSTRAINTS tc
JOIN information_schema.KEY_COLUMN_USAGE kcu
  ON kcu.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
 AND kcu.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
 AND kcu.TABLE_NAME = tc.TABLE_NAME
WHERE tc.CONSTRAINT_SCHEMA = DATABASE()
  AND tc.CONSTRAINT_TYPE = 'FOREIGN KEY'
  AND tc.TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
ORDER BY tc.TABLE_NAME, tc.CONSTRAINT_NAME, kcu.ORDINAL_POSITION;
SELECT JSON_EXTRACT(IF(@preflight_ok = 1, '{}', 'invalid'), '$') AS 'S3结构预检门禁';


-- ----------------------------------------------------------------------------
-- 五、旧库数据只读预检：父链、仓库来源、地址规范化与重复
-- ----------------------------------------------------------------------------
-- 父链解析只读；任一阻碍存在时不回填、不收紧、不创建唯一键。
-- 递归 CTE 直接读基础表 wms_warehouse_zone；临时表在同一查询中不能重复打开，
-- 因此父行存在性用 LEFT JOIN 基础表判断，而不是自连接临时表。
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_zone_chain;
CREATE TEMPORARY TABLE tmp_iws_s3_zone_chain AS
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

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_zone_resolution;
CREATE TEMPORARY TABLE tmp_iws_s3_zone_resolution AS
SELECT origin_zone_id,
       MAX(is_missing_parent) AS has_missing_parent,
       MAX(is_cycle) AS has_cycle,
       MAX(CASE WHEN is_cycle = 0 AND is_missing_parent = 0
                 AND CHAR_LENGTH(path_text) >= 3900 AND parent_zone_id IS NOT NULL
                THEN 1 ELSE 0 END) AS is_truncated,
       COUNT(DISTINCT CASE WHEN warehouse_code IS NOT NULL
                           THEN CAST(warehouse_code AS BINARY) END) AS code_count,
       MAX(warehouse_code) AS code_value
FROM tmp_iws_s3_zone_chain
GROUP BY origin_zone_id;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_wh_code;
CREATE TEMPORARY TABLE tmp_iws_s3_wh_code AS
SELECT CAST(warehouse_code AS BINARY) AS code_bin,
       COUNT(*) AS warehouse_count,
       MIN(warehouse_id) AS warehouse_id
FROM wms_warehouse
GROUP BY CAST(warehouse_code AS BINARY);

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_zone_status;
CREATE TEMPORARY TABLE tmp_iws_s3_zone_status AS
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
       r.code_count,
       r.code_value,
       w.warehouse_count,
       w.warehouse_id
FROM tmp_iws_s3_zone_resolution r
LEFT JOIN tmp_iws_s3_wh_code w
  ON r.code_count = 1
 AND CAST(r.code_value AS BINARY) = w.code_bin;

SET @zone_missing_parent_count = (
    SELECT COUNT(*) FROM tmp_iws_s3_zone_status WHERE resolution_status = 'MISSING_PARENT'
);
SET @zone_cycle_count = (
    SELECT COUNT(*) FROM tmp_iws_s3_zone_status WHERE resolution_status = 'CYCLE'
);
SET @zone_truncated_count = (
    SELECT COUNT(*) FROM tmp_iws_s3_zone_status WHERE resolution_status = 'TRUNCATED'
);
SET @zone_multi_code_count = (
    SELECT COUNT(*) FROM tmp_iws_s3_zone_status WHERE resolution_status = 'MULTI_CODE'
);
SET @zone_no_code_count = (
    SELECT COUNT(*) FROM tmp_iws_s3_zone_status WHERE resolution_status = 'NO_CODE'
);
SET @zone_no_warehouse_count = (
    SELECT COUNT(*) FROM tmp_iws_s3_zone_status WHERE resolution_status = 'NO_WAREHOUSE'
);
SET @zone_ambiguous_warehouse_count = (
    SELECT COUNT(*) FROM tmp_iws_s3_zone_status WHERE resolution_status = 'AMBIGUOUS_WAREHOUSE'
);

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_storage_effective;
CREATE TEMPORARY TABLE tmp_iws_s3_storage_effective (
    storage_id            bigint      NOT NULL,
    zone_id               bigint      NOT NULL,
    resolution_status     varchar(24) NULL,
    resolved_warehouse_id bigint      NULL,
    aisle_bin             varbinary(64) NULL,
    row_bin               varbinary(64) NULL,
    level_bin             varbinary(64) NULL,
    column_bin            varbinary(64) NULL,
    depth_bin             varbinary(64) NULL,
    current_warehouse_id  bigint      NULL,
    PRIMARY KEY (storage_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO tmp_iws_s3_storage_effective
    (storage_id, zone_id, resolution_status, resolved_warehouse_id,
     aisle_bin, row_bin, level_bin, column_bin, depth_bin, current_warehouse_id)
SELECT s.storage_id, s.zone_id, zs.resolution_status, zs.warehouse_id,
       CAST(s.aisle_text AS BINARY),
       CASE WHEN s.row_text IS NOT NULL THEN CAST(s.row_text AS BINARY)
            WHEN s.row_no IS NOT NULL THEN CAST(CAST(s.row_no AS CHAR) AS BINARY) END,
       CASE WHEN s.level_text IS NOT NULL THEN CAST(s.level_text AS BINARY)
            WHEN s.level_no IS NOT NULL THEN CAST(CAST(s.level_no AS CHAR) AS BINARY) END,
       CASE WHEN s.column_text IS NOT NULL THEN CAST(s.column_text AS BINARY)
            WHEN s.col_no IS NOT NULL THEN CAST(CAST(s.col_no AS CHAR) AS BINARY) END,
       CAST(s.depth_text AS BINARY),
       s.warehouse_id
FROM wms_storage s
LEFT JOIN tmp_iws_s3_zone_status zs ON zs.zone_id = s.zone_id;

SET @storage_total_count = (SELECT COUNT(*) FROM tmp_iws_s3_storage_effective);
SET @storage_orphan_zone_count = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_storage_effective e
    LEFT JOIN wms_warehouse_zone z ON z.zone_id = e.zone_id
    WHERE z.zone_id IS NULL
);
SET @storage_zone_unresolved_count = (
    SELECT COUNT(*) FROM tmp_iws_s3_storage_effective
    WHERE resolution_status IS NOT NULL AND resolution_status <> 'OK'
);
SET @storage_warehouse_mismatch_count = (
    SELECT COUNT(*) FROM tmp_iws_s3_storage_effective
    WHERE resolution_status = 'OK'
      AND current_warehouse_id IS NOT NULL
      AND current_warehouse_id <> resolved_warehouse_id
);
SET @storage_need_backfill_count = (
    SELECT COUNT(*) FROM tmp_iws_s3_storage_effective
    WHERE resolution_status = 'OK' AND current_warehouse_id IS NULL
);
SET @storage_already_backfilled_count = (
    SELECT COUNT(*) FROM tmp_iws_s3_storage_effective
    WHERE resolution_status = 'OK'
      AND current_warehouse_id IS NOT NULL
      AND current_warehouse_id = resolved_warehouse_id
);
SET @zone_warehouse_value_count_pre = (
    SELECT COUNT(*) FROM wms_warehouse_zone WHERE warehouse_id IS NOT NULL
);

-- 地址值必须为 NULL，或非空且与 ASCII TRIM 结果逐字节相等；空串与首尾空格阻断。
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

-- 同仓规范化五项地址候选重复：GROUP BY 对 NULL 视为同值；varbinary 精确比较。
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_dup_group;
CREATE TEMPORARY TABLE tmp_iws_s3_dup_group AS
SELECT resolved_warehouse_id AS warehouse_id,
       aisle_bin, row_bin, level_bin, column_bin, depth_bin,
       COUNT(*) AS dup_count,
       GROUP_CONCAT(storage_id ORDER BY storage_id SEPARATOR ',') AS storage_ids
FROM tmp_iws_s3_storage_effective
WHERE resolution_status = 'OK'
GROUP BY resolved_warehouse_id, aisle_bin, row_bin, level_bin, column_bin, depth_bin
HAVING COUNT(*) > 1;
SET @address_dup_group_count = (SELECT COUNT(*) FROM tmp_iws_s3_dup_group);
SET @address_dup_row_count = (SELECT IFNULL(SUM(dup_count), 0) FROM tmp_iws_s3_dup_group);
SET @address_allnull_dup_group_count = (
    SELECT COUNT(*) FROM tmp_iws_s3_dup_group
    WHERE aisle_bin IS NULL AND row_bin IS NULL AND level_bin IS NULL
      AND column_bin IS NULL AND depth_bin IS NULL
);

SET @data_blocker_count = @zone_missing_parent_count + @zone_cycle_count
    + @zone_truncated_count + @zone_multi_code_count + @zone_no_code_count
    + @zone_no_warehouse_count + @zone_ambiguous_warehouse_count
    + @storage_orphan_zone_count + @storage_zone_unresolved_count
    + @storage_warehouse_mismatch_count
    + @address_trim_violation_row_count + @address_dup_group_count;

SELECT
    @storage_total_count AS '储位总行数',
    @storage_orphan_zone_count AS '储位孤立库区数',
    @storage_zone_unresolved_count AS '储位库区父链不可解析数',
    @storage_warehouse_mismatch_count AS '储位仓库与父链不一致数',
    @storage_need_backfill_count AS '待回填仓库ID行数',
    @storage_already_backfilled_count AS '仓库ID已一致行数',
    @zone_warehouse_value_count_pre AS '库区warehouse_id现状值数';
SELECT
    @zone_missing_parent_count AS '库区父链缺失数',
    @zone_cycle_count AS '库区父链成环数',
    @zone_truncated_count AS '库区父链遍历截断数',
    @zone_multi_code_count AS '跨warehouse_code父链冲突数',
    @zone_no_code_count AS '库区无仓库编码数',
    @zone_no_warehouse_count AS '仓库编码无匹配仓库数',
    @zone_ambiguous_warehouse_count AS '仓库编码多公司歧义数';
SELECT
    @address_trim_violation_row_count AS '地址规范化违规行数',
    @address_dup_group_count AS '同仓地址重复组数',
    @address_dup_row_count AS '同仓地址重复行数',
    @address_allnull_dup_group_count AS '五项全空重复组数',
    @data_blocker_count AS '数据阻碍合计',
    IF(@data_blocker_count = 0, '数据前置通过',
       'S3数据阻碍：禁止回填、NOT NULL收紧与唯一键创建') AS '数据预检结果';

-- 阻碍明细：库区父链、储位解析、仓库不一致、地址违规、重复组。
SELECT z.zone_id AS '库区ID', z.zone_code AS '库区编码',
       z.parent_zone_id AS '上级库区ID', z.warehouse_code AS '库区仓库编码',
       zs.resolution_status AS '解析状态', zs.code_count AS '父链仓库编码数',
       zs.warehouse_count AS '匹配仓库数', zs.warehouse_id AS '解析仓库ID'
FROM wms_warehouse_zone z
JOIN tmp_iws_s3_zone_status zs ON zs.zone_id = z.zone_id
WHERE zs.resolution_status <> 'OK'
ORDER BY z.zone_id
LIMIT 50;
SELECT s.storage_id AS '储位ID', s.storage_code AS '储位编码',
       s.zone_id AS '库区ID',
       IFNULL(e.resolution_status, 'ZONE_MISSING') AS '解析状态'
FROM wms_storage s
JOIN tmp_iws_s3_storage_effective e ON e.storage_id = s.storage_id
WHERE e.resolution_status IS NULL OR e.resolution_status <> 'OK'
ORDER BY s.storage_id
LIMIT 50;
SELECT s.storage_id AS '储位ID', s.storage_code AS '储位编码', s.zone_id AS '库区ID',
       s.warehouse_id AS '现有仓库ID', e.resolved_warehouse_id AS '父链仓库ID'
FROM wms_storage s
JOIN tmp_iws_s3_storage_effective e ON e.storage_id = s.storage_id
WHERE e.resolution_status = 'OK'
  AND s.warehouse_id IS NOT NULL
  AND s.warehouse_id <> e.resolved_warehouse_id
ORDER BY s.storage_id
LIMIT 50;
SELECT d.* FROM (
    SELECT 'aisle_text' AS '地址列', storage_id AS '储位ID', storage_code AS '储位编码',
           CONCAT('[', aisle_text, ']') AS '显示值', HEX(aisle_text) AS '字节'
    FROM wms_storage
    WHERE aisle_text IS NOT NULL
      AND (aisle_text = '' OR CAST(aisle_text AS BINARY) <> CAST(TRIM(aisle_text) AS BINARY))
    UNION ALL
    SELECT 'row_text', storage_id, storage_code, CONCAT('[', row_text, ']'), HEX(row_text)
    FROM wms_storage
    WHERE row_text IS NOT NULL
      AND (row_text = '' OR CAST(row_text AS BINARY) <> CAST(TRIM(row_text) AS BINARY))
    UNION ALL
    SELECT 'level_text', storage_id, storage_code, CONCAT('[', level_text, ']'), HEX(level_text)
    FROM wms_storage
    WHERE level_text IS NOT NULL
      AND (level_text = '' OR CAST(level_text AS BINARY) <> CAST(TRIM(level_text) AS BINARY))
    UNION ALL
    SELECT 'column_text', storage_id, storage_code, CONCAT('[', column_text, ']'), HEX(column_text)
    FROM wms_storage
    WHERE column_text IS NOT NULL
      AND (column_text = '' OR CAST(column_text AS BINARY) <> CAST(TRIM(column_text) AS BINARY))
    UNION ALL
    SELECT 'depth_text', storage_id, storage_code, CONCAT('[', depth_text, ']'), HEX(depth_text)
    FROM wms_storage
    WHERE depth_text IS NOT NULL
      AND (depth_text = '' OR CAST(depth_text AS BINARY) <> CAST(TRIM(depth_text) AS BINARY))
) d
ORDER BY 1, 2
LIMIT 50;
SELECT g.warehouse_id AS '仓库ID',
       IFNULL(CONCAT('[', CONVERT(g.aisle_bin USING utf8mb4), ']'), '<NULL>') AS '巷道',
       IFNULL(CONCAT('[', CONVERT(g.row_bin USING utf8mb4), ']'), '<NULL>') AS '排',
       IFNULL(CONCAT('[', CONVERT(g.level_bin USING utf8mb4), ']'), '<NULL>') AS '层',
       IFNULL(CONCAT('[', CONVERT(g.column_bin USING utf8mb4), ']'), '<NULL>') AS '列',
       IFNULL(CONCAT('[', CONVERT(g.depth_bin USING utf8mb4), ']'), '<NULL>') AS '深',
       g.dup_count AS '重复行数', g.storage_ids AS '储位ID列表'
FROM tmp_iws_s3_dup_group g
ORDER BY g.warehouse_id, g.storage_ids
LIMIT 50;
SELECT JSON_EXTRACT(IF(@data_blocker_count = 0, '{}', 'invalid'), '$') AS 'S3数据阻碍门禁';

-- ----------------------------------------------------------------------------
-- 六、旧行回填（仅在结构预检与数据预检均通过后执行）
-- ----------------------------------------------------------------------------
SET @write_ok = IF(@preflight_ok = 1 AND @data_blocker_count = 0, 1, 0);

SET @ddl_sql = IF(@write_ok = 1,
    'UPDATE wms_storage s JOIN tmp_iws_s3_storage_effective e ON e.storage_id = s.storage_id SET s.warehouse_id = e.resolved_warehouse_id WHERE s.warehouse_id IS NULL AND e.resolution_status = ''OK'' AND e.resolved_warehouse_id IS NOT NULL',
    'DO 0');
PREPARE stmt_iws_s3_backfill_wh FROM @ddl_sql;
EXECUTE stmt_iws_s3_backfill_wh;
SET @backfill_warehouse_count = ROW_COUNT();
DEALLOCATE PREPARE stmt_iws_s3_backfill_wh;

SET @ddl_sql = IF(@write_ok = 1,
    'UPDATE wms_storage SET row_text = CAST(row_no AS CHAR) WHERE row_text IS NULL AND row_no IS NOT NULL',
    'DO 0');
PREPARE stmt_iws_s3_backfill_row FROM @ddl_sql;
EXECUTE stmt_iws_s3_backfill_row;
SET @backfill_row_text_count = ROW_COUNT();
DEALLOCATE PREPARE stmt_iws_s3_backfill_row;

SET @ddl_sql = IF(@write_ok = 1,
    'UPDATE wms_storage SET level_text = CAST(level_no AS CHAR) WHERE level_text IS NULL AND level_no IS NOT NULL',
    'DO 0');
PREPARE stmt_iws_s3_backfill_level FROM @ddl_sql;
EXECUTE stmt_iws_s3_backfill_level;
SET @backfill_level_text_count = ROW_COUNT();
DEALLOCATE PREPARE stmt_iws_s3_backfill_level;

SET @ddl_sql = IF(@write_ok = 1,
    'UPDATE wms_storage SET column_text = CAST(col_no AS CHAR) WHERE column_text IS NULL AND col_no IS NOT NULL',
    'DO 0');
PREPARE stmt_iws_s3_backfill_column FROM @ddl_sql;
EXECUTE stmt_iws_s3_backfill_column;
SET @backfill_column_text_count = ROW_COUNT();
DEALLOCATE PREPARE stmt_iws_s3_backfill_column;

-- 回填后复核：任何 NULL、规范违规、重复或仓库不一致都必须在 DDL 前阻断。
SET @post_null_warehouse_count = (
    SELECT COUNT(*) FROM wms_storage WHERE warehouse_id IS NULL
);
SET @post_warehouse_mismatch_count = (
    SELECT COUNT(*)
    FROM wms_storage s
    JOIN tmp_iws_s3_storage_effective e ON e.storage_id = s.storage_id
    WHERE e.resolution_status = 'OK'
      AND s.warehouse_id <> e.resolved_warehouse_id
);
SET @post_address_trim_violation_count = (
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
SET @post_dup_group_count = (
    SELECT COUNT(*) FROM (
        SELECT warehouse_id, aisle_text, row_text, level_text, column_text, depth_text
        FROM wms_storage
        WHERE warehouse_id IS NOT NULL
        GROUP BY warehouse_id, aisle_text, row_text, level_text, column_text, depth_text
        HAVING COUNT(*) > 1
    ) d
);
SET @backfill_ok = IF(
    @write_ok = 1
    AND @post_null_warehouse_count = 0
    AND @post_warehouse_mismatch_count = 0
    AND @post_address_trim_violation_count = 0
    AND @post_dup_group_count = 0,
    1, 0
);
SELECT
    @backfill_warehouse_count AS '回填仓库ID行数(辅助)',
    @backfill_row_text_count AS '回填排文本行数(辅助)',
    @backfill_level_text_count AS '回填层文本行数(辅助)',
    @backfill_column_text_count AS '回填列文本行数(辅助)',
    @post_null_warehouse_count AS '回填后仓库ID空值数',
    @post_warehouse_mismatch_count AS '回填后仓库不一致数',
    @post_address_trim_violation_count AS '回填后地址规范违规行数',
    @post_dup_group_count AS '回填后同仓重复组数',
    IF(@backfill_ok = 1, '回填复核通过', '回填复核失败：禁止进入收紧DDL') AS '回填结果';
SELECT JSON_EXTRACT(IF(@backfill_ok = 1, '{}', 'invalid'), '$') AS 'S3回填门禁';

-- ----------------------------------------------------------------------------
-- 七、收紧 DDL（逐对象由元数据与会话 checkpoint 门控）
-- ----------------------------------------------------------------------------
-- 地址 CHECK、生成列 DDL 片段仅用于本次受控 ALTER；不从 INFORMATION_SCHEMA
-- 回读表达式拼 SQL，避免转义形式参与 DDL。
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_check_ddl;
CREATE TEMPORARY TABLE tmp_iws_s3_check_ddl (
    seq             int          NOT NULL,
    constraint_name varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ddl_fragment    longtext     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (seq),
    UNIQUE KEY uk_tmp_iws_s3_check_ddl (constraint_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO tmp_iws_s3_check_ddl
    (seq, constraint_name, ddl_fragment)
VALUES
(1,'ck_wms_storage_aisle_text_trim','ADD CONSTRAINT `ck_wms_storage_aisle_text_trim` CHECK (aisle_text IS NULL OR (aisle_text <> '''' AND CAST(aisle_text AS BINARY) = CAST(TRIM(aisle_text) AS BINARY)))'),
(2,'ck_wms_storage_row_text_trim','ADD CONSTRAINT `ck_wms_storage_row_text_trim` CHECK (row_text IS NULL OR (row_text <> '''' AND CAST(row_text AS BINARY) = CAST(TRIM(row_text) AS BINARY)))'),
(3,'ck_wms_storage_level_text_trim','ADD CONSTRAINT `ck_wms_storage_level_text_trim` CHECK (level_text IS NULL OR (level_text <> '''' AND CAST(level_text AS BINARY) = CAST(TRIM(level_text) AS BINARY)))'),
(4,'ck_wms_storage_column_text_trim','ADD CONSTRAINT `ck_wms_storage_column_text_trim` CHECK (column_text IS NULL OR (column_text <> '''' AND CAST(column_text AS BINARY) = CAST(TRIM(column_text) AS BINARY)))'),
(5,'ck_wms_storage_depth_text_trim','ADD CONSTRAINT `ck_wms_storage_depth_text_trim` CHECK (depth_text IS NULL OR (depth_text <> '''' AND CAST(depth_text AS BINARY) = CAST(TRIM(depth_text) AS BINARY)))');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_conflict_detail;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_gen_ddl;
CREATE TEMPORARY TABLE tmp_iws_s3_gen_ddl (
    seq          int          NOT NULL,
    column_name  varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ddl_fragment longtext     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (seq),
    UNIQUE KEY uk_tmp_iws_s3_gen_ddl (column_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO tmp_iws_s3_gen_ddl
    (seq, column_name, ddl_fragment)
VALUES
(1,'aisle_text_is_null','ADD COLUMN `aisle_text_is_null` tinyint GENERATED ALWAYS AS (aisle_text IS NULL) STORED NOT NULL COMMENT ''巷道地址空值标志；NULL按相等参与同仓地址唯一键'''),
(2,'row_text_is_null','ADD COLUMN `row_text_is_null` tinyint GENERATED ALWAYS AS (row_text IS NULL) STORED NOT NULL COMMENT ''排地址空值标志；NULL按相等参与同仓地址唯一键'''),
(3,'level_text_is_null','ADD COLUMN `level_text_is_null` tinyint GENERATED ALWAYS AS (level_text IS NULL) STORED NOT NULL COMMENT ''层地址空值标志；NULL按相等参与同仓地址唯一键'''),
(4,'column_text_is_null','ADD COLUMN `column_text_is_null` tinyint GENERATED ALWAYS AS (column_text IS NULL) STORED NOT NULL COMMENT ''列地址空值标志；NULL按相等参与同仓地址唯一键'''),
(5,'depth_text_is_null','ADD COLUMN `depth_text_is_null` tinyint GENERATED ALWAYS AS (depth_text IS NULL) STORED NOT NULL COMMENT ''深地址空值标志；NULL按相等参与同仓地址唯一键'''),
(6,'aisle_text_cmp','ADD COLUMN `aisle_text_cmp` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (IFNULL(aisle_text,'''')) STORED NOT NULL COMMENT ''巷道地址精确比较值；NULL统一为空串且使用二进制排序规则'''),
(7,'row_text_cmp','ADD COLUMN `row_text_cmp` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (IFNULL(row_text,'''')) STORED NOT NULL COMMENT ''排地址精确比较值；NULL统一为空串且使用二进制排序规则'''),
(8,'level_text_cmp','ADD COLUMN `level_text_cmp` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (IFNULL(level_text,'''')) STORED NOT NULL COMMENT ''层地址精确比较值；NULL统一为空串且使用二进制排序规则'''),
(9,'column_text_cmp','ADD COLUMN `column_text_cmp` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (IFNULL(column_text,'''')) STORED NOT NULL COMMENT ''列地址精确比较值；NULL统一为空串且使用二进制排序规则'''),
(10,'depth_text_cmp','ADD COLUMN `depth_text_cmp` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (IFNULL(depth_text,'''')) STORED NOT NULL COMMENT ''深地址精确比较值；NULL统一为空串且使用二进制排序规则''');

SET @ddl_chain_ok = @backfill_ok;

-- 1) 五个地址 CHECK，缺失的合并为一个 ALTER。
SET @missing_check_ddl = (
    SELECT GROUP_CONCAT(d.ddl_fragment ORDER BY d.seq SEPARATOR ', ')
    FROM tmp_iws_s3_check_ddl d
    WHERE NOT EXISTS (
        SELECT 1 FROM tmp_iws_s3_actual_checks a
        WHERE CAST(a.constraint_name AS BINARY) = CAST(d.constraint_name AS BINARY)
    )
);
SET @ddl_sql = IF(@ddl_chain_ok = 1 AND @missing_check_ddl IS NOT NULL,
    CONCAT('ALTER TABLE wms_storage ', @missing_check_ddl), 'DO 0');
PREPARE stmt_iws_s3_checks FROM @ddl_sql;
EXECUTE stmt_iws_s3_checks;
DEALLOCATE PREPARE stmt_iws_s3_checks;
SET @ddl_chain_ok = IF(@ddl_chain_ok = 1 AND (
    SELECT COUNT(*) = 5
    FROM tmp_iws_s3_expected_checks e
    WHERE EXISTS (
        SELECT 1
        FROM information_schema.TABLE_CONSTRAINTS tc
        JOIN information_schema.CHECK_CONSTRAINTS cc
          ON CAST(cc.CONSTRAINT_SCHEMA AS BINARY) = CAST(tc.CONSTRAINT_SCHEMA AS BINARY)
         AND CAST(cc.CONSTRAINT_NAME AS BINARY) = CAST(tc.CONSTRAINT_NAME AS BINARY)
        WHERE tc.CONSTRAINT_SCHEMA = DATABASE()
          AND tc.CONSTRAINT_TYPE = 'CHECK'
          AND tc.TABLE_NAME = e.table_name
          AND CAST(tc.CONSTRAINT_NAME AS BINARY) = CAST(e.constraint_name AS BINARY)
          AND CAST(cc.CHECK_CLAUSE AS BINARY) = CAST(e.check_clause AS BINARY)
          AND CAST(tc.ENFORCED AS BINARY) = CAST(e.enforced AS BINARY)
    )
), 1, 0);

-- 2) 回填完成且无空值后收紧 wms_storage.warehouse_id。
SET @ddl_sql = IF(@ddl_chain_ok = 1 AND @storage_wh_action = 'TIGHTEN',
    'ALTER TABLE wms_storage MODIFY COLUMN warehouse_id bigint NOT NULL COMMENT ''所属仓库主键，逻辑关联 wms_warehouse.warehouse_id；S3回填后非空，须与库区父链所属仓库一致''',
    'DO 0');
PREPARE stmt_iws_s3_notnull FROM @ddl_sql;
EXECUTE stmt_iws_s3_notnull;
DEALLOCATE PREPARE stmt_iws_s3_notnull;
SET @ddl_chain_ok = IF(@ddl_chain_ok = 1 AND (
    SELECT COUNT(*) = 1
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_storage'
      AND COLUMN_NAME = 'warehouse_id' AND IS_NULLABLE = 'NO'
      AND CAST(COLUMN_COMMENT AS BINARY) = CAST(@s3_wh_comment AS BINARY)
), 1, 0);

-- 3) 十个 STORED NOT NULL 生成列，缺失的合并为一个 ALTER（物理顺序不作为合同）。
SET @missing_gen_ddl = (
    SELECT GROUP_CONCAT(d.ddl_fragment ORDER BY d.seq SEPARATOR ', ')
    FROM tmp_iws_s3_gen_ddl d
    WHERE NOT EXISTS (
        SELECT 1 FROM tmp_iws_s3_actual_columns a
        WHERE a.table_name = 'wms_storage'
          AND CAST(a.column_name AS BINARY) = CAST(d.column_name AS BINARY)
    )
);
SET @ddl_sql = IF(@ddl_chain_ok = 1 AND @missing_gen_ddl IS NOT NULL,
    CONCAT('ALTER TABLE wms_storage ', @missing_gen_ddl), 'DO 0');
PREPARE stmt_iws_s3_generated FROM @ddl_sql;
EXECUTE stmt_iws_s3_generated;
DEALLOCATE PREPARE stmt_iws_s3_generated;
SET @ddl_chain_ok = IF(@ddl_chain_ok = 1 AND (
    SELECT COUNT(*) = 10
    FROM tmp_iws_s3_expected_columns e
    WHERE e.ordinal_check = 0
      AND EXISTS (
          SELECT 1 FROM information_schema.COLUMNS c
          WHERE c.TABLE_SCHEMA = DATABASE()
            AND c.TABLE_NAME = e.table_name
            AND CAST(c.COLUMN_NAME AS BINARY) = CAST(e.column_name AS BINARY)
            AND c.EXTRA = 'STORED GENERATED'
            AND c.IS_NULLABLE = 'NO'
      )
), 1, 0);

-- 4) 同仓五项地址唯一键（含全部生命周期行，不做部分索引）。
-- 唯一键覆盖 ACTIVE/RETIRING/RETIRED、停用、逻辑删除等全部生命周期行；
-- 不使用 status/del_flag 做部分索引，退役储位继续占用原五项地址组合。
-- 字节核算：8 + 5×1 + 5×64×4 = 1293 字节，低于 InnoDB 3072 字节上限。
SET @ddl_sql = IF(@ddl_chain_ok = 1 AND @uk_pending = 1,
    'ALTER TABLE wms_storage ADD UNIQUE KEY uk_wms_storage_warehouse_address (warehouse_id, aisle_text_is_null, row_text_is_null, level_text_is_null, column_text_is_null, depth_text_is_null, aisle_text_cmp, row_text_cmp, level_text_cmp, column_text_cmp, depth_text_cmp)',
    'DO 0');
PREPARE stmt_iws_s3_uk FROM @ddl_sql;
EXECUTE stmt_iws_s3_uk;
DEALLOCATE PREPARE stmt_iws_s3_uk;

-- ----------------------------------------------------------------------------
-- 八、后置完整结构与数据验收
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_actual_tables;
CREATE TEMPORARY TABLE tmp_iws_s3_actual_tables AS
SELECT TABLE_NAME AS table_name, TABLE_TYPE AS table_type, ENGINE AS engine,
       TABLE_COLLATION AS table_collation, CREATE_OPTIONS AS create_options,
       TABLE_COMMENT AS table_comment
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_actual_columns;
CREATE TEMPORARY TABLE tmp_iws_s3_actual_columns AS
SELECT TABLE_NAME AS table_name, ORDINAL_POSITION AS ordinal_position,
       COLUMN_NAME AS column_name, COLUMN_TYPE AS column_type,
       IS_NULLABLE AS is_nullable, COLUMN_DEFAULT AS column_default,
       EXTRA AS extra, CHARACTER_SET_NAME AS character_set_name,
       COLLATION_NAME AS collation_name, GENERATION_EXPRESSION AS generation_expression,
       COLUMN_COMMENT AS column_comment
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_actual_indexes;
CREATE TEMPORARY TABLE tmp_iws_s3_actual_indexes AS
SELECT TABLE_NAME AS table_name, INDEX_NAME AS index_name,
       NON_UNIQUE AS non_unique, SEQ_IN_INDEX AS seq_in_index,
       COLUMN_NAME AS column_name, SUB_PART AS sub_part, PACKED AS packed,
       NULLABLE AS nullable, INDEX_TYPE AS index_type,
       COLLATION AS index_collation, EXPRESSION AS index_expression,
       IS_VISIBLE AS is_visible, INDEX_COMMENT AS index_comment
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_actual_checks;
CREATE TEMPORARY TABLE tmp_iws_s3_actual_checks AS
SELECT tc.TABLE_NAME AS table_name, tc.CONSTRAINT_NAME AS constraint_name,
       cc.CHECK_CLAUSE AS check_clause, tc.ENFORCED AS enforced
FROM information_schema.TABLE_CONSTRAINTS tc
JOIN information_schema.CHECK_CONSTRAINTS cc
  ON CAST(cc.CONSTRAINT_SCHEMA AS BINARY) = CAST(tc.CONSTRAINT_SCHEMA AS BINARY)
 AND CAST(cc.CONSTRAINT_NAME AS BINARY) = CAST(tc.CONSTRAINT_NAME AS BINARY)
WHERE tc.CONSTRAINT_SCHEMA = DATABASE()
  AND tc.CONSTRAINT_TYPE = 'CHECK'
  AND tc.TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage');

SET @post_column_missing = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_expected_columns e
    LEFT JOIN tmp_iws_s3_actual_columns a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE a.column_name IS NULL
);
SET @post_column_extra = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_actual_columns a
    LEFT JOIN tmp_iws_s3_expected_columns e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE e.column_name IS NULL
);
SET @post_column_mismatch = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_actual_columns a
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
SET @post_column_diff = @post_column_missing + @post_column_extra + @post_column_mismatch;

SET @post_index_missing = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_expected_indexes e
    LEFT JOIN tmp_iws_s3_actual_indexes a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE a.index_name IS NULL
);
SET @post_index_extra = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_actual_indexes a
    LEFT JOIN tmp_iws_s3_expected_indexes e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE e.index_name IS NULL
);
SET @post_index_mismatch = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_actual_indexes a
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
SET @post_index_diff = @post_index_missing + @post_index_extra + @post_index_mismatch;

SET @post_check_missing = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_expected_checks e
    LEFT JOIN tmp_iws_s3_actual_checks a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE a.constraint_name IS NULL
);
SET @post_check_extra = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_actual_checks a
    LEFT JOIN tmp_iws_s3_expected_checks e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE e.constraint_name IS NULL
);
SET @post_check_mismatch = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_actual_checks a
    JOIN tmp_iws_s3_expected_checks e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE CAST(a.check_clause AS BINARY) <> CAST(e.check_clause AS BINARY)
       OR CAST(a.enforced AS BINARY) <> CAST(e.enforced AS BINARY)
);
SET @post_check_diff = @post_check_missing + @post_check_extra + @post_check_mismatch;

SET @post_table_missing = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_expected_tables e
    LEFT JOIN tmp_iws_s3_actual_tables a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE a.table_name IS NULL
);
SET @post_table_mismatch = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_actual_tables a
    JOIN tmp_iws_s3_expected_tables e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE CAST(a.table_type AS BINARY) <> CAST(e.table_type AS BINARY)
       OR NOT (CAST(a.engine AS BINARY) <=> CAST(e.engine AS BINARY))
       OR NOT (CAST(a.table_collation AS BINARY) <=> CAST(e.table_collation AS BINARY))
       OR CAST(a.create_options AS BINARY) <> CAST(e.create_options AS BINARY)
       OR CAST(a.table_comment AS BINARY) <> CAST(e.table_comment AS BINARY)
);
SET @post_table_extra = (
    SELECT COUNT(*)
    FROM tmp_iws_s3_actual_tables a
    LEFT JOIN tmp_iws_s3_expected_tables e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE e.table_name IS NULL
);
SET @post_table_diff = @post_table_missing + @post_table_mismatch + @post_table_extra;

SET @post_non_base_count = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
      AND TABLE_TYPE <> 'BASE TABLE'
);
SET @post_structure_ok = IF(
    @post_column_diff = 0 AND @post_index_diff = 0 AND @post_check_diff = 0
    AND @post_table_diff = 0 AND @post_non_base_count = 0
    AND @fingerprint_ok = 1,
    1, 0
);

SET @post_null_warehouse_count = (
    SELECT COUNT(*) FROM wms_storage WHERE warehouse_id IS NULL
);
SET @post_warehouse_mismatch_count = (
    SELECT COUNT(*)
    FROM wms_storage s
    JOIN tmp_iws_s3_storage_effective e ON e.storage_id = s.storage_id
    WHERE e.resolution_status = 'OK'
      AND s.warehouse_id <> e.resolved_warehouse_id
);
SET @post_address_trim_violation_count = (
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
SET @post_dup_group_count = (
    SELECT COUNT(*) FROM (
        SELECT warehouse_id, aisle_text, row_text, level_text, column_text, depth_text
        FROM wms_storage
        WHERE warehouse_id IS NOT NULL
        GROUP BY warehouse_id, aisle_text, row_text, level_text, column_text, depth_text
        HAVING COUNT(*) > 1
    ) d
);
SET @post_allnull_group_count = (
    SELECT COUNT(*) FROM (
        SELECT warehouse_id
        FROM wms_storage
        WHERE warehouse_id IS NOT NULL AND aisle_text IS NULL AND row_text IS NULL
          AND level_text IS NULL AND column_text IS NULL AND depth_text IS NULL
        GROUP BY warehouse_id
        HAVING COUNT(*) > 1
    ) d
);

-- 生成键与存储值必须一致：五个空值标志等于 IS NULL，五个比较值等于 IFNULL。
SET @post_generated_mismatch_count = (
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
SET @post_zone_warehouse_value_count = (
    SELECT COUNT(*) FROM wms_warehouse_zone WHERE warehouse_id IS NOT NULL
);

SET @uk_part_count_post = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_storage'
      AND INDEX_NAME = @uk_index_name
);
SET @uk_not_null_part_count_post = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS s
    JOIN information_schema.COLUMNS c
      ON c.TABLE_SCHEMA = s.TABLE_SCHEMA AND c.TABLE_NAME = s.TABLE_NAME
     AND c.COLUMN_NAME = s.COLUMN_NAME
    WHERE s.TABLE_SCHEMA = DATABASE() AND s.TABLE_NAME = 'wms_storage'
      AND s.INDEX_NAME = @uk_index_name
      AND c.IS_NULLABLE = 'NO'
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
SET @uk_structure_ok = IF(
    @uk_part_count_post = 11
    AND @uk_not_null_part_count_post = 11
    AND @uk_index_bytes = 1293
    AND @uk_index_bytes <= @uk_index_size_limit,
    1, 0
);

SET @post_ok = IF(
    @preflight_ok = 1
    AND @data_blocker_count = 0
    AND @backfill_ok = 1
    AND @post_structure_ok = 1
    AND @post_null_warehouse_count = 0
    AND @post_warehouse_mismatch_count = 0
    AND @post_address_trim_violation_count = 0
    AND @post_dup_group_count = 0
    AND @post_allnull_group_count = 0
    AND @post_generated_mismatch_count = 0
    AND @post_zone_warehouse_value_count = @zone_warehouse_value_count_pre
    AND @uk_structure_ok = 1,
    1, 0
);

SELECT
    @s3_release_id AS '发布标识',
    @s3_contract_version AS '合同版本',
    @target_schema AS '目标库',
    VERSION() AS '数据库版本',
    IF(@metadata_visibility_ok = 1, '通过', '权限不足') AS '元数据权限',
    IF(@fingerprint_ok = 1, '匹配', '不匹配') AS '目标结构指纹',
    @post_column_diff AS '列差异数',
    @post_index_diff AS '索引差异数',
    @post_check_diff AS 'CHECK差异数',
    @post_table_diff AS '表差异数',
    @post_non_base_count AS '同名非表对象数',
    IF(@post_structure_ok = 1, '通过', '失败') AS '结构验收';
SELECT
    @storage_total_count AS '储位总行数',
    @storage_need_backfill_count AS '计划回填仓库ID行数',
    @backfill_warehouse_count AS '实际回填仓库ID行数(辅助)',
    @backfill_row_text_count AS '实际回填排文本行数(辅助)',
    @backfill_level_text_count AS '实际回填层文本行数(辅助)',
    @backfill_column_text_count AS '实际回填列文本行数(辅助)',
    @post_null_warehouse_count AS '仓库ID空值数',
    @post_warehouse_mismatch_count AS '仓库与父链不一致数',
    @post_address_trim_violation_count AS '地址规范违规行数',
    @post_dup_group_count AS '同仓地址重复组数',
    @post_allnull_group_count AS '五项全空重复组数',
    @post_generated_mismatch_count AS '生成键与存储值不一致数',
    @post_zone_warehouse_value_count AS '库区warehouse_id值数',
    @zone_warehouse_value_count_pre AS '升级前库区warehouse_id值数';
SELECT
    @uk_part_count_post AS '唯一键列数',
    @uk_not_null_part_count_post AS '唯一键非空列数',
    @uk_index_bytes AS '唯一键字节数',
    @uk_index_size_limit AS 'InnoDB单索引字节上限',
    IF(@uk_structure_ok = 1, '结构满足', '结构不满足') AS '唯一键核算结果';
SELECT
    @data_blocker_count AS '数据阻碍合计',
    @structure_conflict_count AS '升级前结构冲突合计',
    IF(@post_ok = 1,
       'S3回填与收紧完成；唯一键覆盖全部生命周期行；S4全量验收与cutover未实现',
       CASE
           WHEN @metadata_visibility_ok = 0 THEN '失败：元数据权限不足'
           WHEN @fingerprint_ok = 0 THEN '失败：目标结构指纹不符'
           WHEN @structure_conflict_count > 0 THEN '失败：升级前结构冲突'
           WHEN @data_blocker_count > 0 THEN '失败：旧库数据阻碍，未回填未收紧'
           WHEN @backfill_ok = 0 THEN '失败：回填复核未通过'
           WHEN @post_structure_ok = 0 THEN '失败：后置结构验收不一致'
           ELSE '失败：后置数据或唯一键验收未通过'
       END) AS '执行结果';
SELECT JSON_EXTRACT(IF(@post_ok = 1, '{}', 'invalid'), '$') AS 'S3完成门禁';

-- ----------------------------------------------------------------------------
-- 九、会话临时对象清理
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_conflict_detail;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_gen_ddl;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_check_ddl;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_dup_group;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_storage_effective;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_zone_status;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_wh_code;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_zone_resolution;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_zone_chain;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_actual_checks;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_actual_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_actual_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_actual_tables;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_expected_checks;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_expected_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_expected_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s3_expected_tables;

SET SESSION group_concat_max_len = @saved_group_concat_max_len;
