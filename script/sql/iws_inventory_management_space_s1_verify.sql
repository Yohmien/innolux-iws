-- ============================================================================
-- IWS+ 在库空间 SQL S1 首切片只读验收（INV-P2-01-S1）
-- ============================================================================
-- 目标数据库：MySQL 8.0；已在 MySQL 8.0.45 隔离库验证
-- 验收范围：
--   wms_warehouse 15 列 3 索引；
--   wms_warehouse_zone 19 列 6 索引；
--   wms_storage 30 列 7 索引；
--   七个 S1 父链/地址列仍为可空，S1 数据尚未回填；
--   受影响表不得出现 S1 未声明的 CHECK/FK/trigger；S1 新列已有值、已收紧
--   NOT NULL 或存在孤儿仓库时明确判为阶段/数据冲突，不能报告 S1 成功。
--   不得提前出现生成列、CHECK 或同仓五项地址唯一键。
-- 成功含义：
--   只代表当前版本的 S1 可空结构成立。S2 冻结、S3 回填/父链核对/NOT NULL/
--   生成列/CHECK/地址唯一键、S4 数据和空间完整验收均未完成。
--   本脚本与 S1 升级脚本使用同一组成功条件，不把后续阶段或数据冲突报成 S1 通过。
-- 执行：
--   mysql --no-defaults --protocol=TCP --host=<主机> --port=<端口> --user=<账号> \
--     --default-character-set=utf8mb4 --database=<目标库> --batch --raw \
--     < script/sql/iws_inventory_management_space_s1_verify.sql
--   禁止 --force；stdin 在首个 SQL 错误处终止并返回非 0。本脚本只读业务对象和元数据；
--   任意 GUI 或其他自行续错的执行器不在支持范围。
-- ============================================================================

SET NAMES utf8mb4;

SET @target_schema = 'innolux-iws';
SET @s1_release_id = 'INV-P2-01-S1-20261027';

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

SET @schema_ok = (
    DATABASE() IS NOT NULL
    AND CAST(DATABASE() AS BINARY) = CAST(@target_schema AS BINARY)
);
SET @version_ok = (VERSION() LIKE '8.%');

-- S1 的公司基线与目标列/索引均按 JSON canonical 行签名比较。JSON_ARRAY 保留
-- SQL NULL，并以 JSON 转义组合各字段，因此真实字符串 '<NULL>'、'|'、'~'、
-- 引号和反斜杠不会与相邻字段或 NULL 发生碰撞。
SET @company_ok = (
    SELECT COUNT(*) = 1
       AND MAX(TABLE_TYPE = 'BASE TABLE') = 1
       AND MAX(ENGINE = 'InnoDB') = 1
       AND MAX(TABLE_COLLATION = 'utf8mb4_0900_ai_ci') = 1
       AND MAX(CAST(TABLE_COMMENT AS BINARY) = CAST('WMS公司主档' AS BINARY)) = 1
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_company'
);
SET @company_columns_ok = (
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
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_company'
);
SET @company_indexes_ok = (
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
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_company'
);
SET @company_structure_ok = (@company_ok = 1 AND @company_columns_ok = 1 AND @company_indexes_ok = 1);

