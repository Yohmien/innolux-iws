-- ============================================================================
-- IWS+ 在库空间 SQL S1 首切片幂等升级（INV-P2-01-S1）
-- ============================================================================
-- 目标数据库：MySQL 8.0；已在 MySQL 8.0.45 隔离库验证
-- 目标对象：
--   wms_warehouse 新建，作为公司 -> 仓库 -> 库区 -> 储位的稳定仓库父节点；
--   wms_warehouse_zone 新增可空 warehouse_id；
--   wms_storage 新增可空 warehouse_id 与 aisle_text/row_text/level_text/
--   column_text/depth_text 五个可空文本地址列。
-- 本切片边界：
--   只新增可空列和支持索引。不回填旧行，不收紧 NOT NULL，不创建生成列、
--   CHECK 或储位五项地址唯一键；这些均属于 S3/S4，不能由本脚本宣称完成。
--   不修改旧数值地址列、旧库区字段、旧容器唯一索引或任何业务数据。
-- 幂等与恢复：
--   每次执行从 INFORMATION_SCHEMA 读取对象状态；完全缺失则创建，完全匹配则跳过，
--   已有对象定义不符则在该对象 DDL 前阻断。DDL 提交后即使会话 checkpoint 丢失，
--   后续重跑也会按元数据识别并继续，不重复 ALTER，不删表重建。
--   多语句 DDL 不承诺整体回滚；首个 SQL 错误必须停止并保留现场。
-- 前置：
--   先完成既有公司 SQL 切片，wms_company 必须是当前目标结构。
--   旧库区/储位必须匹配 S1 前 20 表 WMS 基线中的对应表定义，允许 S1 目标对象
--   已部分或全部存在的中断态。库区父级缺失/成环、跨 warehouse_code 父链冲突、
--   旧地址候选重复和无仓库来源只作只读阻碍报告，不伪造回填结果，也不阻断
--   本切片新增可空结构；这些阻碍会明确标记 S3 未完成。
--   受影响表不得带有 S1 未声明的 CHECK、FOREIGN KEY 或触发器；检测到即在前置
--   阶段失败，不执行任何业务 DDL。S1 新列已有值、已收紧 NOT NULL 或存在孤儿
--   仓库时，说明库已进入 S3 阶段或数据不一致，本脚本同样在写入前拒绝，避免把
--   后续阶段或冲突状态报告为 S1 成功。
-- 执行：
--   mysql --no-defaults --protocol=TCP --host=<主机> --port=<端口> --user=<账号> \
--     --default-character-set=utf8mb4 --database=<目标库> --batch --raw \
--     < script/sql/iws_inventory_management_space_s1_upgrade.sql
--   禁止 --force；必须同一连接串行执行。MySQL 8.0.45 客户端通过 stdin 在首个 SQL
--   错误处终止并返回非 0。任意 GUI 或其他自行续错的执行器不在支持范围。
-- 权限：
--   部署账号需要 SELECT（information_schema）、CREATE、CREATE TEMPORARY TABLES、
--   ALTER、INDEX 权限。本脚本不创建存储过程、触发器和事件。
-- 参数：
--   目标库和发布版本集中在下一节；执行前只改 @target_schema。
-- ============================================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 集中参数
-- ----------------------------------------------------------------------------
SET @target_schema = 'innolux-iws';
SET @s1_release_id = 'INV-P2-01-S1-20261027';
SET @s1_baseline_id = 'WMS-CORE-20-PRE-S1-20261027';

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

-- ----------------------------------------------------------------------------
-- 一、目标库、版本与公司前置
-- ----------------------------------------------------------------------------
SET @schema_ok = (
    DATABASE() IS NOT NULL
    AND CAST(DATABASE() AS BINARY) = CAST(@target_schema AS BINARY)
);
SET @version_ok = (VERSION() LIKE '8.%');

SET @company_table_ok = (
    SELECT COUNT(*) = 1
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_company'
      AND TABLE_TYPE = 'BASE TABLE'
      AND ENGINE = 'InnoDB'
      AND TABLE_COLLATION = 'utf8mb4_0900_ai_ci'
      AND CAST(TABLE_COMMENT AS BINARY) = CAST('WMS公司主档' AS BINARY)
);
SET @company_column_ok = (
    SELECT COUNT(*) = 12
       AND SHA2(CONCAT('[',
           GROUP_CONCAT(
               CAST(JSON_ARRAY(
                   ORDINAL_POSITION, COLUMN_NAME, LOWER(COLUMN_TYPE),
                   IS_NULLABLE, COLUMN_DEFAULT, CHARACTER_SET_NAME,
                   COLLATION_NAME, EXTRA, GENERATION_EXPRESSION, COLUMN_COMMENT
               ) AS CHAR CHARACTER SET utf8mb4)
               ORDER BY ORDINAL_POSITION SEPARATOR ','
           ),
       ']'), 256) = '6f3c1bd1a895dea71774fb94ebee42f1b89cc67418a7fd8a3fb173b42bdb5265'
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_company'
);
SET @company_index_ok = (
    SELECT COUNT(DISTINCT INDEX_NAME) = 3
       AND SHA2(CONCAT('[',
           GROUP_CONCAT(
               CAST(JSON_ARRAY(
                   INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME,
                   SUB_PART, INDEX_TYPE, COLLATION, EXPRESSION,
                   IS_VISIBLE, INDEX_COMMENT
               ) AS CHAR CHARACTER SET utf8mb4)
               ORDER BY INDEX_NAME, SEQ_IN_INDEX SEPARATOR ','
           ),
       ']'), 256) = '864506f2624b557c109137c1674978a48c990d7810d0507bc91a4aef1d8e7921'
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_company'
);
SET @company_ok = (@company_table_ok = 1 AND @company_column_ok = 1 AND @company_index_ok = 1);

