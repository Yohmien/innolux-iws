-- ============================================================================
-- IWS+ 库存 P5-01 分配、锁与释放独立目标结构
-- ============================================================================
-- 目标数据库：MySQL 8.0.45
-- 目标库参数：@target_schema，集中定义于下方；必须与连接 DATABASE() 字节一致
-- 独立验收：要求以下 6 表已完整存在，不执行目标 DDL。
--   wms_access_channel_lock
--   wms_allocation_candidate
--   wms_allocation_decision
--   wms_allocation_lock
--   wms_allocation_release
--   wms_inventory_reservation
-- 依据：
--   D/work/inventory-p1-contract-20261027.md §5.5、§7、§8、§11
--   D/work/inventory-implementation-tasks-20261027.md §8
--   D/docs/inventory-allocation-rules-20261026.md §3、§7、§8
-- 范围：
--   只建立独立目标结构。跨表 ID 均为逻辑引用，不建立物理外键；物理占用由 P4
--   wms_storage_occupancy 负责，本文件不重复建账；不修改空间、P4、库存或旧 flow；
--   不创建业务码、计划能力、菜单、权限、seed，不回填、不迁移、不写真实库。
-- 键与边界：
--   LOCKED 通道锁由 active_channel_key 唯一约束保证一个通道只有一个活动锁；
--   CONSUMED/RELEASED 为终态且不占活动键。allocation_lock 的 candidate_share_key
--   允许同一候选按容量维度或实体槽拆分多行；active_share_key 在实体槽锁下绑定
--   storage+slot，在容量份额下使用锁 ID 以允许并存份额；
--   容量是否足够、路径可达、成员归属和锁序仍由后续锁内事务校验。
--   effect_result 保留 SEND_INTENT/ACCEPTED/UNKNOWN；UNKNOWN 保持锁事实，
--   不得凭超时/页面关闭释放。释放只撤销本任务份额，不改物理占用。
-- 幂等与冲突：
--   首次、二次、三次重跑共用同一套元数据流程。全缺、部分已满足、全部已满足可继续；
--   已有目标表必须逐表、逐列、逐索引、逐 CHECK 完整匹配；同名 VIEW、大小写错配、
--   未知列/索引/CHECK、表选项漂移、本表 FK、任意 schema 指入 FK、目标触发器、
--   权限不可见或错误目标库均在首条目标 DDL 前硬失败。漂移表不 ALTER/删除/覆盖。
-- 离线账号合同：
--   直接持有全局 SELECT、TRIGGER，partial_revokes=OFF；schema/table/角色授权不满足
--   跨 schema 入向外键与触发器完整可见性证明。
-- 执行：
--   mysql --no-defaults --protocol=TCP --host=<主机> --port=<端口> \
--     --user=<账号> --default-character-set=utf8mb4 --database=innolux-iws \
--     --batch --raw < script/sql/iws_inventory_management_allocation_foundation_verify.sql
--   必须使用 stdin 或等价首错退出执行器，不得添加 --force。DDL 隐式提交；部分执行后
--   依据 INFORMATION_SCHEMA 重跑，不承诺整份脚本事务回滚。
-- ============================================================================

SET NAMES utf8mb4;


