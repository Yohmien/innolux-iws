-- ============================================================================
-- IWS+ WMS 直接写入结构可信登记（INV-S2-F0）
-- ============================================================================
-- 目标数据库：MySQL 8.0.45
-- 组件合同：
--   component_code  = WMS_DIRECT_WRITE
--   schema_version  = 1
--   release_id      = INV-WMS-DIRECT-WRITE-1
-- 本脚本性质：
--   这是离线 schema 验收后的控制记录登记脚本，不是业务 seed，不创建或修复
--   任何业务表、控制结构、触发器、索引或数据行。
-- 登记范围：
--   控制 5 表：
--     wms_schema_version / wms_upgrade_run / wms_upgrade_checkpoint /
--     wms_legacy_id_map / wms_upgrade_issue
--   公司 5 表：
--     wms_company / wms_company_management_scope /
--     wms_company_management_grant / wms_object_audit / wms_command_attempt
--   供应商表：
--     wms_supplier
-- 验收口径：
--   逐表比较表类型、引擎、排序规则、表注释、完整列定义、完整索引定义、
--   CHECK（含 ENFORCED）、本表外键、目标表触发器和外部指入外键。目标清单中的
--   外键、触发器和外部指入外键均必须为零；未知对象不能因元数据权限不足而
--   被忽略。最终 11 表规范指纹必须同时等于脚本声明值和 Java 固定值。
--   当前结构直接登记为 source=target，不伪装历史迁移。
-- 幂等与冲突：
--   同一 (component_code, schema_version) 且内容完全一致并已 VERIFIED 时只跳过；
--   同键异 release/source/target/范围备注或非 VERIFIED 状态硬阻断，不覆盖既有登记。
--   所有 WMS 控制运行均须安全完成：COMPLETED/COMPLETE/非空 completed_at，
--   且 run 自身 release/target 指纹恰对应一条 VERIFIED schema 登记。
-- 规范签名：
--   列、索引、CHECK、外键和触发器的每个稳定字段均以 JSON_ARRAY 保留 SQL NULL
--   与文本边界，再进行固定长度 SHA-256；不把 NULL、'<NULL>' 或拼接分隔符
--   视为同一内容。目标 manifest 与当前结构使用同一 canonical。
-- 权限：
--   部署账号必须是离线验收账号，直接持有全局 SELECT、TRIGGER，且
--   partial_revokes=OFF；schema/table 授权或角色授权不满足完整性证明。
-- 参数：
--   目标库、组件键、发布标识、执行人和目标指纹集中在下方。执行前只修改
--   @target_schema；其他合同值必须与 Java 同版。
-- 执行：
--   mysql --no-defaults --protocol=TCP --host=<主机> --port=<端口> \
--     --user=<验收账号> --default-character-set=utf8mb4 \
--     --database=<目标库> --batch --raw \
--     < script/sql/iws_inventory_direct_write_fence_register.sql
--   必须使用同一连接且不得添加 --force；首个 SQL 错误即停止并关闭连接。
-- ============================================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 集中参数
-- ----------------------------------------------------------------------------
SET @target_schema = 'innolux-iws';
SET @component_code = 'WMS_DIRECT_WRITE';
SET @schema_version = '1';
SET @release_id = 'INV-WMS-DIRECT-WRITE-1';
SET @registration_actor = 'OFFLINE_SCHEMA_ACCEPTANCE';
SET @registration_remark =
    'INV-S2-F0 仅登记 WMS_DIRECT_WRITE；不表示整个 P1 已完成';
SET @declared_target_fingerprint =
    '1b236346b990fbf9410aeea49bb323f174112e1a44c133998baccb2ddac06909';
SET @expected_target_table_count = 11;

-- 离线验收账号契约。该检查证明触发器、跨表外键和完整 information_schema
-- 结果未被权限裁剪，不把“查询查不到”误判成“目标不存在”。
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

SET @saved_group_concat_max_len = @@SESSION.group_concat_max_len;
SET SESSION group_concat_max_len = 1048576;