-- ----------------------------------------------------------------------------
-- 二、旧库区/储位只读结构预检
-- ----------------------------------------------------------------------------
-- 列签名覆盖顺序、名称、完整类型、空值、默认值、字符集、排序规则、额外属性、
-- 生成表达式和注释。先校验 S1 前基线，随后逐一校验六个新增可空对象。
-- 每个元数据行由 JSON_ARRAY 编码后组成 JSON 数组，保留 SQL NULL 和全部字符，
-- 不使用 IFNULL sentinel、CONCAT_WS 或 GROUP_CONCAT SEPARATOR 拼接。
SET @zone_base_columns_ok = (
    SELECT COUNT(*) = 18
       AND SHA2(CONCAT('[',
           GROUP_CONCAT(
               CAST(JSON_ARRAY(
                   ORDINAL_POSITION, COLUMN_NAME, LOWER(COLUMN_TYPE),
                   IS_NULLABLE, COLUMN_DEFAULT, CHARACTER_SET_NAME,
                   COLLATION_NAME, EXTRA, GENERATION_EXPRESSION, COLUMN_COMMENT
               ) AS CHAR CHARACTER SET utf8mb4)
               ORDER BY ORDINAL_POSITION SEPARATOR ','
           ),
       ']'), 256) = '1c5e59e1e8995c94a860b43ab0f357739df434aa56f7884fdec9f4b5a580edcd'
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_warehouse_zone'
      AND COLUMN_NAME NOT IN ('warehouse_id')
);
SET @storage_base_columns_ok = (
    SELECT COUNT(*) = 24
       AND SHA2(CONCAT('[',
           GROUP_CONCAT(
               CAST(JSON_ARRAY(
                   ORDINAL_POSITION, COLUMN_NAME, LOWER(COLUMN_TYPE),
                   IS_NULLABLE, COLUMN_DEFAULT, CHARACTER_SET_NAME,
                   COLLATION_NAME, EXTRA, GENERATION_EXPRESSION, COLUMN_COMMENT
               ) AS CHAR CHARACTER SET utf8mb4)
               ORDER BY ORDINAL_POSITION SEPARATOR ','
           ),
       ']'), 256) = '4a86df18718fd0c4abc5cc76d6e92d7a290b652ca15e354bc9d97cfa9079e107'
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_storage'
      AND COLUMN_NAME NOT IN (
          'warehouse_id', 'aisle_text', 'row_text', 'level_text',
          'column_text', 'depth_text'
      )
);
SET @zone_base_indexes_ok = (
    SELECT COUNT(DISTINCT INDEX_NAME) = 5
       AND SHA2(CONCAT('[',
           GROUP_CONCAT(
               CAST(JSON_ARRAY(
                   INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME,
                   SUB_PART, INDEX_TYPE, COLLATION, EXPRESSION,
                   IS_VISIBLE, INDEX_COMMENT
               ) AS CHAR CHARACTER SET utf8mb4)
               ORDER BY INDEX_NAME, SEQ_IN_INDEX SEPARATOR ','
           ),
       ']'), 256) = '6fed6a8f4fe084ce1e2ad5ed26e3b7e3fe727cce9ccfcd73d3d54def506f4eac'
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_warehouse_zone'
      AND INDEX_NAME NOT IN ('idx_wms_zone_warehouse_status')
);
SET @storage_base_indexes_ok = (
    SELECT COUNT(DISTINCT INDEX_NAME) = 6
       AND SHA2(CONCAT('[',
           GROUP_CONCAT(
               CAST(JSON_ARRAY(
                   INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME,
                   SUB_PART, INDEX_TYPE, COLLATION, EXPRESSION,
                   IS_VISIBLE, INDEX_COMMENT
               ) AS CHAR CHARACTER SET utf8mb4)
               ORDER BY INDEX_NAME, SEQ_IN_INDEX SEPARATOR ','
           ),
       ']'), 256) = '6b934a1c79b03dab2a5dc7210737dc68ec874bb07843ebb8e96bcc38d16fd9c3'
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_storage'
      AND INDEX_NAME NOT IN ('idx_wms_storage_warehouse_status')
);
SET @base_options_ok = (
    SELECT COUNT(*) = 2
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND (
          (TABLE_NAME = 'wms_warehouse_zone'
           AND TABLE_TYPE = 'BASE TABLE'
           AND ENGINE = 'InnoDB'
           AND TABLE_COLLATION = 'utf8mb4_0900_ai_ci'
           AND CAST(TABLE_COMMENT AS BINARY) = CAST('WMS库区表' AS BINARY))
          OR
          (TABLE_NAME = 'wms_storage'
           AND TABLE_TYPE = 'BASE TABLE'
           AND ENGINE = 'InnoDB'
           AND TABLE_COLLATION = 'utf8mb4_0900_ai_ci'
           AND CAST(TABLE_COMMENT AS BINARY) = CAST('WMS储位表' AS BINARY))
      )
);
SET @zone_extra_column_count = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_warehouse_zone'
      AND COLUMN_NAME NOT IN (
          'zone_id', 'zone_code', 'zone_name', 'zone_type', 'plant_code',
          'warehouse_code', 'parent_zone_id', 'allow_inbound', 'allow_outbound',
          'status', 'sort_no', 'del_flag', 'create_dept', 'create_by',
          'create_time', 'update_by', 'update_time', 'remark', 'warehouse_id'
      )
);
SET @storage_extra_column_count = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_storage'
      AND COLUMN_NAME NOT IN (
          'storage_id', 'storage_code', 'zone_id', 'storage_type', 'height_type',
          'height_desc', 'row_no', 'col_no', 'level_no', 'x_coordinate',
          'y_coordinate', 'port_code', 'wcs_point_code', 'status', 'lock_status',
          'disable_status', 'version', 'del_flag', 'create_dept', 'create_by',
          'create_time', 'update_by', 'update_time', 'remark', 'warehouse_id',
          'aisle_text', 'row_text', 'level_text', 'column_text', 'depth_text'
      )
);
SET @zone_extra_index_count = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_warehouse_zone'
      AND INDEX_NAME NOT IN (
          'PRIMARY', 'uk_wms_zone_code', 'idx_wms_zone_plant_wh_status',
          'idx_wms_zone_type_status', 'idx_wms_zone_parent',
          'idx_wms_zone_warehouse_status'
      )
);
SET @storage_extra_index_count = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_storage'
      AND INDEX_NAME NOT IN (
          'PRIMARY', 'uk_wms_storage_code', 'idx_wms_storage_zone_status',
          'idx_wms_storage_matrix', 'idx_wms_storage_port',
          'idx_wms_storage_wcs_point', 'idx_wms_storage_warehouse_status'
      )
);
SET @base_manifest_ok = (
    @zone_base_columns_ok = 1
    AND @storage_base_columns_ok = 1
    AND @zone_base_indexes_ok = 1
    AND @storage_base_indexes_ok = 1
    AND @base_options_ok = 1
    AND @zone_extra_column_count = 0
    AND @storage_extra_column_count = 0
    AND @zone_extra_index_count = 0
    AND @storage_extra_index_count = 0
);