-- ----------------------------------------------------------------------------
-- 集中参数
-- ----------------------------------------------------------------------------
SET @target_schema = 'innolux-iws';
SET @p5_release_id = 'INV-P5-01-ALLOCATION-FOUNDATION-20261029';
SET @p5_contract_version = 'ALLOCATION-6T-P5-01-20261029';
SET @expected_mysql_version = '8.0.45';
SET @expected_table_count = 6;
SET @target_schema_q = CONCAT('`', REPLACE(@target_schema, '`', '``'), '`');
SET @target_table_names = 'wms_access_channel_lock,wms_allocation_candidate,wms_allocation_decision,wms_allocation_lock,wms_allocation_release,wms_inventory_reservation';

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
DROP TEMPORARY TABLE IF EXISTS tmp_iws_p5_expected_tables;
CREATE TEMPORARY TABLE tmp_iws_p5_expected_tables (
    table_name       varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    table_type       varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    engine           varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    table_collation  varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    create_options   varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    table_comment    varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_p5_expected_columns;
CREATE TEMPORARY TABLE tmp_iws_p5_expected_columns (
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
    UNIQUE KEY uk_tmp_iws_p5_expected_columns (table_name, column_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_p5_expected_indexes;
CREATE TEMPORARY TABLE tmp_iws_p5_expected_indexes (
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

DROP TEMPORARY TABLE IF EXISTS tmp_iws_p5_expected_checks;
CREATE TEMPORARY TABLE tmp_iws_p5_expected_checks (
    table_name       varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    constraint_name  varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    check_clause     longtext    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    enforced         varchar(3)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (table_name, constraint_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 以下完整预期行由目标 MySQL 8.0.45 的实际 INFORMATION_SCHEMA 生成并冻结。

INSERT INTO tmp_iws_p5_expected_tables
    (table_name, table_type, engine, table_collation, create_options, table_comment)
VALUES
('wms_access_channel_lock','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS持久通道锁'),
('wms_allocation_candidate','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS分配候选与排序证据'),
('wms_allocation_decision','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS分配决策'),
('wms_allocation_lock','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS分配锁/实体槽锁'),
('wms_allocation_release','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS分配锁释放流水'),
('wms_inventory_reservation','BASE TABLE','InnoDB','utf8mb4_0900_ai_ci','','WMS数量预留账');

INSERT INTO tmp_iws_p5_expected_columns
    (table_name, ordinal_position, column_name, column_type, is_nullable, column_default,
     extra, character_set_name, collation_name, generation_expression, column_comment)
VALUES
('wms_access_channel_lock',1,'channel_lock_id','bigint','NO',NULL,'',NULL,NULL,'','通道锁主键，服务显式分配'),
('wms_access_channel_lock',2,'channel_id','bigint','NO',NULL,'',NULL,NULL,'','访问通道ID，逻辑关联 wms_access_channel.channel_id'),
('wms_access_channel_lock',3,'business_type','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','父业务类型稳定编码'),
('wms_access_channel_lock',4,'business_name','varchar(128)','NO',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','父业务类型名称快照'),
('wms_access_channel_lock',5,'business_id','bigint','NO',NULL,'',NULL,NULL,'','父业务内部ID'),
('wms_access_channel_lock',6,'business_no','varchar(100)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','父业务号快照'),
('wms_access_channel_lock',7,'business_line_id','bigint','YES',NULL,'',NULL,NULL,'','父业务明细ID'),
('wms_access_channel_lock',8,'allocation_unit_id','bigint','NO',NULL,'',NULL,NULL,'','不可拆分配单元ID'),
('wms_access_channel_lock',9,'round_no','int','NO',NULL,'',NULL,NULL,'','分配轮次'),
('wms_access_channel_lock',10,'phase','varchar(16)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','任务阶段：LOCK/RELEASE'),
('wms_access_channel_lock',11,'task_key','varchar(96)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','稳定任务键'),
('wms_access_channel_lock',12,'plan_instance_id','bigint','NO',NULL,'',NULL,NULL,'','计划实例ID'),
('wms_access_channel_lock',13,'lock_effect_key','varchar(128)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','通道锁效果键WMS:SHA-256'),
('wms_access_channel_lock',14,'status','varchar(16)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','状态：LOCKED/CONSUMED/RELEASED'),
('wms_access_channel_lock',15,'effect_result','varchar(16)','NO','NONE','','utf8mb4','utf8mb4_0900_bin','','外部效果：NONE/SEND_INTENT/ACCEPTED/UNKNOWN/CONSUMED/NO_EFFECT'),
('wms_access_channel_lock',16,'active_channel_key','bigint','YES',NULL,'STORED GENERATED',NULL,NULL,'if((`status` = _utf8mb4\\''LOCKED\\''),`channel_id`,NULL)','活动通道ID，终态为NULL'),
('wms_access_channel_lock',17,'path_member_ids_json','json','NO',NULL,'',NULL,NULL,'','锁内路径成员ID数组快照'),
('wms_access_channel_lock',18,'path_revision','int','NO',NULL,'',NULL,NULL,'','通道路径修订号快照'),
('wms_access_channel_lock',19,'evidence_revision','int','NO','0','',NULL,NULL,'','无效果或外部效果证据修订号'),
('wms_access_channel_lock',20,'terminal_effect_key','varchar(128)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','通道锁终态效果键'),
('wms_access_channel_lock',21,'terminal_release_id','bigint','YES',NULL,'',NULL,NULL,'','终态释放流水ID'),
('wms_access_channel_lock',22,'consumption_trace_id','bigint','YES',NULL,'',NULL,NULL,'','消费trace头ID'),
('wms_access_channel_lock',23,'terminal_at','datetime(3)','YES',NULL,'',NULL,NULL,'','终态时间'),
('wms_access_channel_lock',24,'revision','int','NO','1','',NULL,NULL,'','通道锁修订号'),
('wms_access_channel_lock',25,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_access_channel_lock',26,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_access_channel_lock',27,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_access_channel_lock',28,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_access_channel_lock',29,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_allocation_candidate',1,'candidate_id','bigint','NO',NULL,'',NULL,NULL,'','候选主键，服务显式分配'),
('wms_allocation_candidate',2,'decision_id','bigint','NO',NULL,'',NULL,NULL,'','分配决策ID，逻辑关联 wms_allocation_decision.decision_id'),
('wms_allocation_candidate',3,'storage_id','bigint','NO',NULL,'',NULL,NULL,'','候选储位ID，逻辑关联 wms_storage.storage_id'),
('wms_allocation_candidate',4,'rank_no','int','NO',NULL,'',NULL,NULL,'','稳定排序序号'),
('wms_allocation_candidate',5,'eligible_flag','tinyint(1)','NO','1','',NULL,NULL,'','硬筛选后是否可用：1可用，0不可用'),
('wms_allocation_candidate',6,'selected_flag','tinyint(1)','NO','0','',NULL,NULL,'','是否选中：1选中，0未选中'),
('wms_allocation_candidate',7,'sort_key_json','json','NO',NULL,'',NULL,NULL,'','冻结排序键数组'),
('wms_allocation_candidate',8,'residual_vector_json','json','NO',NULL,'',NULL,NULL,'','多维残余向量对象'),
('wms_allocation_candidate',9,'protection_reason','varchar(500)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','稀缺/深位保护原因'),
('wms_allocation_candidate',10,'block_reason','varchar(500)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','硬阻挡原因'),
('wms_allocation_candidate',11,'selected_decision_key','bigint','YES',NULL,'STORED GENERATED',NULL,NULL,'if((`selected_flag` = 1),`decision_id`,NULL)','选中候选决策唯一键，未选中为NULL'),
('wms_allocation_candidate',12,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_allocation_candidate',13,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_allocation_candidate',14,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_allocation_candidate',15,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_allocation_candidate',16,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_allocation_decision',1,'decision_id','bigint','NO',NULL,'',NULL,NULL,'','分配决策主键，服务显式分配'),
('wms_allocation_decision',2,'business_type','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','父业务类型稳定编码'),
('wms_allocation_decision',3,'business_name','varchar(128)','NO',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','父业务类型名称快照'),
('wms_allocation_decision',4,'business_id','bigint','NO',NULL,'',NULL,NULL,'','父业务内部ID'),
('wms_allocation_decision',5,'business_no','varchar(100)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','父业务号快照'),
('wms_allocation_decision',6,'business_line_id','bigint','YES',NULL,'',NULL,NULL,'','父业务明细ID'),
('wms_allocation_decision',7,'allocation_unit_id','bigint','NO',NULL,'',NULL,NULL,'','不可拆分配单元ID'),
('wms_allocation_decision',8,'round_no','int','NO',NULL,'',NULL,NULL,'','分配轮次'),
('wms_allocation_decision',9,'phase','varchar(16)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','任务阶段：LOCK/RELEASE'),
('wms_allocation_decision',10,'task_key','varchar(96)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','稳定任务键'),
('wms_allocation_decision',11,'plan_instance_id','bigint','NO',NULL,'',NULL,NULL,'','计划实例ID'),
('wms_allocation_decision',12,'effect_key','varchar(128)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','决策效果键WMS:SHA-256'),
('wms_allocation_decision',13,'input_hash','char(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','规范化输入SHA-256小写十六进制'),
('wms_allocation_decision',14,'rule_package_version_id','bigint','NO',NULL,'',NULL,NULL,'','规则包版本ID快照'),
('wms_allocation_decision',15,'logical_scope_version_id','bigint','NO',NULL,'',NULL,NULL,'','逻辑归属版本ID快照'),
('wms_allocation_decision',16,'space_snapshot_hash','char(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','空间配置快照SHA-256'),
('wms_allocation_decision',17,'capacity_snapshot_hash','char(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','容量账快照SHA-256'),
('wms_allocation_decision',18,'candidate_summary_json','json','NO',NULL,'',NULL,NULL,'','候选摘要与筛选解释JSON'),
('wms_allocation_decision',19,'result','varchar(32)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','结果：EVALUATED/LOCKED/NO_CANDIDATE/BLOCKED'),
('wms_allocation_decision',20,'result_reason','varchar(500)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','无解或阻断原因'),
('wms_allocation_decision',21,'decided_at','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','决策时间'),
('wms_allocation_decision',22,'revision','int','NO','1','',NULL,NULL,'','决策修订号'),
('wms_allocation_decision',23,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_allocation_decision',24,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_allocation_decision',25,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_allocation_decision',26,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_allocation_decision',27,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_allocation_lock',1,'allocation_lock_id','bigint','NO',NULL,'',NULL,NULL,'','分配锁主键，服务显式分配'),
('wms_allocation_lock',2,'decision_id','bigint','NO',NULL,'',NULL,NULL,'','分配决策ID，逻辑关联 wms_allocation_decision.decision_id'),
('wms_allocation_lock',3,'candidate_id','bigint','NO',NULL,'',NULL,NULL,'','候选ID，逻辑关联 wms_allocation_candidate.candidate_id'),
('wms_allocation_lock',4,'channel_lock_id','bigint','YES',NULL,'',NULL,NULL,'','通道锁ID，资源域为CHANNEL时必填'),
('wms_allocation_lock',5,'business_type','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','父业务类型稳定编码'),
('wms_allocation_lock',6,'business_name','varchar(128)','NO',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','父业务类型名称快照'),
('wms_allocation_lock',7,'business_id','bigint','NO',NULL,'',NULL,NULL,'','父业务内部ID'),
('wms_allocation_lock',8,'business_no','varchar(100)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','父业务号快照'),
('wms_allocation_lock',9,'business_line_id','bigint','YES',NULL,'',NULL,NULL,'','父业务明细ID'),
('wms_allocation_lock',10,'allocation_unit_id','bigint','NO',NULL,'',NULL,NULL,'','不可拆分配单元ID'),
('wms_allocation_lock',11,'round_no','int','NO',NULL,'',NULL,NULL,'','分配轮次'),
('wms_allocation_lock',12,'phase','varchar(16)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','任务阶段：LOCK/RELEASE'),
('wms_allocation_lock',13,'task_key','varchar(96)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','稳定任务键'),
('wms_allocation_lock',14,'plan_instance_id','bigint','NO',NULL,'',NULL,NULL,'','计划实例ID'),
('wms_allocation_lock',15,'storage_id','bigint','NO',NULL,'',NULL,NULL,'','锁定的物理储位ID'),
('wms_allocation_lock',16,'resource_domain','varchar(16)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','锁域：STORAGE/CHANNEL'),
('wms_allocation_lock',17,'lock_kind','varchar(32)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','锁类型：CAPACITY_SHARE/ENTITY_SLOT'),
('wms_allocation_lock',18,'dimension_key','varchar(64)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','容量份额维度键'),
('wms_allocation_lock',19,'unit','varchar(32)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','容量份额单位'),
('wms_allocation_lock',20,'locked_qty','decimal(38,12)','YES',NULL,'',NULL,NULL,'','容量份额数量'),
('wms_allocation_lock',21,'slot_key','varchar(128)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','实体槽稳定键'),
('wms_allocation_lock',22,'status','varchar(16)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','状态：LOCKED/CONSUMED/RELEASED'),
('wms_allocation_lock',23,'effect_result','varchar(16)','NO','NONE','','utf8mb4','utf8mb4_0900_bin','','外部效果：NONE/SEND_INTENT/ACCEPTED/UNKNOWN/CONSUMED/NO_EFFECT'),
('wms_allocation_lock',24,'effect_key','varchar(128)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','分配锁效果键WMS:SHA-256'),
('wms_allocation_lock',25,'evidence_revision','int','NO','0','',NULL,NULL,'','证据版本：无效果或外部效果'),
('wms_allocation_lock',26,'candidate_share_key','varchar(300)','YES',NULL,'STORED GENERATED','utf8mb4','utf8mb4_0900_bin','(case when (`lock_kind` = _utf8mb4\\''CAPACITY_SHARE\\'') then concat(_utf8mb4\\''C:\\'',lpad(hex(`candidate_id`),16,_utf8mb4\\''0\\''),_utf8mb4\\'':\\'',hex(cast(`dimension_key` as char charset binary))) when (`lock_kind` = _utf8mb4\\''ENTITY_SLOT\\'') then concat(_utf8mb4\\''E:\\'',lpad(hex(`candidate_id`),16,_utf8mb4\\''0\\''),_utf8mb4\\'':\\'',hex(cast(`slot_key` as char charset binary))) end)','候选容量维度/实体槽唯一键，支持同一候选多维或多槽'),
('wms_allocation_lock',27,'active_share_key','varchar(560)','YES',NULL,'STORED GENERATED','utf8mb4','utf8mb4_0900_bin','(case when ((`status` = _utf8mb4\\''LOCKED\\'') and (`lock_kind` = _utf8mb4\\''ENTITY_SLOT\\'')) then concat(_utf8mb4\\''S:\\'',lpad(hex(`storage_id`),16,_utf8mb4\\''0\\''),_utf8mb4\\'':\\'',hex(cast(`slot_key` as char charset binary))) when (`status` = _utf8mb4\\''LOCKED\\'') then concat(_utf8mb4\\''C:\\'',lpad(hex(`allocation_lock_id`),16,_utf8mb4\\''0\\'')) else NULL end)','活动份额/实体槽唯一键，终态为NULL'),
('wms_allocation_lock',28,'terminal_effect_key','varchar(128)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','锁终态效果键'),
('wms_allocation_lock',29,'consumption_trace_id','bigint','YES',NULL,'',NULL,NULL,'','消费trace头ID'),
('wms_allocation_lock',30,'terminal_release_id','bigint','YES',NULL,'',NULL,NULL,'','终态释放流水ID'),
('wms_allocation_lock',31,'terminal_at','datetime(3)','YES',NULL,'',NULL,NULL,'','终态时间'),
('wms_allocation_lock',32,'revision','int','NO','1','',NULL,NULL,'','分配锁修订号'),
('wms_allocation_lock',33,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_allocation_lock',34,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_allocation_lock',35,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_allocation_lock',36,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_allocation_lock',37,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间'),
('wms_allocation_release',1,'release_id','bigint','NO',NULL,'',NULL,NULL,'','释放流水主键，服务显式分配'),
('wms_allocation_release',2,'allocation_lock_id','bigint','NO',NULL,'',NULL,NULL,'','被释放分配锁ID，逻辑关联 wms_allocation_lock.allocation_lock_id'),
('wms_allocation_release',3,'channel_lock_id','bigint','YES',NULL,'',NULL,NULL,'','关联通道锁ID'),
('wms_allocation_release',4,'business_type','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','父业务类型稳定编码'),
('wms_allocation_release',5,'business_name','varchar(128)','NO',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','父业务类型名称快照'),
('wms_allocation_release',6,'business_id','bigint','NO',NULL,'',NULL,NULL,'','父业务内部ID'),
('wms_allocation_release',7,'business_no','varchar(100)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','父业务号快照'),
('wms_allocation_release',8,'business_line_id','bigint','YES',NULL,'',NULL,NULL,'','父业务明细ID'),
('wms_allocation_release',9,'allocation_unit_id','bigint','NO',NULL,'',NULL,NULL,'','不可拆分配单元ID'),
('wms_allocation_release',10,'round_no','int','NO',NULL,'',NULL,NULL,'','分配轮次'),
('wms_allocation_release',11,'phase','varchar(16)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','释放阶段：RELEASE'),
('wms_allocation_release',12,'task_key','varchar(96)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','稳定任务键'),
('wms_allocation_release',13,'plan_instance_id','bigint','NO',NULL,'',NULL,NULL,'','计划实例ID'),
('wms_allocation_release',14,'evidence_revision','int','NO',NULL,'',NULL,NULL,'','无效果证据修订号'),
('wms_allocation_release',15,'evidence_ref','varchar(500)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','脱敏证据引用'),
('wms_allocation_release',16,'effect_key','varchar(128)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','释放效果键WMS:SHA-256'),
('wms_allocation_release',17,'before_status','varchar(16)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','释放前锁状态'),
('wms_allocation_release',18,'after_status','varchar(16)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','释放后锁状态'),
('wms_allocation_release',19,'release_reason','varchar(500)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','释放原因'),
('wms_allocation_release',20,'operator_id','bigint','YES',NULL,'',NULL,NULL,'','操作者ID'),
('wms_allocation_release',21,'occurred_at','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','释放发生时间'),
('wms_allocation_release',22,'recorded_at','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','释放事实记录时间'),
('wms_allocation_release',23,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_allocation_release',24,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_allocation_release',25,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_allocation_release',26,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者，释放事实由服务禁止更新'),
('wms_allocation_release',27,'update_time','datetime(3)','YES',NULL,'',NULL,NULL,'','更新时间，释放事实由服务禁止更新'),
('wms_inventory_reservation',1,'reservation_id','bigint','NO',NULL,'',NULL,NULL,'','数量预留主键，服务显式分配'),
('wms_inventory_reservation',2,'inventory_id','bigint','NO',NULL,'',NULL,NULL,'','库存单元ID，逻辑关联 wms_inventory.inventory_id'),
('wms_inventory_reservation',3,'business_type','varchar(64)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','父业务类型稳定编码'),
('wms_inventory_reservation',4,'business_name','varchar(128)','NO',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','父业务类型名称快照'),
('wms_inventory_reservation',5,'business_id','bigint','NO',NULL,'',NULL,NULL,'','父业务内部ID'),
('wms_inventory_reservation',6,'business_no','varchar(100)','YES',NULL,'','utf8mb4','utf8mb4_0900_ai_ci','','父业务号快照'),
('wms_inventory_reservation',7,'business_line_id','bigint','YES',NULL,'',NULL,NULL,'','父业务明细ID'),
('wms_inventory_reservation',8,'allocation_unit_id','bigint','NO',NULL,'',NULL,NULL,'','不可拆分配单元ID'),
('wms_inventory_reservation',9,'round_no','int','NO',NULL,'',NULL,NULL,'','分配轮次'),
('wms_inventory_reservation',10,'phase','varchar(16)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','任务阶段：LOCK/RELEASE'),
('wms_inventory_reservation',11,'task_key','varchar(96)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','稳定任务键'),
('wms_inventory_reservation',12,'plan_instance_id','bigint','NO',NULL,'',NULL,NULL,'','计划实例ID'),
('wms_inventory_reservation',13,'reserved_qty','decimal(20,6)','NO',NULL,'',NULL,NULL,'','预留数量'),
('wms_inventory_reservation',14,'unit','varchar(16)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','预留单位'),
('wms_inventory_reservation',15,'status','varchar(16)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','状态：ACTIVE/CONSUMED/RELEASED'),
('wms_inventory_reservation',16,'effect_result','varchar(16)','NO','NONE','','utf8mb4','utf8mb4_0900_bin','','外部效果：NONE/SEND_INTENT/ACCEPTED/UNKNOWN/CONSUMED/NO_EFFECT'),
('wms_inventory_reservation',17,'effect_key','varchar(128)','NO',NULL,'','utf8mb4','utf8mb4_0900_bin','','预留效果键WMS:SHA-256'),
('wms_inventory_reservation',18,'evidence_revision','int','NO','0','',NULL,NULL,'','证据修订号'),
('wms_inventory_reservation',19,'expires_at','datetime(3)','YES',NULL,'',NULL,NULL,'','预留到期时间，不自动释放依据'),
('wms_inventory_reservation',20,'active_marker','tinyint','YES',NULL,'STORED GENERATED',NULL,NULL,'if((`status` = _utf8mb4\\''ACTIVE\\''),1,NULL)','活动预留标志，终态为NULL'),
('wms_inventory_reservation',21,'terminal_effect_key','varchar(128)','YES',NULL,'','utf8mb4','utf8mb4_0900_bin','','预留终态效果键'),
('wms_inventory_reservation',22,'consumption_trace_id','bigint','YES',NULL,'',NULL,NULL,'','消费trace头ID'),
('wms_inventory_reservation',23,'terminal_release_id','bigint','YES',NULL,'',NULL,NULL,'','终态释放流水ID'),
('wms_inventory_reservation',24,'terminal_at','datetime(3)','YES',NULL,'',NULL,NULL,'','终态时间'),
('wms_inventory_reservation',25,'revision','int','NO','1','',NULL,NULL,'','预留修订号'),
('wms_inventory_reservation',26,'create_dept','bigint','YES',NULL,'',NULL,NULL,'','创建部门'),
('wms_inventory_reservation',27,'create_by','bigint','YES',NULL,'',NULL,NULL,'','创建者'),
('wms_inventory_reservation',28,'create_time','datetime(3)','NO','CURRENT_TIMESTAMP(3)','DEFAULT_GENERATED',NULL,NULL,'','创建时间'),
('wms_inventory_reservation',29,'update_by','bigint','YES',NULL,'',NULL,NULL,'','更新者'),
('wms_inventory_reservation',30,'update_time','datetime(3)','YES',NULL,'on update CURRENT_TIMESTAMP(3)',NULL,NULL,'','更新时间');

INSERT INTO tmp_iws_p5_expected_indexes
    (table_name, index_name, non_unique, seq_in_index, column_name, sub_part, packed,
     nullable, index_type, index_collation, index_expression, is_visible, index_comment)
VALUES
('wms_access_channel_lock','idx_wms_access_channel_lock_business',1,1,'business_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_access_channel_lock','idx_wms_access_channel_lock_business',1,2,'business_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_access_channel_lock','idx_wms_access_channel_lock_business',1,3,'business_line_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_access_channel_lock','idx_wms_access_channel_lock_channel_status',1,1,'channel_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_access_channel_lock','idx_wms_access_channel_lock_channel_status',1,2,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_access_channel_lock','idx_wms_access_channel_lock_channel_status',1,3,'evidence_revision',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_access_channel_lock','idx_wms_access_channel_lock_task',1,1,'business_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_access_channel_lock','idx_wms_access_channel_lock_task',1,2,'task_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_access_channel_lock','PRIMARY',0,1,'channel_lock_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_access_channel_lock','uk_wms_access_channel_lock_active',0,1,'active_channel_key',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_access_channel_lock','uk_wms_access_channel_lock_effect',0,1,'lock_effect_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_access_channel_lock','uk_wms_access_channel_lock_terminal_effect',0,1,'terminal_effect_key',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_access_channel_lock','uk_wms_access_channel_lock_terminal_release',0,1,'terminal_release_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_allocation_candidate','idx_wms_allocation_candidate_eligible',1,1,'decision_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_candidate','idx_wms_allocation_candidate_eligible',1,2,'eligible_flag',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_candidate','idx_wms_allocation_candidate_eligible',1,3,'rank_no',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_candidate','idx_wms_allocation_candidate_storage',1,1,'storage_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_candidate','idx_wms_allocation_candidate_storage',1,2,'decision_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_candidate','PRIMARY',0,1,'candidate_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_candidate','uk_wms_allocation_candidate_decision_rank',0,1,'decision_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_candidate','uk_wms_allocation_candidate_decision_rank',0,2,'rank_no',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_candidate','uk_wms_allocation_candidate_decision_storage',0,1,'decision_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_candidate','uk_wms_allocation_candidate_decision_storage',0,2,'storage_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_candidate','uk_wms_allocation_candidate_selected',0,1,'selected_decision_key',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_allocation_decision','idx_wms_allocation_decision_business',1,1,'business_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_decision','idx_wms_allocation_decision_business',1,2,'business_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_decision','idx_wms_allocation_decision_business',1,3,'business_line_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_allocation_decision','idx_wms_allocation_decision_result',1,1,'result',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_decision','idx_wms_allocation_decision_result',1,2,'decided_at',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_decision','idx_wms_allocation_decision_unit',1,1,'business_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_decision','idx_wms_allocation_decision_unit',1,2,'business_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_decision','idx_wms_allocation_decision_unit',1,3,'allocation_unit_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_decision','idx_wms_allocation_decision_unit',1,4,'round_no',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_decision','PRIMARY',0,1,'decision_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_decision','uk_wms_allocation_decision_effect',0,1,'effect_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_decision','uk_wms_allocation_decision_instance',0,1,'plan_instance_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_decision','uk_wms_allocation_decision_task',0,1,'business_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_decision','uk_wms_allocation_decision_task',0,2,'task_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_lock','idx_wms_allocation_lock_business',1,1,'business_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_lock','idx_wms_allocation_lock_business',1,2,'business_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_lock','idx_wms_allocation_lock_business',1,3,'business_line_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_allocation_lock','idx_wms_allocation_lock_channel',1,1,'channel_lock_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_allocation_lock','idx_wms_allocation_lock_decision',1,1,'decision_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_lock','idx_wms_allocation_lock_decision',1,2,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_lock','idx_wms_allocation_lock_storage',1,1,'storage_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_lock','idx_wms_allocation_lock_storage',1,2,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_lock','idx_wms_allocation_lock_storage',1,3,'resource_domain',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_lock','idx_wms_allocation_lock_task',1,1,'business_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_lock','idx_wms_allocation_lock_task',1,2,'task_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_lock','PRIMARY',0,1,'allocation_lock_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_lock','uk_wms_allocation_lock_active_share',0,1,'active_share_key',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_allocation_lock','uk_wms_allocation_lock_candidate_share',0,1,'candidate_share_key',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_allocation_lock','uk_wms_allocation_lock_effect',0,1,'effect_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_lock','uk_wms_allocation_lock_terminal_effect',0,1,'terminal_effect_key',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_allocation_lock','uk_wms_allocation_lock_terminal_release',0,1,'terminal_release_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_allocation_release','idx_wms_allocation_release_business',1,1,'business_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_release','idx_wms_allocation_release_business',1,2,'business_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_release','idx_wms_allocation_release_business',1,3,'business_line_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_allocation_release','idx_wms_allocation_release_channel',1,1,'channel_lock_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_allocation_release','idx_wms_allocation_release_task',1,1,'business_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_release','idx_wms_allocation_release_task',1,2,'task_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_release','idx_wms_allocation_release_time',1,1,'occurred_at',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_release','idx_wms_allocation_release_time',1,2,'release_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_release','PRIMARY',0,1,'release_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_release','uk_wms_allocation_release_effect',0,1,'effect_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_allocation_release','uk_wms_allocation_release_lock',0,1,'allocation_lock_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_inventory_reservation','idx_wms_inventory_reservation_business',1,1,'business_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_inventory_reservation','idx_wms_inventory_reservation_business',1,2,'business_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_inventory_reservation','idx_wms_inventory_reservation_business',1,3,'business_line_id',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_inventory_reservation','idx_wms_inventory_reservation_inventory',1,1,'inventory_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_inventory_reservation','idx_wms_inventory_reservation_inventory',1,2,'status',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_inventory_reservation','PRIMARY',0,1,'reservation_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_inventory_reservation','uk_wms_inventory_reservation_active_task',0,1,'business_type',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_inventory_reservation','uk_wms_inventory_reservation_active_task',0,2,'task_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_inventory_reservation','uk_wms_inventory_reservation_active_task',0,3,'inventory_id',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_inventory_reservation','uk_wms_inventory_reservation_active_task',0,4,'active_marker',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_inventory_reservation','uk_wms_inventory_reservation_effect',0,1,'effect_key',NULL,NULL,'','BTREE','A',NULL,'YES',''),
('wms_inventory_reservation','uk_wms_inventory_reservation_terminal_effect',0,1,'terminal_effect_key',NULL,NULL,'YES','BTREE','A',NULL,'YES',''),
('wms_inventory_reservation','uk_wms_inventory_reservation_terminal_release',0,1,'terminal_release_id',NULL,NULL,'YES','BTREE','A',NULL,'YES','');

INSERT INTO tmp_iws_p5_expected_checks
    (table_name, constraint_name, check_clause, enforced)
VALUES
('wms_access_channel_lock','ck_wms_access_channel_lock_active','(((`status` = _utf8mb4\\''LOCKED\\'') and (`active_channel_key` is not null) and (`terminal_effect_key` is null) and (`terminal_release_id` is null) and (`consumption_trace_id` is null) and (`terminal_at` is null) and (`effect_result` in (_utf8mb4\\''NONE\\'',_utf8mb4\\''SEND_INTENT\\'',_utf8mb4\\''ACCEPTED\\'',_utf8mb4\\''UNKNOWN\\''))) or ((`status` = _utf8mb4\\''CONSUMED\\'') and (`active_channel_key` is null) and (`terminal_effect_key` is not null) and (`consumption_trace_id` is not null) and (`terminal_release_id` is null) and (`terminal_at` is not null) and (`effect_result` = _utf8mb4\\''CONSUMED\\'')) or ((`status` = _utf8mb4\\''RELEASED\\'') and (`active_channel_key` is null) and (`terminal_effect_key` is not null) and (`terminal_release_id` is not null) and (`consumption_trace_id` is null) and (`terminal_at` is not null) and (`effect_result` = _utf8mb4\\''NO_EFFECT\\'')))','YES'),
('wms_access_channel_lock','ck_wms_access_channel_lock_effect','regexp_like(`lock_effect_key`,_utf8mb4\\''^WMS:[0-9a-f]{64}$\\'',_utf8mb4\\''c\\'')','YES'),
('wms_access_channel_lock','ck_wms_access_channel_lock_evidence_revision','(`evidence_revision` >= 0)','YES'),
('wms_access_channel_lock','ck_wms_access_channel_lock_path_json','(json_type(`path_member_ids_json`) = _utf8mb4\\''ARRAY\\'')','YES'),
('wms_access_channel_lock','ck_wms_access_channel_lock_path_revision','(`path_revision` >= 1)','YES'),
('wms_access_channel_lock','ck_wms_access_channel_lock_phase','(`phase` in (_utf8mb4\\''LOCK\\'',_utf8mb4\\''RELEASE\\''))','YES'),
('wms_access_channel_lock','ck_wms_access_channel_lock_result','(`effect_result` in (_utf8mb4\\''NONE\\'',_utf8mb4\\''SEND_INTENT\\'',_utf8mb4\\''ACCEPTED\\'',_utf8mb4\\''UNKNOWN\\'',_utf8mb4\\''CONSUMED\\'',_utf8mb4\\''NO_EFFECT\\''))','YES'),
('wms_access_channel_lock','ck_wms_access_channel_lock_revision','(`revision` >= 1)','YES'),
('wms_access_channel_lock','ck_wms_access_channel_lock_round','(`round_no` >= 1)','YES'),
('wms_access_channel_lock','ck_wms_access_channel_lock_status','(`status` in (_utf8mb4\\''LOCKED\\'',_utf8mb4\\''CONSUMED\\'',_utf8mb4\\''RELEASED\\''))','YES'),
('wms_access_channel_lock','ck_wms_access_channel_lock_terminal_effect','((`terminal_effect_key` is null) or regexp_like(`terminal_effect_key`,_utf8mb4\\''^WMS:[0-9a-f]{64}$\\'',_utf8mb4\\''c\\''))','YES'),
('wms_allocation_candidate','ck_wms_allocation_candidate_eligible','(`eligible_flag` in (0,1))','YES'),
('wms_allocation_candidate','ck_wms_allocation_candidate_rank','(`rank_no` >= 1)','YES'),
('wms_allocation_candidate','ck_wms_allocation_candidate_residual_json','(json_type(`residual_vector_json`) = _utf8mb4\\''OBJECT\\'')','YES'),
('wms_allocation_candidate','ck_wms_allocation_candidate_selected','(`selected_flag` in (0,1))','YES'),
('wms_allocation_candidate','ck_wms_allocation_candidate_selected_eligible','((`selected_flag` = 0) or ((`eligible_flag` = 1) and (`block_reason` is null)))','YES'),
('wms_allocation_candidate','ck_wms_allocation_candidate_sort_json','(json_type(`sort_key_json`) = _utf8mb4\\''ARRAY\\'')','YES'),
('wms_allocation_decision','ck_wms_allocation_decision_capacity_hash','regexp_like(`capacity_snapshot_hash`,_utf8mb4\\''^[0-9a-f]{64}$\\'',_utf8mb4\\''c\\'')','YES'),
('wms_allocation_decision','ck_wms_allocation_decision_effect','regexp_like(`effect_key`,_utf8mb4\\''^WMS:[0-9a-f]{64}$\\'',_utf8mb4\\''c\\'')','YES'),
('wms_allocation_decision','ck_wms_allocation_decision_input_hash','regexp_like(`input_hash`,_utf8mb4\\''^[0-9a-f]{64}$\\'',_utf8mb4\\''c\\'')','YES'),
('wms_allocation_decision','ck_wms_allocation_decision_phase','(`phase` in (_utf8mb4\\''LOCK\\'',_utf8mb4\\''RELEASE\\''))','YES'),
('wms_allocation_decision','ck_wms_allocation_decision_result','(`result` in (_utf8mb4\\''EVALUATED\\'',_utf8mb4\\''LOCKED\\'',_utf8mb4\\''NO_CANDIDATE\\'',_utf8mb4\\''BLOCKED\\''))','YES'),
('wms_allocation_decision','ck_wms_allocation_decision_revision','(`revision` >= 1)','YES'),
('wms_allocation_decision','ck_wms_allocation_decision_round','(`round_no` >= 1)','YES'),
('wms_allocation_decision','ck_wms_allocation_decision_space_hash','regexp_like(`space_snapshot_hash`,_utf8mb4\\''^[0-9a-f]{64}$\\'',_utf8mb4\\''c\\'')','YES'),
('wms_allocation_decision','ck_wms_allocation_decision_summary','(json_type(`candidate_summary_json`) = _utf8mb4\\''OBJECT\\'')','YES'),
('wms_allocation_lock','ck_wms_allocation_lock_active_share','(((`status` = _utf8mb4\\''LOCKED\\'') and (`active_share_key` is not null) and (`terminal_effect_key` is null) and (`terminal_release_id` is null) and (`consumption_trace_id` is null) and (`terminal_at` is null) and (`effect_result` in (_utf8mb4\\''NONE\\'',_utf8mb4\\''SEND_INTENT\\'',_utf8mb4\\''ACCEPTED\\'',_utf8mb4\\''UNKNOWN\\''))) or ((`status` = _utf8mb4\\''CONSUMED\\'') and (`active_share_key` is null) and (`terminal_effect_key` is not null) and (`consumption_trace_id` is not null) and (`terminal_release_id` is null) and (`terminal_at` is not null) and (`effect_result` = _utf8mb4\\''CONSUMED\\'')) or ((`status` = _utf8mb4\\''RELEASED\\'') and (`active_share_key` is null) and (`terminal_effect_key` is not null) and (`terminal_release_id` is not null) and (`consumption_trace_id` is null) and (`terminal_at` is not null) and (`effect_result` = _utf8mb4\\''NO_EFFECT\\'')))','YES'),
('wms_allocation_lock','ck_wms_allocation_lock_domain','(`resource_domain` in (_utf8mb4\\''STORAGE\\'',_utf8mb4\\''CHANNEL\\''))','YES'),
('wms_allocation_lock','ck_wms_allocation_lock_domain_ref','(((`resource_domain` = _utf8mb4\\''CHANNEL\\'') and (`channel_lock_id` is not null)) or ((`resource_domain` = _utf8mb4\\''STORAGE\\'') and (`channel_lock_id` is null)))','YES'),
('wms_allocation_lock','ck_wms_allocation_lock_effect','regexp_like(`effect_key`,_utf8mb4\\''^WMS:[0-9a-f]{64}$\\'',_utf8mb4\\''c\\'')','YES'),
('wms_allocation_lock','ck_wms_allocation_lock_evidence_revision','(`evidence_revision` >= 0)','YES'),
('wms_allocation_lock','ck_wms_allocation_lock_kind','(`lock_kind` in (_utf8mb4\\''CAPACITY_SHARE\\'',_utf8mb4\\''ENTITY_SLOT\\''))','YES'),
('wms_allocation_lock','ck_wms_allocation_lock_phase','(`phase` in (_utf8mb4\\''LOCK\\'',_utf8mb4\\''RELEASE\\''))','YES'),
('wms_allocation_lock','ck_wms_allocation_lock_result','(`effect_result` in (_utf8mb4\\''NONE\\'',_utf8mb4\\''SEND_INTENT\\'',_utf8mb4\\''ACCEPTED\\'',_utf8mb4\\''UNKNOWN\\'',_utf8mb4\\''CONSUMED\\'',_utf8mb4\\''NO_EFFECT\\''))','YES'),
('wms_allocation_lock','ck_wms_allocation_lock_revision','(`revision` >= 1)','YES'),
('wms_allocation_lock','ck_wms_allocation_lock_round','(`round_no` >= 1)','YES'),
('wms_allocation_lock','ck_wms_allocation_lock_share_shape','(((`lock_kind` = _utf8mb4\\''CAPACITY_SHARE\\'') and (`dimension_key` is not null) and (`unit` is not null) and (`locked_qty` is not null) and (`locked_qty` > 0) and (`slot_key` is null)) or ((`lock_kind` = _utf8mb4\\''ENTITY_SLOT\\'') and (`dimension_key` is null) and (`unit` is null) and (`locked_qty` is null) and (`slot_key` is not null)))','YES'),
('wms_allocation_lock','ck_wms_allocation_lock_status','(`status` in (_utf8mb4\\''LOCKED\\'',_utf8mb4\\''CONSUMED\\'',_utf8mb4\\''RELEASED\\''))','YES'),
('wms_allocation_lock','ck_wms_allocation_lock_terminal_effect','((`terminal_effect_key` is null) or regexp_like(`terminal_effect_key`,_utf8mb4\\''^WMS:[0-9a-f]{64}$\\'',_utf8mb4\\''c\\''))','YES'),
('wms_allocation_release','ck_wms_allocation_release_after_status','(`after_status` = _utf8mb4\\''RELEASED\\'')','YES'),
('wms_allocation_release','ck_wms_allocation_release_before_status','(`before_status` = _utf8mb4\\''LOCKED\\'')','YES'),
('wms_allocation_release','ck_wms_allocation_release_effect','regexp_like(`effect_key`,_utf8mb4\\''^WMS:[0-9a-f]{64}$\\'',_utf8mb4\\''c\\'')','YES'),
('wms_allocation_release','ck_wms_allocation_release_evidence_revision','(`evidence_revision` >= 0)','YES'),
('wms_allocation_release','ck_wms_allocation_release_phase','(`phase` = _utf8mb4\\''RELEASE\\'')','YES'),
('wms_allocation_release','ck_wms_allocation_release_round','(`round_no` >= 1)','YES'),
('wms_inventory_reservation','ck_wms_inventory_reservation_active','(((`status` = _utf8mb4\\''ACTIVE\\'') and (`active_marker` is not null) and (`terminal_effect_key` is null) and (`terminal_release_id` is null) and (`consumption_trace_id` is null) and (`terminal_at` is null) and (`effect_result` in (_utf8mb4\\''NONE\\'',_utf8mb4\\''SEND_INTENT\\'',_utf8mb4\\''ACCEPTED\\'',_utf8mb4\\''UNKNOWN\\''))) or ((`status` = _utf8mb4\\''CONSUMED\\'') and (`active_marker` is null) and (`terminal_effect_key` is not null) and (`consumption_trace_id` is not null) and (`terminal_release_id` is null) and (`terminal_at` is not null) and (`effect_result` = _utf8mb4\\''CONSUMED\\'')) or ((`status` = _utf8mb4\\''RELEASED\\'') and (`active_marker` is null) and (`terminal_effect_key` is not null) and (`terminal_release_id` is not null) and (`consumption_trace_id` is null) and (`terminal_at` is not null) and (`effect_result` = _utf8mb4\\''NO_EFFECT\\'')))','YES'),
('wms_inventory_reservation','ck_wms_inventory_reservation_effect','regexp_like(`effect_key`,_utf8mb4\\''^WMS:[0-9a-f]{64}$\\'',_utf8mb4\\''c\\'')','YES'),
('wms_inventory_reservation','ck_wms_inventory_reservation_evidence_revision','(`evidence_revision` >= 0)','YES'),
('wms_inventory_reservation','ck_wms_inventory_reservation_phase','(`phase` in (_utf8mb4\\''LOCK\\'',_utf8mb4\\''RELEASE\\''))','YES'),
('wms_inventory_reservation','ck_wms_inventory_reservation_qty','(`reserved_qty` > 0)','YES'),
('wms_inventory_reservation','ck_wms_inventory_reservation_result','(`effect_result` in (_utf8mb4\\''NONE\\'',_utf8mb4\\''SEND_INTENT\\'',_utf8mb4\\''ACCEPTED\\'',_utf8mb4\\''UNKNOWN\\'',_utf8mb4\\''CONSUMED\\'',_utf8mb4\\''NO_EFFECT\\''))','YES'),
('wms_inventory_reservation','ck_wms_inventory_reservation_revision','(`revision` >= 1)','YES'),
('wms_inventory_reservation','ck_wms_inventory_reservation_round','(`round_no` >= 1)','YES'),
('wms_inventory_reservation','ck_wms_inventory_reservation_status','(`status` in (_utf8mb4\\''ACTIVE\\'',_utf8mb4\\''CONSUMED\\'',_utf8mb4\\''RELEASED\\''))','YES'),
('wms_inventory_reservation','ck_wms_inventory_reservation_terminal_effect','((`terminal_effect_key` is null) or regexp_like(`terminal_effect_key`,_utf8mb4\\''^WMS:[0-9a-f]{64}$\\'',_utf8mb4\\''c\\''))','YES');

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
DROP TEMPORARY TABLE IF EXISTS tmp_iws_p5_actual_tables;
CREATE TEMPORARY TABLE tmp_iws_p5_actual_tables AS
SELECT TABLE_NAME AS table_name, TABLE_TYPE AS table_type, ENGINE AS engine,
       TABLE_COLLATION AS table_collation, CREATE_OPTIONS AS create_options,
       TABLE_COMMENT AS table_comment
FROM information_schema.TABLES
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_p5_actual_columns;
CREATE TEMPORARY TABLE tmp_iws_p5_actual_columns AS
SELECT TABLE_NAME AS table_name, ORDINAL_POSITION AS ordinal_position,
       COLUMN_NAME AS column_name, COLUMN_TYPE AS column_type,
       IS_NULLABLE AS is_nullable, COLUMN_DEFAULT AS column_default,
       EXTRA AS extra, CHARACTER_SET_NAME AS character_set_name,
       COLLATION_NAME AS collation_name, GENERATION_EXPRESSION AS generation_expression,
       COLUMN_COMMENT AS column_comment
FROM information_schema.COLUMNS
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_p5_actual_indexes;
CREATE TEMPORARY TABLE tmp_iws_p5_actual_indexes AS
SELECT TABLE_NAME AS table_name, INDEX_NAME AS index_name,
       NON_UNIQUE AS non_unique, SEQ_IN_INDEX AS seq_in_index,
       COLUMN_NAME AS column_name, SUB_PART AS sub_part, PACKED AS packed,
       NULLABLE AS nullable, INDEX_TYPE AS index_type, COLLATION AS index_collation,
       EXPRESSION AS index_expression, IS_VISIBLE AS is_visible,
       INDEX_COMMENT AS index_comment
FROM information_schema.STATISTICS
WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
  AND FIND_IN_SET(CAST(TABLE_NAME AS BINARY), CAST(@target_table_names AS BINARY)) > 0;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_p5_actual_checks;
CREATE TEMPORARY TABLE tmp_iws_p5_actual_checks AS
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
    SELECT COUNT(*) FROM tmp_iws_p5_actual_tables WHERE table_type = 'BASE TABLE'
);
SET @post_object_conflict_count = (
    SELECT COUNT(*) FROM tmp_iws_p5_actual_tables WHERE table_type <> 'BASE TABLE'
);
SET @post_case_conflict_count = (
    SELECT COUNT(*)
    FROM information_schema.TABLES a
    JOIN tmp_iws_p5_expected_tables e
      ON LOWER(a.TABLE_NAME) = LOWER(e.table_name)
    WHERE CAST(a.TABLE_SCHEMA AS BINARY) = CAST(@target_schema AS BINARY)
      AND CAST(a.TABLE_NAME AS BINARY) <> CAST(e.table_name AS BINARY)
);
SET @post_table_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_p5_actual_tables a
    LEFT JOIN tmp_iws_p5_expected_tables e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE e.table_name IS NULL
       OR NOT (CAST(a.table_type AS BINARY) <=> CAST(e.table_type AS BINARY))
       OR NOT (CAST(a.engine AS BINARY) <=> CAST(e.engine AS BINARY))
       OR NOT (CAST(a.table_collation AS BINARY) <=> CAST(e.table_collation AS BINARY))
       OR NOT (CAST(a.create_options AS BINARY) <=> CAST(e.create_options AS BINARY))
       OR NOT (CAST(a.table_comment AS BINARY) <=> CAST(e.table_comment AS BINARY))
);
SET @post_table_expected_missing = (
    SELECT COUNT(*) FROM tmp_iws_p5_expected_tables e
    LEFT JOIN tmp_iws_p5_actual_tables a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
    WHERE a.table_name IS NULL
);
SET @post_column_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_p5_actual_columns a
    LEFT JOIN tmp_iws_p5_expected_columns e
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
    SELECT COUNT(*) FROM tmp_iws_p5_expected_columns e
    LEFT JOIN tmp_iws_p5_actual_columns a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.column_name AS BINARY) = CAST(e.column_name AS BINARY)
    WHERE a.column_name IS NULL
);
SET @post_index_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_p5_actual_indexes a
    LEFT JOIN tmp_iws_p5_expected_indexes e
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
    SELECT COUNT(*) FROM tmp_iws_p5_expected_indexes e
    LEFT JOIN tmp_iws_p5_actual_indexes a
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.index_name AS BINARY) = CAST(e.index_name AS BINARY)
     AND a.seq_in_index = e.seq_in_index
    WHERE a.index_name IS NULL
);
SET @post_check_actual_extra = (
    SELECT COUNT(*) FROM tmp_iws_p5_actual_checks a
    LEFT JOIN tmp_iws_p5_expected_checks e
      ON CAST(a.table_name AS BINARY) = CAST(e.table_name AS BINARY)
     AND CAST(a.constraint_name AS BINARY) = CAST(e.constraint_name AS BINARY)
    WHERE e.constraint_name IS NULL
       OR NOT (CAST(a.check_clause AS BINARY) <=> CAST(e.check_clause AS BINARY))
       OR NOT (CAST(a.enforced AS BINARY) <=> CAST(e.enforced AS BINARY))
);
SET @post_check_expected_missing = (
    SELECT COUNT(*) FROM tmp_iws_p5_expected_checks e
    LEFT JOIN tmp_iws_p5_actual_checks a
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
SET @expected_table_rows = (SELECT COUNT(*) FROM tmp_iws_p5_expected_tables);
SET @actual_table_rows = (SELECT COUNT(*) FROM tmp_iws_p5_actual_tables);
SET @expected_column_rows = (SELECT COUNT(*) FROM tmp_iws_p5_expected_columns);
SET @actual_column_rows = (SELECT COUNT(*) FROM tmp_iws_p5_actual_columns);
SET @expected_index_rows = (SELECT COUNT(*) FROM tmp_iws_p5_expected_indexes);
SET @actual_index_rows = (SELECT COUNT(*) FROM tmp_iws_p5_actual_indexes);
SET @expected_check_rows = (SELECT COUNT(*) FROM tmp_iws_p5_expected_checks);
SET @actual_check_rows = (SELECT COUNT(*) FROM tmp_iws_p5_actual_checks);
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
    @p5_release_id AS `发布标识`,
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
       'P5 allocation foundation 6表结构验收通过；不回填、不迁移、不宣称 P5 完成',
       'P5 allocation foundation 执行后结构验收失败') AS `执行结果`;
SET @structure_fail_fast = IF(
    @post_structure_ok = 1,
    0,
    CAST('invalid' AS JSON)
);
SELECT IF(@post_structure_ok = 1, 1,
          JSON_EXTRACT(JSON_QUOTE('invalid'), '$')) AS `P5分配foundation验收硬门禁`;

SELECT
    '索引上限核算' AS `检查项`,
    '2243/3072 字节、4/16 列' AS `结果`,
    '基于冻结元数据：全部索引含保守 NULL/长度开销' AS `说明`
UNION ALL SELECT
    '表边界', '6 张且仅本次 6 张',
    '不创建库存/占用/trace/空间/审计/业务头等本切片外对象'
UNION ALL SELECT
    '后续事务边界', '未宣称',
    '锁序/路径可达/容量守恒/UNKNOWN禁止释放/正式计划接入由后续服务与Review验证';

DROP TEMPORARY TABLE IF EXISTS tmp_iws_p5_actual_checks;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_p5_actual_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_p5_actual_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_p5_actual_tables;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_p5_expected_checks;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_p5_expected_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_p5_expected_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_p5_expected_tables;
SET @post_cleanup_ok = IF(@post_structure_ok = 1, 1, @structure_fail_fast);
SELECT @post_cleanup_ok AS `P5分配foundation验收收尾门禁`;