SET @schema_ok = (
    DATABASE() IS NOT NULL
    AND CAST(DATABASE() AS BINARY) = CAST(@target_schema AS BINARY)
);
SET @schema_exists = (
    SELECT COUNT(*) = 1
      FROM information_schema.SCHEMATA
     WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@target_schema AS BINARY)
);
SET @version_ok = (VERSION() = '8.0.45');

-- ----------------------------------------------------------------------------
-- 一、目标 manifest
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_iws_direct_write_expected;
CREATE TEMPORARY TABLE tmp_iws_direct_write_expected (
    table_name            varchar(64)  NOT NULL,
    engine                varchar(32)  NOT NULL,
    table_collation       varchar(64)  NOT NULL,
    table_comment         varchar(255) NOT NULL,
    column_count          int          NOT NULL,
    column_hash           char(64)     NOT NULL,
    index_count           int          NOT NULL,
    index_hash            char(64)     NOT NULL,
    check_count           int          NOT NULL,
    check_disabled_count  int          NOT NULL,
    check_hash            char(64)     NOT NULL,
    foreign_key_count     int          NOT NULL,
    foreign_key_hash      char(64)     NOT NULL,
    trigger_count         int          NOT NULL,
    trigger_hash          char(64)     NOT NULL,
    PRIMARY KEY (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO tmp_iws_direct_write_expected
    (table_name, engine, table_collation, table_comment,
     column_count, column_hash, index_count, index_hash,
     check_count, check_disabled_count, check_hash,
     foreign_key_count, foreign_key_hash, trigger_count, trigger_hash)
VALUES
    ('wms_command_attempt', 'InnoDB', 'utf8mb4_0900_ai_ci',
     'WMS非计划受控命令尝试',
     22, '0c8e67e13e5d4a0a618df9d6cc31a4fbee6315e564d89a9cb9d3c1d75a58f44d',
     4,  '2664c44c319897cf667da0341d175b03a0075f1e0b99f5cb7f471f30a10f0efa',
     0,  0, '', 0, '', 0, ''),
    ('wms_company', 'InnoDB', 'utf8mb4_0900_ai_ci',
     'WMS公司主档',
     12, 'd93ccceced65b2171c08e0c41bc0d8f637cb0dbcd635a768897c4967591f619c',
     3,  '3634a6b8771364d57f7f09e68c84fe60accc53d408ad3d667ebd536c1416603a',
     0,  0, '', 0, '', 0, ''),
    ('wms_company_management_grant', 'InnoDB', 'utf8mb4_0900_ai_ci',
     'WMS公司管理角色授权明细',
     5,  '12ee2fdceb4023f6948573ca7e9bd8447bacb7d3ef2c3b85b1a4ed86475b41c0',
     3,  'c372d7389f6b856c04dc6fd0924cd48eeada3486531bfb2af069c1c8ae0dd023',
     0,  0, '', 0, '', 0, ''),
    ('wms_company_management_scope', 'InnoDB', 'utf8mb4_0900_ai_ci',
     'WMS公司管理范围头',
     4,  '97c38130ce76ef621636bee7b93a66b111ce52757b9dc7df4ec5bfec0a9e73c4',
     1,  '0e90ef8cde10aa1cc148d64a732af6517188edeb65bb7ac82aa825f5e149a168',
     0,  0, '', 0, '', 0, ''),
    ('wms_legacy_id_map', 'InnoDB', 'utf8mb4_0900_ai_ci',
     'WMS旧业务标识到目标内部标识映射',
     10, '2eece521e63e8394925159ab3752a26104b9ee6b8fe4a7c5f853fe8fd8ad4fcc',
     3,  '2555126cf12596b66baeb246fe4b8cec0ed75372858dc70ea45598eccca3da33',
     1,  0, 'dd59a94403618f90a4d5c3a452facba4d630ab8936a17964d3250d33e4756670',
     0,  '', 0, ''),
    ('wms_object_audit', 'InnoDB', 'utf8mb4_0900_ai_ci',
     'WMS对象变更审计，只追加',
     21, '487a3a8ba4d72517c0e001532e18cbdc0d925cd79f943a6eb5ffdfbc894a7531',
     3,  'c74d1d26cfb065dd4451e64ec67570840fb466136120d2280f746386c7bd590e',
     0,  0, '', 0, '', 0, ''),
    ('wms_schema_version', 'InnoDB', 'utf8mb4_0900_ai_ci',
     'WMS组件结构与发布版本登记',
     10, 'cb61e3772eb2343991085d0f954d21a4dc001015eb51c24a2f20454888d3d155',
     3,  '59f8002573fcd52b209c0aec41434c8c4b22aa97d8185b559e34db60c186e468',
     1,  0, '9628922b93f3f4acbafd4f883c6241e2d58ee29222ce9d6f6e3a83044f87696c',
     0,  '', 0, ''),
    ('wms_supplier', 'InnoDB', 'utf8mb4_0900_ai_ci',
     'WMS供应商表',
     16, '67efd9798b1acc9075afeb53de9642de2a63111eb15ae095b1530eea96e0326c',
     5,  'ee44cecf913cdef316dec9e5c57132437e52dbcb472478c64fda0c5c4ce176ac',
     0,  0, '', 0, '', 0, ''),
    ('wms_upgrade_checkpoint', 'InnoDB', 'utf8mb4_0900_ai_ci',
     'WMS库存升级步骤检查点',
     13, '45ac1f85bfcc145eee79744b7a892f40eeec0de6ac8eef7078aec20396cedb6d',
     3,  '1b0de5c9e978a229da9d04ee2027f04e0196cdf4caf1980f9b664244e7c52a58',
     2,  0, '5869cda69d05735ec36e95e52696f0be298279ee778067aec2732f5b94476554',
     0,  '', 0, ''),
    ('wms_upgrade_issue', 'InnoDB', 'utf8mb4_0900_ai_ci',
     'WMS库存升级中文阻碍清单',
     10, '07cd86fa9ae831fc8d49690fc4d560da1d6386799a5485f0271e35652f95a10a',
     3,  'f27aa6c75c94f0559306e3635e5d7ba44308ca3b3f557bebdea242a5474a93f9',
     2,  0, '2efbf4688bf4dff7dec7e31fbc7e8aaffb01d94f6eb60208b8aba076f46224cf',
     0,  '', 0, ''),
    ('wms_upgrade_run', 'InnoDB', 'utf8mb4_0900_ai_ci',
     'WMS库存升级运行',
     14, '39e6b1265158a00ded2d51854ca3d1e2b4bc340c0c2ecdf43bffc7a6081621ec',
     3,  '2170d0148f11bfd72d19011d7aa0efbe06b6a41aee72fdef51b2bca48a85d336',
     3,  0, 'f2061ba8938dcdde32e4b5171b5292befc98cafcb3838fb2909247875f2aa843',
     0,  '', 0, '');

SET @manifest_fingerprint = (
    SELECT SHA2(GROUP_CONCAT(
        SHA2(CAST(JSON_ARRAY(
            table_name, engine, table_collation, table_comment,
            column_count, column_hash, index_count, index_hash,
            check_count, check_disabled_count, check_hash,
            foreign_key_count, foreign_key_hash, trigger_count, trigger_hash
        ) AS CHAR), 256)
        ORDER BY table_name SEPARATOR ''
    ), 256)
      FROM tmp_iws_direct_write_expected
);
SET @declared_fingerprint_ok = (
    CAST(@manifest_fingerprint AS BINARY)
    = CAST(@declared_target_fingerprint AS BINARY)
);

-- ----------------------------------------------------------------------------
-- 二、当前结构规范
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_iws_direct_write_columns;
CREATE TEMPORARY TABLE tmp_iws_direct_write_columns AS
SELECT c.TABLE_NAME AS table_name,
       COUNT(*) AS column_count,
       SHA2(GROUP_CONCAT(
           SHA2(CAST(JSON_ARRAY(
               c.ORDINAL_POSITION, c.COLUMN_NAME, LOWER(c.COLUMN_TYPE),
               c.IS_NULLABLE, c.COLUMN_DEFAULT, c.CHARACTER_SET_NAME,
               c.COLLATION_NAME, c.EXTRA, c.GENERATION_EXPRESSION, c.COLUMN_COMMENT
           ) AS CHAR), 256)
           ORDER BY c.ORDINAL_POSITION SEPARATOR ''
       ), 256) AS column_hash
  FROM information_schema.COLUMNS c
  JOIN tmp_iws_direct_write_expected e ON e.table_name = c.TABLE_NAME
 WHERE c.TABLE_SCHEMA = DATABASE()
 GROUP BY c.TABLE_NAME;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_direct_write_indexes;
CREATE TEMPORARY TABLE tmp_iws_direct_write_indexes AS
SELECT s.TABLE_NAME AS table_name,
       COUNT(DISTINCT s.INDEX_NAME) AS index_count,
       SHA2(GROUP_CONCAT(
           SHA2(CAST(JSON_ARRAY(
               s.INDEX_NAME, s.NON_UNIQUE, s.SEQ_IN_INDEX, s.COLUMN_NAME,
               s.SUB_PART, s.INDEX_TYPE, s.COLLATION, s.EXPRESSION,
               s.IS_VISIBLE, s.INDEX_COMMENT
           ) AS CHAR), 256)
           ORDER BY s.INDEX_NAME, s.SEQ_IN_INDEX SEPARATOR ''
       ), 256) AS index_hash
  FROM information_schema.STATISTICS s
  JOIN tmp_iws_direct_write_expected e ON e.table_name = s.TABLE_NAME
 WHERE s.TABLE_SCHEMA = DATABASE()
 GROUP BY s.TABLE_NAME;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_direct_write_checks;
CREATE TEMPORARY TABLE tmp_iws_direct_write_checks AS
SELECT tc.TABLE_NAME AS table_name,
       COUNT(*) AS check_count,
       SUM(CASE WHEN tc.ENFORCED = 'YES' THEN 0 ELSE 1 END) AS check_disabled_count,
       SHA2(GROUP_CONCAT(
           SHA2(CAST(JSON_ARRAY(
               cc.CONSTRAINT_NAME, tc.ENFORCED, cc.CHECK_CLAUSE
           ) AS CHAR), 256)
           ORDER BY cc.CONSTRAINT_NAME SEPARATOR ''
       ), 256) AS check_hash
  FROM information_schema.TABLE_CONSTRAINTS tc
  JOIN information_schema.CHECK_CONSTRAINTS cc
    ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
   AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
  JOIN tmp_iws_direct_write_expected e ON e.table_name = tc.TABLE_NAME
 WHERE tc.TABLE_SCHEMA = DATABASE()
   AND tc.CONSTRAINT_TYPE = 'CHECK'
 GROUP BY tc.TABLE_NAME;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_direct_write_fk_parts;
CREATE TEMPORARY TABLE tmp_iws_direct_write_fk_parts AS
SELECT rc.TABLE_NAME AS table_name,
       rc.CONSTRAINT_NAME AS constraint_name,
       rc.UNIQUE_CONSTRAINT_NAME AS referenced_constraint,
       rc.UPDATE_RULE AS update_rule,
       rc.DELETE_RULE AS delete_rule,
       SHA2(GROUP_CONCAT(
           SHA2(CAST(JSON_ARRAY(
               k.COLUMN_NAME, k.REFERENCED_TABLE_NAME, k.REFERENCED_COLUMN_NAME
           ) AS CHAR), 256)
           ORDER BY k.ORDINAL_POSITION SEPARATOR ''
       ), 256) AS columns_part
  FROM information_schema.REFERENTIAL_CONSTRAINTS rc
  JOIN information_schema.KEY_COLUMN_USAGE k
    ON k.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA
   AND k.TABLE_NAME = rc.TABLE_NAME
   AND k.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
  JOIN tmp_iws_direct_write_expected e ON e.table_name = rc.TABLE_NAME
 WHERE rc.CONSTRAINT_SCHEMA = DATABASE()
 GROUP BY rc.TABLE_NAME, rc.CONSTRAINT_NAME, rc.UNIQUE_CONSTRAINT_NAME,
          rc.UPDATE_RULE, rc.DELETE_RULE;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_direct_write_foreign_keys;
CREATE TEMPORARY TABLE tmp_iws_direct_write_foreign_keys AS
SELECT table_name,
       COUNT(*) AS foreign_key_count,
       SHA2(GROUP_CONCAT(
           SHA2(CAST(JSON_ARRAY(
               constraint_name, referenced_constraint, update_rule,
               delete_rule, columns_part
           ) AS CHAR), 256)
           ORDER BY constraint_name SEPARATOR ''
       ), 256) AS foreign_key_hash
  FROM tmp_iws_direct_write_fk_parts
 GROUP BY table_name;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_direct_write_inbound_fk_parts;
CREATE TEMPORARY TABLE tmp_iws_direct_write_inbound_fk_parts AS
SELECT k.REFERENCED_TABLE_NAME AS table_name,
       k.CONSTRAINT_SCHEMA AS source_schema,
       k.TABLE_NAME AS source_table,
       k.CONSTRAINT_NAME AS constraint_name,
       rc.UPDATE_RULE AS update_rule,
       rc.DELETE_RULE AS delete_rule,
       SHA2(GROUP_CONCAT(
           SHA2(CAST(JSON_ARRAY(
               k.COLUMN_NAME, k.REFERENCED_COLUMN_NAME
           ) AS CHAR), 256)
           ORDER BY k.ORDINAL_POSITION SEPARATOR ''
       ), 256) AS columns_part
  FROM information_schema.KEY_COLUMN_USAGE k
  JOIN information_schema.REFERENTIAL_CONSTRAINTS rc
    ON rc.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
   AND rc.TABLE_NAME = k.TABLE_NAME
   AND rc.CONSTRAINT_NAME = k.CONSTRAINT_NAME
  JOIN tmp_iws_direct_write_expected e ON e.table_name = k.REFERENCED_TABLE_NAME
 WHERE k.REFERENCED_TABLE_SCHEMA = DATABASE()
 GROUP BY k.REFERENCED_TABLE_NAME, k.CONSTRAINT_SCHEMA, k.TABLE_NAME,
          k.CONSTRAINT_NAME, rc.UPDATE_RULE, rc.DELETE_RULE;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_direct_write_inbound_foreign_keys;
CREATE TEMPORARY TABLE tmp_iws_direct_write_inbound_foreign_keys AS
SELECT table_name,
       COUNT(*) AS inbound_foreign_key_count,
       SHA2(GROUP_CONCAT(
           SHA2(CAST(JSON_ARRAY(
               source_schema, source_table, constraint_name,
               update_rule, delete_rule, columns_part
           ) AS CHAR), 256)
           ORDER BY source_schema, source_table, constraint_name SEPARATOR ''
       ), 256) AS inbound_foreign_key_hash
  FROM tmp_iws_direct_write_inbound_fk_parts
 GROUP BY table_name;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_direct_write_triggers;
CREATE TEMPORARY TABLE tmp_iws_direct_write_triggers AS
SELECT tr.EVENT_OBJECT_TABLE AS table_name,
       COUNT(*) AS trigger_count,
       SHA2(GROUP_CONCAT(
           SHA2(CAST(JSON_ARRAY(
               tr.TRIGGER_NAME, tr.ACTION_TIMING, tr.EVENT_MANIPULATION,
               tr.ACTION_STATEMENT, tr.ACTION_ORDER
           ) AS CHAR), 256)
           ORDER BY tr.TRIGGER_NAME SEPARATOR ''
       ), 256) AS trigger_hash
  FROM information_schema.TRIGGERS tr
  JOIN tmp_iws_direct_write_expected e ON e.table_name = tr.EVENT_OBJECT_TABLE
 WHERE tr.EVENT_OBJECT_SCHEMA = DATABASE()
 GROUP BY tr.EVENT_OBJECT_TABLE;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_direct_write_actual;
CREATE TEMPORARY TABLE tmp_iws_direct_write_actual AS
SELECT e.table_name,
       t.TABLE_TYPE AS table_type,
       t.ENGINE AS engine,
       t.TABLE_COLLATION AS table_collation,
       t.TABLE_COMMENT AS table_comment,
       IFNULL(c.column_count, 0) AS column_count,
       IFNULL(c.column_hash, '') AS column_hash,
       IFNULL(i.index_count, 0) AS index_count,
       IFNULL(i.index_hash, '') AS index_hash,
       IFNULL(k.check_count, 0) AS check_count,
       IFNULL(k.check_disabled_count, 0) AS check_disabled_count,
       IFNULL(k.check_hash, '') AS check_hash,
       IFNULL(f.foreign_key_count, 0) AS foreign_key_count,
       IFNULL(f.foreign_key_hash, '') AS foreign_key_hash,
       IFNULL(r.trigger_count, 0) AS trigger_count,
       IFNULL(r.trigger_hash, '') AS trigger_hash,
       IFNULL(inf.inbound_foreign_key_count, 0) AS inbound_foreign_key_count,
       IFNULL(inf.inbound_foreign_key_hash, '') AS inbound_foreign_key_hash
  FROM tmp_iws_direct_write_expected e
  LEFT JOIN information_schema.TABLES t
    ON t.TABLE_SCHEMA = DATABASE()
   AND t.TABLE_NAME = e.table_name
  LEFT JOIN tmp_iws_direct_write_columns c ON c.table_name = e.table_name
  LEFT JOIN tmp_iws_direct_write_indexes i ON i.table_name = e.table_name
  LEFT JOIN tmp_iws_direct_write_checks k ON k.table_name = e.table_name
  LEFT JOIN tmp_iws_direct_write_foreign_keys f ON f.table_name = e.table_name
  LEFT JOIN tmp_iws_direct_write_triggers r ON r.table_name = e.table_name
  LEFT JOIN tmp_iws_direct_write_inbound_foreign_keys inf ON inf.table_name = e.table_name;

SET @actual_table_count = (
    SELECT COUNT(*) FROM tmp_iws_direct_write_actual WHERE table_type = 'BASE TABLE'
);
SET @definition_mismatch_count = (
    SELECT COUNT(*)
      FROM tmp_iws_direct_write_actual a
      JOIN tmp_iws_direct_write_expected e ON e.table_name = a.table_name
     WHERE a.table_type IS NULL
        OR a.table_type <> 'BASE TABLE'
        OR NOT (CAST(a.engine AS BINARY) <=> CAST(e.engine AS BINARY))
        OR NOT (CAST(a.table_collation AS BINARY) <=> CAST(e.table_collation AS BINARY))
        OR NOT (CAST(a.table_comment AS BINARY) <=> CAST(e.table_comment AS BINARY))
        OR a.column_count <> e.column_count
        OR NOT (CAST(a.column_hash AS BINARY) <=> CAST(e.column_hash AS BINARY))
        OR a.index_count <> e.index_count
        OR NOT (CAST(a.index_hash AS BINARY) <=> CAST(e.index_hash AS BINARY))
        OR a.check_count <> e.check_count
        OR a.check_disabled_count <> e.check_disabled_count
        OR NOT (CAST(a.check_hash AS BINARY) <=> CAST(e.check_hash AS BINARY))
        OR a.foreign_key_count <> e.foreign_key_count
        OR NOT (CAST(a.foreign_key_hash AS BINARY) <=> CAST(e.foreign_key_hash AS BINARY))
        OR a.trigger_count <> e.trigger_count
        OR NOT (CAST(a.trigger_hash AS BINARY) <=> CAST(e.trigger_hash AS BINARY))
        OR a.inbound_foreign_key_count <> 0
);

SET @actual_target_fingerprint = (
    SELECT SHA2(GROUP_CONCAT(
        SHA2(CAST(JSON_ARRAY(
            table_name, engine, table_collation, table_comment,
            column_count, column_hash, index_count, index_hash,
            check_count, check_disabled_count, check_hash,
            foreign_key_count, foreign_key_hash, trigger_count, trigger_hash
        ) AS CHAR), 256)
        ORDER BY table_name SEPARATOR ''
    ), 256)
      FROM tmp_iws_direct_write_actual
);

SET @preflight_ok = (
    @metadata_visibility_ok = 1
    AND @schema_ok = 1
    AND @schema_exists = 1
    AND @version_ok = 1
    AND @declared_fingerprint_ok = 1
    AND @actual_table_count = @expected_target_table_count
    AND @definition_mismatch_count = 0
    AND CAST(@actual_target_fingerprint AS BINARY)
        = CAST(@declared_target_fingerprint AS BINARY)
);

SELECT
    @target_schema AS '目标库',
    VERSION() AS '数据库版本',
    IF(@metadata_visibility_ok = 1, '通过', '失败') AS '元数据可见权限',
    @component_code AS '组件编码',
    @schema_version AS '结构版本',
    @release_id AS '发布标识',
    @actual_table_count AS '已匹配目标表数',
    @definition_mismatch_count AS '结构冲突数',
    IF(@declared_fingerprint_ok = 1, '通过', '失败') AS '目标 manifest 自检',
    @actual_target_fingerprint AS '实际结构指纹',
    IF(@preflight_ok = 1, '通过', '失败') AS '登记前置结果';

SELECT
    a.table_name AS '目标表',
    a.table_type AS '表类型',
    a.check_disabled_count AS '禁用CHECK数',
    a.trigger_count AS '触发器数',
    a.foreign_key_count AS '本表外键数',
    a.inbound_foreign_key_count AS '外部指入外键数',
    a.column_hash AS '列签名',
    a.index_hash AS '索引签名',
    a.check_hash AS 'CHECK签名'
  FROM tmp_iws_direct_write_actual a
 ORDER BY a.table_name;

SELECT JSON_EXTRACT(IF(@preflight_ok = 1, '{}', 'invalid'), '$')
    AS '可信结构登记前置门禁';

-- ----------------------------------------------------------------------------
-- 三、锁登记键并执行同键幂等或冲突阻断
-- ----------------------------------------------------------------------------
SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION;

SET @existing_component_code = NULL;
SET @existing_schema_version = NULL;
SET @existing_release_id = NULL;
SET @existing_source_fingerprint = NULL;
SET @existing_target_fingerprint = NULL;
SET @existing_status = NULL;
SET @existing_remark = NULL;

SELECT component_code, schema_version, release_id,
       source_fingerprint, target_fingerprint, status, remark
  INTO @existing_component_code, @existing_schema_version,
       @existing_release_id, @existing_source_fingerprint,
       @existing_target_fingerprint, @existing_status, @existing_remark
  FROM wms_schema_version
 WHERE component_code = @component_code
   AND schema_version = @schema_version
 FOR UPDATE;

SELECT schema_version_id
  FROM wms_schema_version
 WHERE status = 'VERIFIED'
 ORDER BY schema_version_id
 FOR SHARE;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_direct_write_runs;
CREATE TEMPORARY TABLE tmp_iws_direct_write_runs (
    run_id                    bigint      NOT NULL,
    release_id                varchar(64) NOT NULL,
    status                    varchar(16) NOT NULL,
    phase                     varchar(32) NOT NULL,
    target_fingerprint        char(64)    NOT NULL,
    completed_at              datetime(3) DEFAULT NULL,
    registration_match_count  int         NOT NULL DEFAULT 0,
    PRIMARY KEY (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SELECT run_id
  FROM wms_upgrade_run
 ORDER BY run_id
 FOR UPDATE;

INSERT INTO tmp_iws_direct_write_runs
    (run_id, release_id, status, phase, target_fingerprint, completed_at)
SELECT run_id, release_id, status, phase, target_fingerprint, completed_at
  FROM wms_upgrade_run
 ORDER BY run_id;

UPDATE tmp_iws_direct_write_runs r
  LEFT JOIN (
      SELECT release_id, target_fingerprint, COUNT(*) AS registration_match_count
        FROM wms_schema_version
       WHERE status = 'VERIFIED'
       GROUP BY release_id, target_fingerprint
  ) v
    ON CAST(v.release_id AS BINARY) = CAST(r.release_id AS BINARY)
   AND CAST(v.target_fingerprint AS BINARY) = CAST(r.target_fingerprint AS BINARY)
   SET r.registration_match_count = IFNULL(v.registration_match_count, 0);

SET @unsafe_run_count = (
    SELECT COUNT(*)
      FROM tmp_iws_direct_write_runs
     WHERE NOT (
         status = 'COMPLETED'
         AND phase = 'COMPLETE'
         AND completed_at IS NOT NULL
         AND registration_match_count = 1
     )
);

SET @existing_found = (@existing_component_code IS NOT NULL);
SET @existing_matches = (
    @existing_found = 1
    AND CAST(@existing_component_code AS BINARY) = CAST(@component_code AS BINARY)
    AND CAST(@existing_schema_version AS BINARY) = CAST(@schema_version AS BINARY)
    AND CAST(@existing_release_id AS BINARY) = CAST(@release_id AS BINARY)
    AND CAST(@existing_source_fingerprint AS BINARY)
        = CAST(@declared_target_fingerprint AS BINARY)
    AND CAST(@existing_target_fingerprint AS BINARY)
        = CAST(@declared_target_fingerprint AS BINARY)
    AND CAST(@existing_status AS BINARY) = CAST('VERIFIED' AS BINARY)
    AND CAST(@existing_remark AS BINARY) <=> CAST(@registration_remark AS BINARY)
);

INSERT INTO wms_schema_version
    (component_code, schema_version, release_id,
     source_fingerprint, target_fingerprint, status,
     applied_by, applied_at, remark)
SELECT @component_code, @schema_version, @release_id,
       @declared_target_fingerprint, @declared_target_fingerprint, 'VERIFIED',
       @registration_actor, CURRENT_TIMESTAMP(3),
       @registration_remark
 WHERE @existing_found = 0
   AND @preflight_ok = 1
   AND @unsafe_run_count = 0;

SET @registration_action = CASE
    WHEN @existing_found = 0 THEN 'INSERTED'
    WHEN @existing_matches = 1 THEN 'IDEMPOTENT'
    ELSE 'CONFLICT'
END;
SET @registration_ok = (
    @preflight_ok = 1
    AND @unsafe_run_count = 0
    AND @registration_action IN ('INSERTED', 'IDEMPOTENT')
);

SELECT
    @registration_action AS '登记动作',
    @component_code AS '组件编码',
    @schema_version AS '结构版本',
    @release_id AS '发布标识',
    @declared_target_fingerprint AS '登记指纹',
    @registration_remark AS '登记范围备注',
    @unsafe_run_count AS '未安全完成运行数',
    CASE
        WHEN @registration_ok = 1 THEN '通过'
        WHEN @unsafe_run_count > 1 THEN '失败：多个未安全完成的WMS控制运行冲突'
        WHEN @unsafe_run_count = 1 THEN '失败：存在未安全完成的WMS控制运行'
        ELSE '失败：同键异内容、非VERIFIED或schema验收失败'
    END AS '登记结果';

SELECT JSON_EXTRACT(IF(@registration_ok = 1, '{}', 'invalid'), '$')
    AS '可信结构登记硬门禁';

SET @persisted_ok = (
    SELECT COUNT(*) = 1
      FROM wms_schema_version
     WHERE CAST(component_code AS BINARY) = CAST(@component_code AS BINARY)
       AND CAST(schema_version AS BINARY) = CAST(@schema_version AS BINARY)
       AND CAST(release_id AS BINARY) = CAST(@release_id AS BINARY)
       AND CAST(source_fingerprint AS BINARY)
           = CAST(@declared_target_fingerprint AS BINARY)
       AND CAST(target_fingerprint AS BINARY)
           = CAST(@declared_target_fingerprint AS BINARY)
       AND CAST(status AS BINARY) = CAST('VERIFIED' AS BINARY)
);
SELECT JSON_EXTRACT(IF(@persisted_ok = 1, '{}', 'invalid'), '$')
    AS '登记持久化复核';

COMMIT;

SET SESSION group_concat_max_len = @saved_group_concat_max_len;

SELECT
    @component_code AS '组件编码',
    @schema_version AS '结构版本',
    @release_id AS '发布标识',
    @declared_target_fingerprint AS '已登记目标指纹',
    'SOURCE_EQUALS_TARGET' AS '来源关系',
    'COMMITTED' AS '控制记录状态';