-- ----------------------------------------------------------------------------
-- 三、六个新增可空列与三个索引的对象状态
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s1_col;
CREATE TEMPORARY TABLE tmp_iws_s1_col (
    table_name      varchar(64)  NOT NULL,
    ordinal_position int         NOT NULL,
    column_name     varchar(64)  NOT NULL,
    column_type     varchar(64)  NOT NULL,
    is_nullable     char(3)      NOT NULL,
    column_default  varchar(32)  DEFAULT NULL,
    collation_name  varchar(64)  DEFAULT NULL,
    column_comment  varchar(255) NOT NULL,
    action          varchar(16)  NOT NULL,
    PRIMARY KEY (table_name, column_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO tmp_iws_s1_col
    (table_name, ordinal_position, column_name, column_type, is_nullable,
     column_default, collation_name, column_comment, action)
VALUES
    ('wms_warehouse_zone', 19, 'warehouse_id', 'bigint', 'YES', NULL, NULL,
     '所属仓库主键，逻辑关联 wms_warehouse.warehouse_id；S1暂可空，S3回填后收紧', 'CHECK'),
    ('wms_storage', 25, 'warehouse_id', 'bigint', 'YES', NULL, NULL,
     '所属仓库主键，逻辑关联 wms_warehouse.warehouse_id；S1暂可空，S3回填后收紧', 'CHECK'),
    ('wms_storage', 26, 'aisle_text', 'varchar(64)', 'YES', NULL, 'utf8mb4_0900_bin',
     '巷道文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL', 'CHECK'),
    ('wms_storage', 27, 'row_text', 'varchar(64)', 'YES', NULL, 'utf8mb4_0900_bin',
     '排文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL', 'CHECK'),
    ('wms_storage', 28, 'level_text', 'varchar(64)', 'YES', NULL, 'utf8mb4_0900_bin',
     '层文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL', 'CHECK'),
    ('wms_storage', 29, 'column_text', 'varchar(64)', 'YES', NULL, 'utf8mb4_0900_bin',
     '列文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL', 'CHECK'),
    ('wms_storage', 30, 'depth_text', 'varchar(64)', 'YES', NULL, 'utf8mb4_0900_bin',
     '深文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL', 'CHECK');

UPDATE tmp_iws_s1_col e
LEFT JOIN information_schema.COLUMNS c
    ON c.TABLE_SCHEMA = DATABASE()
   AND c.TABLE_NAME = e.table_name
   AND c.COLUMN_NAME = e.column_name
SET e.action = CASE
    WHEN c.COLUMN_NAME IS NULL THEN 'ADD'
    WHEN c.ORDINAL_POSITION = e.ordinal_position
     AND LOWER(c.COLUMN_TYPE) = LOWER(e.column_type)
     AND c.IS_NULLABLE = e.is_nullable
     AND CAST(c.COLUMN_DEFAULT AS BINARY) <=> CAST(e.column_default AS BINARY)
     AND CAST(c.COLLATION_NAME AS BINARY) <=> CAST(e.collation_name AS BINARY)
     AND CAST(c.CHARACTER_SET_NAME AS BINARY) <=> CAST(
         IF(e.collation_name IS NULL, NULL, 'utf8mb4') AS BINARY)
     AND CAST(c.EXTRA AS BINARY) = CAST('' AS BINARY)
     AND CAST(c.GENERATION_EXPRESSION AS BINARY) = CAST('' AS BINARY)
     AND BINARY c.COLUMN_COMMENT = BINARY e.column_comment
    THEN 'EXISTS'
    ELSE 'CONFLICT'
END;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s1_idx;
CREATE TEMPORARY TABLE tmp_iws_s1_idx (
    table_name  varchar(64) NOT NULL,
    index_name  varchar(64) NOT NULL,
    non_unique  int         NOT NULL,
    seq_in_index int        NOT NULL,
    column_name varchar(64) NOT NULL,
    action      varchar(16) NOT NULL,
    PRIMARY KEY (table_name, index_name, seq_in_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO tmp_iws_s1_idx
    (table_name, index_name, non_unique, seq_in_index, column_name, action)
VALUES
    ('wms_warehouse_zone', 'idx_wms_zone_warehouse_status', 1, 1, 'warehouse_id', 'CHECK'),
    ('wms_warehouse_zone', 'idx_wms_zone_warehouse_status', 1, 2, 'status', 'CHECK'),
    ('wms_storage', 'idx_wms_storage_warehouse_status', 1, 1, 'warehouse_id', 'CHECK'),
    ('wms_storage', 'idx_wms_storage_warehouse_status', 1, 2, 'status', 'CHECK');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s1_idx_expected;
CREATE TEMPORARY TABLE tmp_iws_s1_idx_expected AS
SELECT table_name, index_name, COUNT(*) AS part_count,
       SHA2(CONCAT('[',
           GROUP_CONCAT(
               CAST(JSON_ARRAY(seq_in_index, non_unique, column_name)
                    AS CHAR CHARACTER SET utf8mb4)
               ORDER BY seq_in_index SEPARATOR ','
           ),
       ']'), 256) AS part_hash
FROM tmp_iws_s1_idx
GROUP BY table_name, index_name;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s1_idx_actual;
CREATE TEMPORARY TABLE tmp_iws_s1_idx_actual AS
SELECT TABLE_NAME AS table_name, INDEX_NAME AS index_name,
       COUNT(*) AS part_count,
       SUM(INDEX_TYPE = 'BTREE'
           AND SUB_PART IS NULL
           AND EXPRESSION IS NULL
           AND IS_VISIBLE = 'YES'
           AND COLLATION = 'A') AS valid_part_count,
       SHA2(CONCAT('[',
           GROUP_CONCAT(
               CAST(JSON_ARRAY(SEQ_IN_INDEX, NON_UNIQUE, COLUMN_NAME)
                    AS CHAR CHARACTER SET utf8mb4)
               ORDER BY SEQ_IN_INDEX SEPARATOR ','
           ),
       ']'), 256) AS part_hash
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('wms_warehouse_zone', 'wms_storage')
  AND INDEX_NAME IN (
      'idx_wms_zone_warehouse_status',
      'idx_wms_storage_warehouse_status'
  )
GROUP BY TABLE_NAME, INDEX_NAME;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s1_idx_state;
CREATE TEMPORARY TABLE tmp_iws_s1_idx_state AS
SELECT e.table_name, e.index_name,
       CASE
           WHEN a.index_name IS NULL THEN 'ADD'
           WHEN a.part_count = e.part_count
            AND a.valid_part_count = e.part_count
            AND a.part_hash = e.part_hash THEN 'EXISTS'
           ELSE 'CONFLICT'
       END AS action
FROM tmp_iws_s1_idx_expected e
LEFT JOIN tmp_iws_s1_idx_actual a
  ON a.table_name = e.table_name
 AND a.index_name = e.index_name;

-- 仓库目标表：缺失则创建，完全匹配则跳过，其余情况冲突。
SET @warehouse_exists = (
    SELECT COUNT(*) = 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_warehouse'
      AND TABLE_TYPE = 'BASE TABLE'
      AND ENGINE = 'InnoDB'
      AND TABLE_COLLATION = 'utf8mb4_0900_ai_ci'
      AND CAST(TABLE_COMMENT AS BINARY) = CAST('WMS仓库主表' AS BINARY)
);
SET @warehouse_columns_exact = (
    SELECT COUNT(*) = 15
       AND SHA2(CONCAT('[',
           GROUP_CONCAT(
               CAST(JSON_ARRAY(
                   ORDINAL_POSITION, COLUMN_NAME, LOWER(COLUMN_TYPE),
                   IS_NULLABLE, COLUMN_DEFAULT, CHARACTER_SET_NAME,
                   COLLATION_NAME, EXTRA, GENERATION_EXPRESSION, COLUMN_COMMENT
               ) AS CHAR CHARACTER SET utf8mb4)
               ORDER BY ORDINAL_POSITION SEPARATOR ','
           ),
       ']'), 256) = '2b208463ee2013471c41e5deb24a0efd71b5273bbaf96ddb0c18ac19dfbb5d58'
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_warehouse'
);
SET @warehouse_indexes_exact = (
    SELECT COUNT(DISTINCT INDEX_NAME) = 3
       AND SHA2(CONCAT('[',
           GROUP_CONCAT(
               CAST(JSON_ARRAY(
                   INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME,
                   SUB_PART, INDEX_TYPE, COLLATION, EXPRESSION,
                   IS_VISIBLE, INDEX_COMMENT
               ) AS CHAR CHARACTER SET utf8mb4)
               ORDER BY INDEX_NAME, SEQ_IN_INDEX SEPARATOR ','
           ),
       ']'), 256) = '3a53f0ea4b91671e2c9be36d935202707a1a70102db6b181fba3f4d8f4017e22'
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_warehouse'
);
SET @warehouse_action = CASE
    WHEN NOT EXISTS (
        SELECT 1 FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_warehouse'
    ) THEN 'CREATE'
    WHEN @warehouse_exists = 1
     AND @warehouse_columns_exact = 1
     AND @warehouse_indexes_exact = 1 THEN 'EXISTS'
    ELSE 'CONFLICT'
END;

SET @s1_column_conflict = (
    SELECT COUNT(*) FROM tmp_iws_s1_col WHERE action = 'CONFLICT'
);
SET @s1_column_missing = (
    SELECT COUNT(*) FROM tmp_iws_s1_col WHERE action = 'ADD'
);
SET @s1_index_conflict = (
    SELECT COUNT(*) FROM tmp_iws_s1_idx_state WHERE action = 'CONFLICT'
);
SET @s1_index_missing = (
    SELECT COUNT(*) FROM tmp_iws_s1_idx_state WHERE action = 'ADD'
);
SET @s1_object_conflict = (
    @s1_column_conflict + @s1_index_conflict
    + IF(@warehouse_action = 'CONFLICT', 1, 0)
);

-- S1 未声明任何 CHECK/FK/trigger；受影响表上的额外约束、引用受影响表的外来
-- FK 和触发器都视为未知结构，必须在首条业务 DDL 前拒绝。
SET @affected_table_check_count = (
    SELECT COUNT(*)
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
      AND CONSTRAINT_TYPE = 'CHECK'
);
SET @affected_table_fk_count = (
    SELECT COUNT(*)
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
      AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @affected_inbound_fk_count = (
    SELECT COUNT(*)
    FROM information_schema.REFERENTIAL_CONSTRAINTS
    WHERE UNIQUE_CONSTRAINT_SCHEMA = DATABASE()
      AND REFERENCED_TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
);
SET @affected_trigger_count = (
    SELECT COUNT(*)
    FROM information_schema.TRIGGERS
    WHERE TRIGGER_SCHEMA = DATABASE()
      AND EVENT_OBJECT_TABLE IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
);
SET @affected_unknown_object_count = (
    @affected_table_check_count + @affected_table_fk_count
    + @affected_inbound_fk_count + @affected_trigger_count
);

-- 新列已收紧为 NOT NULL 时，库已进入 S3 结构阶段；S1 只接受七个可空列。
SET @s1_not_null_column_count = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND (
          (TABLE_NAME = 'wms_warehouse_zone' AND COLUMN_NAME = 'warehouse_id')
          OR (TABLE_NAME = 'wms_storage' AND COLUMN_NAME IN (
              'warehouse_id','aisle_text','row_text','level_text','column_text','depth_text'
          ))
      )
      AND IS_NULLABLE = 'NO'
);

-- S1 新列可能尚未全部存在；仅对已存在列动态统计非空值，避免前置查询引用缺失列。
SET @zone_table_exists = (
    SELECT COUNT(*) = 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_warehouse_zone'
      AND TABLE_TYPE = 'BASE TABLE'
);
SET @storage_table_exists = (
    SELECT COUNT(*) = 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_storage'
      AND TABLE_TYPE = 'BASE TABLE'
);
SET @company_table_exists = (
    SELECT COUNT(*) = 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_company'
      AND TABLE_TYPE = 'BASE TABLE'
);
SET @zone_s1_existing_columns = (
    SELECT GROUP_CONCAT(CONCAT('`', COLUMN_NAME, '`')
                        ORDER BY ORDINAL_POSITION SEPARATOR ', ')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_warehouse_zone'
      AND COLUMN_NAME = 'warehouse_id'
);
SET @storage_s1_existing_columns = (
    SELECT GROUP_CONCAT(CONCAT('`', COLUMN_NAME, '`')
                        ORDER BY ORDINAL_POSITION SEPARATOR ', ')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_storage'
      AND COLUMN_NAME IN (
          'warehouse_id','aisle_text','row_text','level_text','column_text','depth_text'
      )
);

SET @zone_data_value_sql = CASE
    WHEN @zone_table_exists = 0 OR @zone_s1_existing_columns IS NULL
        THEN 'SET @zone_warehouse_value_count = 0'
    ELSE CONCAT(
        'SELECT COUNT(*) INTO @zone_warehouse_value_count FROM wms_warehouse_zone WHERE ',
        REPLACE(@zone_s1_existing_columns, ', ', ' IS NOT NULL OR '),
        ' IS NOT NULL'
    )
END;
PREPARE stmt_iws_s1_zone_data FROM @zone_data_value_sql;
EXECUTE stmt_iws_s1_zone_data;
DEALLOCATE PREPARE stmt_iws_s1_zone_data;

SET @storage_data_value_sql = CASE
    WHEN @storage_table_exists = 0 OR @storage_s1_existing_columns IS NULL
        THEN 'SET @s1_data_value_count = 0'
    ELSE CONCAT(
        'SELECT COUNT(*) INTO @s1_data_value_count FROM wms_storage WHERE ',
        REPLACE(@storage_s1_existing_columns, ', ', ' IS NOT NULL OR '),
        ' IS NOT NULL'
    )
END;
PREPARE stmt_iws_s1_storage_data FROM @storage_data_value_sql;
EXECUTE stmt_iws_s1_storage_data;
DEALLOCATE PREPARE stmt_iws_s1_storage_data;

SET @warehouse_orphan_sql = CASE
    WHEN @warehouse_action = 'CREATE' OR @company_table_exists = 0
        THEN 'SET @warehouse_orphan_company_count = 0'
    ELSE 'SELECT COUNT(*) INTO @warehouse_orphan_company_count FROM wms_warehouse w LEFT JOIN wms_company c ON c.company_id = w.company_id WHERE c.company_id IS NULL'
END;
PREPARE stmt_iws_s1_orphan FROM @warehouse_orphan_sql;
EXECUTE stmt_iws_s1_orphan;
DEALLOCATE PREPARE stmt_iws_s1_orphan;

-- ----------------------------------------------------------------------------
-- 四、旧库只读冲突预检：只报告 S3 阻碍，不写业务数据
-- ----------------------------------------------------------------------------
SET @zone_missing_parent_count = (
    SELECT COUNT(*)
    FROM wms_warehouse_zone c
    LEFT JOIN wms_warehouse_zone p ON p.zone_id = c.parent_zone_id
    WHERE c.parent_zone_id IS NOT NULL AND p.zone_id IS NULL
);
SET @zone_cross_warehouse_count = (
    SELECT COUNT(*)
    FROM wms_warehouse_zone c
    JOIN wms_warehouse_zone p ON p.zone_id = c.parent_zone_id
    WHERE c.warehouse_code IS NOT NULL
      AND p.warehouse_code IS NOT NULL
      AND CAST(c.warehouse_code AS BINARY) <> CAST(p.warehouse_code AS BINARY)
);

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s1_cycle;
CREATE TEMPORARY TABLE tmp_iws_s1_cycle AS
WITH RECURSIVE walk (origin_zone_id, current_zone_id, path_text, is_cycle) AS (
    SELECT z.zone_id, z.parent_zone_id,
           CAST(CONCAT(',', z.zone_id, ',') AS CHAR(4000)), 0
    FROM wms_warehouse_zone z
    WHERE z.parent_zone_id IS NOT NULL
    UNION ALL
    SELECT w.origin_zone_id, z.parent_zone_id,
           CONCAT(w.path_text, z.zone_id, ','),
           IF(LOCATE(CONCAT(',', z.parent_zone_id, ','), w.path_text) > 0, 1, 0)
    FROM walk w
    JOIN wms_warehouse_zone z ON z.zone_id = w.current_zone_id
    WHERE w.current_zone_id IS NOT NULL
      AND w.is_cycle = 0
      AND CHAR_LENGTH(w.path_text) < 3900
)
SELECT DISTINCT origin_zone_id FROM walk WHERE is_cycle = 1;
SET @zone_cycle_count = (SELECT COUNT(*) FROM tmp_iws_s1_cycle);

SET @storage_orphan_zone_count = (
    SELECT COUNT(*)
    FROM wms_storage s
    LEFT JOIN wms_warehouse_zone z ON z.zone_id = s.zone_id
    WHERE z.zone_id IS NULL
);
SET @storage_unmapped_warehouse_count = (
    SELECT COUNT(*)
    FROM wms_storage s
    LEFT JOIN wms_warehouse_zone z ON z.zone_id = s.zone_id
    WHERE z.zone_id IS NULL OR z.warehouse_code IS NULL
);
SET @legacy_address_duplicate_group_count = (
    SELECT COUNT(*)
    FROM (
        SELECT IFNULL(CAST(z.warehouse_code AS BINARY), '<NULL>') AS wh_key,
               IFNULL(CAST(s.row_no AS CHAR), '<NULL>') AS row_key,
               IFNULL(CAST(s.col_no AS CHAR), '<NULL>') AS col_key,
               IFNULL(CAST(s.level_no AS CHAR), '<NULL>') AS level_key
        FROM wms_storage s
        LEFT JOIN wms_warehouse_zone z ON z.zone_id = s.zone_id
        GROUP BY wh_key, row_key, col_key, level_key
        HAVING COUNT(*) > 1
    ) d
);
SET @s3_blocker_count = (
    @zone_missing_parent_count + @zone_cross_warehouse_count + @zone_cycle_count
    + @storage_orphan_zone_count + @storage_unmapped_warehouse_count
    + @legacy_address_duplicate_group_count
);

-- ----------------------------------------------------------------------------
-- 五、前置汇总与门禁
-- ----------------------------------------------------------------------------
SET @preflight_ok = (
    @metadata_visibility_ok = 1
    AND @schema_ok = 1
    AND @version_ok = 1
    AND @company_ok = 1
    AND @base_manifest_ok = 1
    AND @s1_object_conflict = 0
    AND @affected_unknown_object_count = 0
    AND @s1_not_null_column_count = 0
    AND @warehouse_orphan_company_count = 0
    AND @zone_warehouse_value_count = 0
    AND @s1_data_value_count = 0
);

SELECT
    @target_schema AS '目标库参数',
    DATABASE() AS '当前库',
    VERSION() AS '数据库版本',
    IF(@metadata_visibility_ok = 1, '通过', '权限不足') AS '元数据权限',
    @s1_release_id AS 'S1发布标识',
    @s1_baseline_id AS 'S1前基线',
    IF(@company_ok = 1, '当前公司目标结构', '缺失或结构冲突') AS '公司前置',
    IF(@base_manifest_ok = 1, '旧区/储位基线匹配', '基线冲突') AS '旧库结构预检',
    @warehouse_action AS '仓库表动作',
    @s1_column_missing AS '待新增可空列数',
    @s1_column_conflict AS '新增列结构冲突数',
    @s1_index_missing AS '待新增索引数',
    @s1_index_conflict AS '新增索引结构冲突数',
    @affected_table_check_count AS '受影响表CHECK数',
    @affected_table_fk_count AS '受影响表FK数',
    @affected_inbound_fk_count AS '引用受影响表FK数',
    @affected_trigger_count AS '受影响表触发器数',
    @s1_not_null_column_count AS 'S1列已收紧NOT NULL数',
    @warehouse_orphan_company_count AS '孤儿仓库数',
    @zone_warehouse_value_count AS '库区warehouse_id已写值数',
    @s1_data_value_count AS '储位S1列已写值数',
    IF(@preflight_ok = 1, '通过',
       '失败：元数据权限、目标库、公司前置、旧库基线、未知约束/触发器、S1对象冲突或数据阶段不匹配') AS '前置结果';
SELECT
    @zone_missing_parent_count AS '旧库区父级缺失数',
    @zone_cycle_count AS '旧库区父级成环数',
    @zone_cross_warehouse_count AS '旧库区跨仓父链数',
    @storage_orphan_zone_count AS '储位孤立区数',
    @storage_unmapped_warehouse_count AS '储位无仓库来源数',
    @legacy_address_duplicate_group_count AS '旧地址候选重复组数',
    IF(@s3_blocker_count = 0, 'S3数据前置未见阻碍',
       'S3未完成：存在阻碍，禁止回填、收紧或创建地址唯一键') AS 'S3只读预检';
SELECT
    rc.CONSTRAINT_SCHEMA AS '来源库',
    rc.TABLE_NAME AS '来源表',
    rc.CONSTRAINT_NAME AS '外键约束',
    rc.REFERENCED_TABLE_NAME AS '目标表'
FROM information_schema.REFERENTIAL_CONSTRAINTS rc
WHERE rc.UNIQUE_CONSTRAINT_SCHEMA = DATABASE()
  AND rc.REFERENCED_TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
ORDER BY rc.CONSTRAINT_SCHEMA, rc.TABLE_NAME, rc.CONSTRAINT_NAME;
SELECT JSON_EXTRACT(IF(@preflight_ok = 1, '{}', 'invalid'), '$') AS 'S1升级前置门禁';
SELECT
    @s1_not_null_column_count AS 'S1列NOT NULL数',
    IF(@s1_not_null_column_count = 0,
       'S1可空阶段',
       'S3结构阶段：NOT NULL已收紧，S1升级拒绝') AS '结构阶段',
    @s1_data_value_count + @zone_warehouse_value_count AS 'S1新列已写值行数',
    @warehouse_orphan_company_count AS '孤儿仓库数',
    IF(@s1_data_value_count = 0 AND @zone_warehouse_value_count = 0,
       'S1未写数据',
       'S3数据阶段：S1新列已有值，S1升级拒绝') AS '数据阶段';

-- ----------------------------------------------------------------------------
-- 六、附加 DDL（逐对象由元数据和会话 checkpoint 门控）
-- ----------------------------------------------------------------------------
SET @ddl_chain_ok = @preflight_ok;

SET @ddl_sql = IF(
    @ddl_chain_ok = 1 AND @warehouse_action = 'CREATE',
    'CREATE TABLE wms_warehouse (
        warehouse_id bigint NOT NULL COMMENT ''仓库主键，系统生成'',
        company_id bigint NOT NULL COMMENT ''所属公司主键，逻辑关联 wms_company.company_id'',
        warehouse_code varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT ''仓库编码，同公司内唯一且使用精确比较'',
        warehouse_name varchar(100) NOT NULL COMMENT ''仓库名称，创建后可按受控业务修改'',
        warehouse_type_code varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT ''仓库类型编码，如 GENERAL/FLOOR；楼层只存于类型配置'',
        type_config_version_id bigint DEFAULT NULL COMMENT ''仓库类型配置版本主键；配置版本表后续建立'',
        lifecycle_status varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT ''ACTIVE'' COMMENT ''生命周期：ACTIVE/RETIRING/RETIRED'',
        current_location_version_id bigint DEFAULT NULL COMMENT ''当前空间版本主键；位置版本表后续建立'',
        revision int NOT NULL DEFAULT 1 COMMENT ''仓库乐观锁版本，创建为1'',
        remark varchar(500) DEFAULT NULL COMMENT ''备注'',
        create_dept bigint DEFAULT NULL COMMENT ''创建部门'',
        create_by bigint DEFAULT NULL COMMENT ''创建者'',
        create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',
        update_by bigint DEFAULT NULL COMMENT ''更新者'',
        update_time datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',
        PRIMARY KEY (warehouse_id),
        UNIQUE KEY uk_wms_warehouse_company_code (company_id, warehouse_code),
        KEY idx_wms_warehouse_company_status (company_id, lifecycle_status, warehouse_type_code)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS仓库主表''',
    'DO 0'
);
PREPARE stmt_iws_s1_warehouse FROM @ddl_sql;
EXECUTE stmt_iws_s1_warehouse;
DEALLOCATE PREPARE stmt_iws_s1_warehouse;
SET @ddl_chain_ok = IF(
    @ddl_chain_ok = 1
    AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_warehouse'
           AND TABLE_TYPE = 'BASE TABLE'),
    1, 0
);

SET @ddl_sql = IF(
    @ddl_chain_ok = 1
    AND (SELECT action FROM tmp_iws_s1_col
         WHERE table_name = 'wms_warehouse_zone' AND column_name = 'warehouse_id') = 'ADD',
    'ALTER TABLE wms_warehouse_zone ADD COLUMN warehouse_id bigint(20) DEFAULT NULL COMMENT ''所属仓库主键，逻辑关联 wms_warehouse.warehouse_id；S1暂可空，S3回填后收紧''',
    'DO 0'
);
PREPARE stmt_iws_s1_col_zone_wh FROM @ddl_sql;
EXECUTE stmt_iws_s1_col_zone_wh;
DEALLOCATE PREPARE stmt_iws_s1_col_zone_wh;
SET @ddl_chain_ok = IF(
    @ddl_chain_ok = 1
    AND (SELECT COUNT(*) = 1 FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_warehouse_zone'
           AND COLUMN_NAME = 'warehouse_id' AND ORDINAL_POSITION = 19),
    1, 0
);

SET @ddl_sql = IF(
    @ddl_chain_ok = 1
    AND (SELECT action FROM tmp_iws_s1_col
         WHERE table_name = 'wms_storage' AND column_name = 'warehouse_id') = 'ADD',
    'ALTER TABLE wms_storage ADD COLUMN warehouse_id bigint(20) DEFAULT NULL COMMENT ''所属仓库主键，逻辑关联 wms_warehouse.warehouse_id；S1暂可空，S3回填后收紧''',
    'DO 0'
);
PREPARE stmt_iws_s1_col_storage_wh FROM @ddl_sql;
EXECUTE stmt_iws_s1_col_storage_wh;
DEALLOCATE PREPARE stmt_iws_s1_col_storage_wh;

SET @ddl_sql = IF(
    @ddl_chain_ok = 1
    AND (SELECT action FROM tmp_iws_s1_col
         WHERE table_name = 'wms_storage' AND column_name = 'aisle_text') = 'ADD',
    'ALTER TABLE wms_storage ADD COLUMN aisle_text varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT ''巷道文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL''',
    'DO 0'
);
PREPARE stmt_iws_s1_col_aisle FROM @ddl_sql;
EXECUTE stmt_iws_s1_col_aisle;
DEALLOCATE PREPARE stmt_iws_s1_col_aisle;

SET @ddl_sql = IF(
    @ddl_chain_ok = 1
    AND (SELECT action FROM tmp_iws_s1_col
         WHERE table_name = 'wms_storage' AND column_name = 'row_text') = 'ADD',
    'ALTER TABLE wms_storage ADD COLUMN row_text varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT ''排文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL''',
    'DO 0'
);
PREPARE stmt_iws_s1_col_row FROM @ddl_sql;
EXECUTE stmt_iws_s1_col_row;
DEALLOCATE PREPARE stmt_iws_s1_col_row;

SET @ddl_sql = IF(
    @ddl_chain_ok = 1
    AND (SELECT action FROM tmp_iws_s1_col
         WHERE table_name = 'wms_storage' AND column_name = 'level_text') = 'ADD',
    'ALTER TABLE wms_storage ADD COLUMN level_text varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT ''层文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL''',
    'DO 0'
);
PREPARE stmt_iws_s1_col_level FROM @ddl_sql;
EXECUTE stmt_iws_s1_col_level;
DEALLOCATE PREPARE stmt_iws_s1_col_level;

SET @ddl_sql = IF(
    @ddl_chain_ok = 1
    AND (SELECT action FROM tmp_iws_s1_col
         WHERE table_name = 'wms_storage' AND column_name = 'column_text') = 'ADD',
    'ALTER TABLE wms_storage ADD COLUMN column_text varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT ''列文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL''',
    'DO 0'
);
PREPARE stmt_iws_s1_col_column FROM @ddl_sql;
EXECUTE stmt_iws_s1_col_column;
DEALLOCATE PREPARE stmt_iws_s1_col_column;

SET @ddl_sql = IF(
    @ddl_chain_ok = 1
    AND (SELECT action FROM tmp_iws_s1_col
         WHERE table_name = 'wms_storage' AND column_name = 'depth_text') = 'ADD',
    'ALTER TABLE wms_storage ADD COLUMN depth_text varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT ''深文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL''',
    'DO 0'
);
PREPARE stmt_iws_s1_col_depth FROM @ddl_sql;
EXECUTE stmt_iws_s1_col_depth;
DEALLOCATE PREPARE stmt_iws_s1_col_depth;
SET @ddl_chain_ok = IF(
    @ddl_chain_ok = 1
    AND (SELECT COUNT(*) = 7 FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND (
               (TABLE_NAME = 'wms_warehouse_zone' AND COLUMN_NAME = 'warehouse_id')
               OR (TABLE_NAME = 'wms_storage' AND COLUMN_NAME IN (
                   'warehouse_id','aisle_text','row_text','level_text','column_text','depth_text'
               ))
           )),
    1, 0
);

SET @ddl_sql = IF(
    @ddl_chain_ok = 1
    AND (SELECT action FROM tmp_iws_s1_idx_state
         WHERE table_name = 'wms_warehouse_zone'
           AND index_name = 'idx_wms_zone_warehouse_status') = 'ADD',
    'ALTER TABLE wms_warehouse_zone ADD KEY idx_wms_zone_warehouse_status (warehouse_id, status)',
    'DO 0'
);
PREPARE stmt_iws_s1_idx_zone FROM @ddl_sql;
EXECUTE stmt_iws_s1_idx_zone;
DEALLOCATE PREPARE stmt_iws_s1_idx_zone;

SET @ddl_sql = IF(
    @ddl_chain_ok = 1
    AND (SELECT action FROM tmp_iws_s1_idx_state
         WHERE table_name = 'wms_storage'
           AND index_name = 'idx_wms_storage_warehouse_status') = 'ADD',
    'ALTER TABLE wms_storage ADD KEY idx_wms_storage_warehouse_status (warehouse_id, status)',
    'DO 0'
);
PREPARE stmt_iws_s1_idx_storage FROM @ddl_sql;
EXECUTE stmt_iws_s1_idx_storage;
DEALLOCATE PREPARE stmt_iws_s1_idx_storage;

SET @ddl_chain_ok = IF(
    @ddl_chain_ok = 1
    AND (SELECT COUNT(DISTINCT INDEX_NAME) = 1 FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_warehouse_zone'
           AND INDEX_NAME = 'idx_wms_zone_warehouse_status')
    AND (SELECT COUNT(DISTINCT INDEX_NAME) = 1 FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_storage'
           AND INDEX_NAME = 'idx_wms_storage_warehouse_status'),
    1, 0
);

-- ----------------------------------------------------------------------------
-- 七、后置结构验收：完整目标签名与“S3 尚未实现”边界
-- ----------------------------------------------------------------------------
SET @warehouse_post_ok = (
    SELECT COUNT(*) = 15
       AND SHA2(CONCAT('[',
           GROUP_CONCAT(
               CAST(JSON_ARRAY(
                   ORDINAL_POSITION, COLUMN_NAME, LOWER(COLUMN_TYPE),
                   IS_NULLABLE, COLUMN_DEFAULT, CHARACTER_SET_NAME,
                   COLLATION_NAME, EXTRA, GENERATION_EXPRESSION, COLUMN_COMMENT
               ) AS CHAR CHARACTER SET utf8mb4)
               ORDER BY ORDINAL_POSITION SEPARATOR ','
           ),
       ']'), 256) = '2b208463ee2013471c41e5deb24a0efd71b5273bbaf96ddb0c18ac19dfbb5d58'
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_warehouse'
);
SET @zone_post_ok = (
    SELECT COUNT(*) = 19
       AND SHA2(CONCAT('[',
           GROUP_CONCAT(
               CAST(JSON_ARRAY(
                   ORDINAL_POSITION, COLUMN_NAME, LOWER(COLUMN_TYPE),
                   IS_NULLABLE, COLUMN_DEFAULT, CHARACTER_SET_NAME,
                   COLLATION_NAME, EXTRA, GENERATION_EXPRESSION, COLUMN_COMMENT
               ) AS CHAR CHARACTER SET utf8mb4)
               ORDER BY ORDINAL_POSITION SEPARATOR ','
           ),
       ']'), 256) = '4b0519abe2deff7a6ec448a4060d96b29c2aa1da9712375a54963df9d931041a'
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_warehouse_zone'
);
SET @storage_post_ok = (
    SELECT COUNT(*) = 30
       AND SHA2(CONCAT('[',
           GROUP_CONCAT(
               CAST(JSON_ARRAY(
                   ORDINAL_POSITION, COLUMN_NAME, LOWER(COLUMN_TYPE),
                   IS_NULLABLE, COLUMN_DEFAULT, CHARACTER_SET_NAME,
                   COLLATION_NAME, EXTRA, GENERATION_EXPRESSION, COLUMN_COMMENT
               ) AS CHAR CHARACTER SET utf8mb4)
               ORDER BY ORDINAL_POSITION SEPARATOR ','
           ),
       ']'), 256) = 'f56277396b69d2ae064ca00ecd25bf5c474cac6a93792c757a9711e315ed4203'
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_storage'
);
SET @warehouse_indexes_post_ok = (
    SELECT COUNT(DISTINCT INDEX_NAME) = 3
       AND SHA2(CONCAT('[',
           GROUP_CONCAT(
               CAST(JSON_ARRAY(
                   INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME,
                   SUB_PART, INDEX_TYPE, COLLATION, EXPRESSION,
                   IS_VISIBLE, INDEX_COMMENT
               ) AS CHAR CHARACTER SET utf8mb4)
               ORDER BY INDEX_NAME, SEQ_IN_INDEX SEPARATOR ','
           ),
       ']'), 256) = '3a53f0ea4b91671e2c9be36d935202707a1a70102db6b181fba3f4d8f4017e22'
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_warehouse'
);
SET @zone_indexes_post_ok = (
    SELECT COUNT(DISTINCT INDEX_NAME) = 6
       AND SHA2(CONCAT('[',
           GROUP_CONCAT(
               CAST(JSON_ARRAY(
                   INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME,
                   SUB_PART, INDEX_TYPE, COLLATION, EXPRESSION,
                   IS_VISIBLE, INDEX_COMMENT
               ) AS CHAR CHARACTER SET utf8mb4)
               ORDER BY INDEX_NAME, SEQ_IN_INDEX SEPARATOR ','
           ),
       ']'), 256) = '4d1fdb85bb7769c535e8405d23296e3a9694ae293bfd2092d81f2a0c3a6e5534'
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_warehouse_zone'
);
SET @storage_indexes_post_ok = (
    SELECT COUNT(DISTINCT INDEX_NAME) = 7
       AND SHA2(CONCAT('[',
           GROUP_CONCAT(
               CAST(JSON_ARRAY(
                   INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME,
                   SUB_PART, INDEX_TYPE, COLLATION, EXPRESSION,
                   IS_VISIBLE, INDEX_COMMENT
               ) AS CHAR CHARACTER SET utf8mb4)
               ORDER BY INDEX_NAME, SEQ_IN_INDEX SEPARATOR ','
           ),
       ']'), 256) = '4dd37da0259bdf102cb9c3d3162db8ea06946c4934059b19eccfd0c772eeffda'
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_storage'
);
SET @s1_still_nullable = (
    SELECT COUNT(*) = 7 AND MIN(IS_NULLABLE = 'YES') = 1
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND (
          (TABLE_NAME = 'wms_warehouse_zone' AND COLUMN_NAME = 'warehouse_id')
          OR (TABLE_NAME = 'wms_storage' AND COLUMN_NAME IN (
              'warehouse_id','aisle_text','row_text','level_text','column_text','depth_text'
          ))
      )
);
SET @no_s3_generated_columns = (
    SELECT COUNT(*) = 0
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
      AND (LOWER(EXTRA) LIKE '%virtual generated%'
           OR LOWER(EXTRA) LIKE '%stored generated%'
           OR GENERATION_EXPRESSION <> '')
);
SET @no_s3_check_constraints = (
    SELECT COUNT(*) = 0
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
      AND CONSTRAINT_TYPE = 'CHECK'
);
SET @no_s3_address_unique = (
    SELECT COUNT(*) = 0
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_storage'
      AND NON_UNIQUE = 0
      AND COLUMN_NAME IN ('aisle_text','row_text','level_text','column_text','depth_text')
);

SET @warehouse_table_options_post_ok = (
    SELECT COUNT(*) = 1
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_warehouse'
      AND TABLE_TYPE = 'BASE TABLE'
      AND ENGINE = 'InnoDB'
      AND TABLE_COLLATION = 'utf8mb4_0900_ai_ci'
      AND CAST(TABLE_COMMENT AS BINARY) = CAST('WMS仓库主表' AS BINARY)
);
SET @affected_table_check_count_post = (
    SELECT COUNT(*)
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
      AND CONSTRAINT_TYPE = 'CHECK'
);
SET @affected_table_fk_count_post = (
    SELECT COUNT(*)
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
      AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @affected_inbound_fk_count_post = (
    SELECT COUNT(*)
    FROM information_schema.REFERENTIAL_CONSTRAINTS
    WHERE UNIQUE_CONSTRAINT_SCHEMA = DATABASE()
      AND REFERENCED_TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
);
SET @affected_trigger_count_post = (
    SELECT COUNT(*)
    FROM information_schema.TRIGGERS
    WHERE TRIGGER_SCHEMA = DATABASE()
      AND EVENT_OBJECT_TABLE IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
);
SET @affected_unknown_object_count_post = (
    @affected_table_check_count_post + @affected_table_fk_count_post
    + @affected_inbound_fk_count_post + @affected_trigger_count_post
);
SET @s1_not_null_column_count_post = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND (
          (TABLE_NAME = 'wms_warehouse_zone' AND COLUMN_NAME = 'warehouse_id')
          OR (TABLE_NAME = 'wms_storage' AND COLUMN_NAME IN (
              'warehouse_id','aisle_text','row_text','level_text','column_text','depth_text'
          ))
      )
      AND IS_NULLABLE = 'NO'
);

-- DDL 链成功时七个 S1 列必然存在；失败时保留 0，由 @ddl_chain_ok 阻止假成功。
SET @post_s1_data_sql = CASE
    WHEN @ddl_chain_ok = 1 THEN
        'SELECT COUNT(*) INTO @s1_data_value_count_post FROM wms_storage WHERE warehouse_id IS NOT NULL OR aisle_text IS NOT NULL OR row_text IS NOT NULL OR level_text IS NOT NULL OR column_text IS NOT NULL OR depth_text IS NOT NULL'
    ELSE 'SET @s1_data_value_count_post = 0'
END;
PREPARE stmt_iws_s1_post_storage_data FROM @post_s1_data_sql;
EXECUTE stmt_iws_s1_post_storage_data;
DEALLOCATE PREPARE stmt_iws_s1_post_storage_data;

SET @post_zone_data_sql = CASE
    WHEN @ddl_chain_ok = 1 THEN
        'SELECT COUNT(*) INTO @zone_warehouse_value_count_post FROM wms_warehouse_zone WHERE warehouse_id IS NOT NULL'
    ELSE 'SET @zone_warehouse_value_count_post = 0'
END;
PREPARE stmt_iws_s1_post_zone_data FROM @post_zone_data_sql;
EXECUTE stmt_iws_s1_post_zone_data;
DEALLOCATE PREPARE stmt_iws_s1_post_zone_data;

SET @post_orphan_sql = CASE
    WHEN @ddl_chain_ok = 1 THEN
        'SELECT COUNT(*) INTO @warehouse_orphan_company_count_post FROM wms_warehouse w LEFT JOIN wms_company c ON c.company_id = w.company_id WHERE c.company_id IS NULL'
    ELSE 'SET @warehouse_orphan_company_count_post = 0'
END;
PREPARE stmt_iws_s1_post_orphan FROM @post_orphan_sql;
EXECUTE stmt_iws_s1_post_orphan;
DEALLOCATE PREPARE stmt_iws_s1_post_orphan;

SET @structure_ok = (
    @metadata_visibility_ok = 1
    AND @ddl_chain_ok = 1
    AND @warehouse_post_ok = 1
    AND @warehouse_indexes_post_ok = 1
    AND @warehouse_table_options_post_ok = 1
    AND @zone_post_ok = 1
    AND @zone_indexes_post_ok = 1
    AND @storage_post_ok = 1
    AND @storage_indexes_post_ok = 1
    AND @s1_still_nullable = 1
    AND @s1_not_null_column_count_post = 0
    AND @no_s3_generated_columns = 1
    AND @no_s3_check_constraints = 1
    AND @no_s3_address_unique = 1
    AND @affected_unknown_object_count_post = 0
    AND @s1_data_value_count_post = 0
    AND @zone_warehouse_value_count_post = 0
    AND @warehouse_orphan_company_count_post = 0
);

SELECT
    @target_schema AS '目标库',
    IF(@metadata_visibility_ok = 1, '通过', '权限不足') AS '元数据权限',
    @s1_release_id AS '发布标识',
    @warehouse_action AS '仓库表动作',
    IF(@structure_ok = 1, '通过', '失败') AS 'S1结构结果',
    @s1_still_nullable AS '父链与地址列仍可空',
    @warehouse_table_options_post_ok AS '仓库表选项',
    @no_s3_generated_columns AS '未提前建生成列',
    @no_s3_check_constraints AS '未提前建CHECK',
    @no_s3_address_unique AS '未提前建地址唯一键',
    @affected_unknown_object_count_post AS '后续未知约束/触发器数',
    @s1_not_null_column_count_post AS 'S1列NOT NULL数',
    @s1_data_value_count_post AS '储位S1列已写值数',
    @zone_warehouse_value_count_post AS '库区warehouse_id已写值数',
    @warehouse_orphan_company_count_post AS '孤儿仓库数',
    IF(@structure_ok = 1,
       'S1可空结构完成；S3回填/NOT NULL/生成列/CHECK/同仓地址唯一键未实现',
       CASE
           WHEN @metadata_visibility_ok = 0
               THEN '权限不足：S1结构不能报告成功，且不得进入后续阶段'
           WHEN @s1_not_null_column_count_post > 0
               THEN '阶段不匹配：S3已收紧NOT NULL，不能报告S1成功'
           WHEN @s1_data_value_count_post + @zone_warehouse_value_count_post > 0
               THEN '阶段不匹配：S3数据已写入，不能报告S1成功'
           WHEN @warehouse_orphan_company_count_post > 0
               THEN '数据冲突：存在孤儿仓库，S1升级失败'
           WHEN @affected_unknown_object_count_post > 0
               THEN '结构冲突：存在未知CHECK/FK/触发器，S1升级失败'
           ELSE 'S1结构验收失败，请检查上述对象和签名'
       END) AS '执行结果';
SELECT
    @s3_blocker_count AS 'S3旧库阻碍合计',
    IF(@s3_blocker_count = 0, 'S3数据前置未见阻碍',
       'S3未完成：存在旧库阻碍，禁止进入回填与唯一键阶段') AS '迁移边界';
SELECT JSON_EXTRACT(IF(@structure_ok = 1, '{}', 'invalid'), '$') AS 'S1结构完成门禁';
SELECT
    @s1_not_null_column_count_post AS 'S1列NOT NULL数',
    IF(@s1_not_null_column_count_post = 0,
       'S1可空阶段',
       'S3结构阶段：NOT NULL已收紧') AS '结构阶段',
    @s1_data_value_count_post + @zone_warehouse_value_count_post AS 'S1新列已写值行数',
    @warehouse_orphan_company_count_post AS '孤儿仓库数',
    IF(@s1_data_value_count_post = 0 AND @zone_warehouse_value_count_post = 0,
       'S1未写数据',
       'S3数据阶段：S1新列已有值') AS '数据阶段';

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s1_cycle;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s1_idx_actual;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s1_idx_expected;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s1_idx_state;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s1_idx;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s1_col;

SET SESSION group_concat_max_len = @saved_group_concat_max_len;