SET @warehouse_columns_ok = (
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
SET @zone_columns_ok = (
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
SET @storage_columns_ok = (
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
SET @warehouse_indexes_ok = (
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
SET @zone_indexes_ok = (
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
SET @storage_indexes_ok = (
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
SET @table_options_ok = (
    SELECT COUNT(*) = 3
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND (
          (TABLE_NAME = 'wms_warehouse'
           AND TABLE_TYPE = 'BASE TABLE' AND ENGINE = 'InnoDB'
           AND TABLE_COLLATION = 'utf8mb4_0900_ai_ci'
           AND CAST(TABLE_COMMENT AS BINARY) = CAST('WMS仓库主表' AS BINARY))
          OR (TABLE_NAME = 'wms_warehouse_zone'
           AND TABLE_TYPE = 'BASE TABLE' AND ENGINE = 'InnoDB'
           AND TABLE_COLLATION = 'utf8mb4_0900_ai_ci'
           AND CAST(TABLE_COMMENT AS BINARY) = CAST('WMS库区表' AS BINARY))
          OR (TABLE_NAME = 'wms_storage'
           AND TABLE_TYPE = 'BASE TABLE' AND ENGINE = 'InnoDB'
           AND TABLE_COLLATION = 'utf8mb4_0900_ai_ci'
           AND CAST(TABLE_COMMENT AS BINARY) = CAST('WMS储位表' AS BINARY))
      )
);

SET @s1_columns_nullable = (
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

-- 与升级脚本同组门禁：受影响表上的任何 CHECK/FK/trigger 都属于 S1 未知结构。
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

-- S1 不写仓库或地址数据；确认没有把后续阶段内容冒充为已完成。
SET @s1_data_value_count = (
    SELECT COUNT(*)
    FROM wms_storage
    WHERE warehouse_id IS NOT NULL
       OR aisle_text IS NOT NULL
       OR row_text IS NOT NULL
       OR level_text IS NOT NULL
       OR column_text IS NOT NULL
       OR depth_text IS NOT NULL
);
SET @zone_warehouse_value_count = (
    SELECT COUNT(*) FROM wms_warehouse_zone WHERE warehouse_id IS NOT NULL
);
SET @warehouse_orphan_company_count = (
    SELECT COUNT(*)
    FROM wms_warehouse w
    LEFT JOIN wms_company c ON c.company_id = w.company_id
    WHERE c.company_id IS NULL
);

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
DROP TEMPORARY TABLE IF EXISTS tmp_iws_s1v_cycle;
CREATE TEMPORARY TABLE tmp_iws_s1v_cycle AS
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
SET @zone_cycle_count = (SELECT COUNT(*) FROM tmp_iws_s1v_cycle);

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

SET @structure_ok = (
    @metadata_visibility_ok = 1
    AND @schema_ok = 1
    AND @version_ok = 1
    AND @company_structure_ok = 1
    AND @warehouse_columns_ok = 1
    AND @warehouse_indexes_ok = 1
    AND @zone_columns_ok = 1
    AND @zone_indexes_ok = 1
    AND @storage_columns_ok = 1
    AND @storage_indexes_ok = 1
    AND @table_options_ok = 1
    AND @s1_columns_nullable = 1
    AND @s1_not_null_column_count = 0
    AND @no_s3_generated_columns = 1
    AND @no_s3_check_constraints = 1
    AND @no_s3_address_unique = 1
    AND @affected_unknown_object_count = 0
    AND @s1_data_value_count = 0
    AND @zone_warehouse_value_count = 0
    AND @warehouse_orphan_company_count = 0
);

SELECT
    @target_schema AS '目标库',
    VERSION() AS '数据库版本',
    IF(@metadata_visibility_ok = 1, '通过', '权限不足') AS '元数据权限',
    @company_structure_ok AS '公司前置',
    @warehouse_columns_ok AS '仓库列签名',
    @warehouse_indexes_ok AS '仓库索引签名',
    @zone_columns_ok AS '库区列签名',
    @zone_indexes_ok AS '库区索引签名',
    @storage_columns_ok AS '储位列签名',
    @storage_indexes_ok AS '储位索引签名',
    @s1_columns_nullable AS 'S1列仍可空',
    @s1_not_null_column_count AS 'S1列NOT NULL数',
    @affected_table_check_count AS '受影响表CHECK数',
    @affected_table_fk_count AS '受影响表FK数',
    @affected_inbound_fk_count AS '引用受影响表FK数',
    @affected_trigger_count AS '受影响表触发器数',
    @s1_data_value_count AS '储位S1列已写值数',
    @zone_warehouse_value_count AS '库区warehouse_id已写值数',
    @warehouse_orphan_company_count AS '孤儿仓库数',
    IF(@structure_ok = 1,
       'S1可空结构验收通过；S3/S4未完成',
       CASE
           WHEN @metadata_visibility_ok = 0
               THEN '权限不足：S1可空结构不能报告通过'
           WHEN @s1_not_null_column_count > 0
               THEN '阶段不匹配：S3已收紧NOT NULL，当前库不属于S1可空阶段'
           WHEN @s1_data_value_count + @zone_warehouse_value_count > 0
               THEN '阶段不匹配：S3数据已写入，S1验收失败'
           WHEN @warehouse_orphan_company_count > 0
               THEN '数据冲突：存在孤儿仓库，S1验收失败'
           WHEN @affected_unknown_object_count > 0
               THEN '结构冲突：存在未知CHECK/FK/触发器，S1验收失败'
           ELSE 'S1可空结构验收失败'
       END) AS '执行结果';
SELECT
    @s3_blocker_count AS 'S3旧库阻碍合计',
    @zone_missing_parent_count AS '旧库区父级缺失数',
    @zone_cycle_count AS '旧库区父级成环数',
    @zone_cross_warehouse_count AS '旧库区跨仓父链数',
    @storage_orphan_zone_count AS '储位孤立区数',
    @storage_unmapped_warehouse_count AS '储位无仓库来源数',
    @legacy_address_duplicate_group_count AS '旧地址候选重复组数',
    IF(@s3_blocker_count = 0, 'S3数据前置未见阻碍',
       'S3未完成：唯一键、生成列、CHECK 和回填均不得据此宣称完成') AS '迁移边界';
SELECT
    rc.CONSTRAINT_SCHEMA AS '来源库',
    rc.TABLE_NAME AS '来源表',
    rc.CONSTRAINT_NAME AS '外键约束',
    rc.REFERENCED_TABLE_NAME AS '目标表'
FROM information_schema.REFERENTIAL_CONSTRAINTS rc
WHERE rc.UNIQUE_CONSTRAINT_SCHEMA = DATABASE()
  AND rc.REFERENCED_TABLE_NAME IN ('wms_warehouse','wms_warehouse_zone','wms_storage')
ORDER BY rc.CONSTRAINT_SCHEMA, rc.TABLE_NAME, rc.CONSTRAINT_NAME;
SELECT JSON_EXTRACT(IF(@structure_ok = 1, '{}', 'invalid'), '$') AS 'S1只读验收门禁';
SELECT
    @s1_not_null_column_count AS 'S1列NOT NULL数',
    IF(@s1_not_null_column_count = 0,
       'S1可空阶段',
       'S3结构阶段：NOT NULL已收紧') AS '结构阶段',
    @s1_data_value_count + @zone_warehouse_value_count AS 'S1新列已写值行数',
    @warehouse_orphan_company_count AS '孤儿仓库数',
    IF(@s1_data_value_count = 0 AND @zone_warehouse_value_count = 0,
       'S1未写数据',
       'S3数据阶段：S1新列已有值') AS '数据阶段';

DROP TEMPORARY TABLE IF EXISTS tmp_iws_s1v_cycle;
SET SESSION group_concat_max_len = @saved_group_concat_max_len;
