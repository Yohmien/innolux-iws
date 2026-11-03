-- ============================================================================
-- IWS+ 在库空间升级持久控制结构只读验收（INV-P1-03）
-- ============================================================================
-- 目标数据库：MySQL 8.0；本切片已在本机 MySQL 8.0.45 隔离实例验证
-- 验收范围：
--   1. 五张控制表与冻结契约的列、索引、CHECK（含 ENFORCED）、表选项、
--      本表外键、挂载触发器和外部指入外键完全一致；
--      8.0.45 上 ALTER CHECK ... NOT ENFORCED 会改写 CHECK_CLAUSE 的字符集
--      前缀，再 ENFORCED 也不还原；本脚本按存储文本比较，会继续报冲突。
--   2. 目标结构指纹与 iws_inventory_management_upgrade_control.sql 声明一致；
--   3. 检查点/问题引用的 run_id 不存在时报告为完整性失败；
--   4. 只读展示最新升级运行、阻断问题、未完成检查点和业务行数量。
-- 本切片边界：
--   只证明控制结构可验收，不执行回填、不创建运行/检查点/映射/问题行，
--   不证明应用启动门禁、S0—S5 执行器、真实库切换或核心/初始化同版整合已完成。
--   最新运行状态只作只读报告；没有运行行时显示 NOT_STARTED，不把结构通过
--   解释为升级完成。
-- 执行：
--   mysql --no-defaults --protocol=TCP --host=<主机> --port=<端口> \
--     --user=<账号> --default-character-set=utf8mb4 --database=<目标库> \
--     --batch --raw < script/sql/iws_inventory_management_upgrade_control_verify.sql
--   只读查询；使用 stdin 且不加 --force，首个 SQL 错误终止并返回非 0。
--   MySQL 8.0.45 客户端不支持 --abort-source-on-error；任意 GUI 或其他
--   自行续错的执行器不在支持范围。
-- ============================================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 集中参数：必须与升级脚本同版
-- ----------------------------------------------------------------------------
SET @target_schema = 'innolux-iws';
SET @control_release_id = 'INV-P1-03-20261028';
SET @control_contract_version = 'CONTROL-5T-20261028';
SET @expected_mysql_version_prefix = '8.0.';
SET @declared_target_fingerprint = '5e8161d1e6a4182446624443de3992436b0a202faf8fe905c3cf7f4d901a905c';
SET @expected_control_table_count = 5;

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
SET @version_ok = (
    VERSION() LIKE CONCAT(@expected_mysql_version_prefix, '%')
    AND VERSION() NOT LIKE '%MariaDB%'
);

-- ----------------------------------------------------------------------------
-- 一、目标定义期望（仅会话临时表）
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_iws_upgrade_control_expected;
CREATE TEMPORARY TABLE tmp_iws_upgrade_control_expected (
    table_name       varchar(64)   NOT NULL,
    engine           varchar(64)   NOT NULL,
    table_collation  varchar(64)   NOT NULL,
    table_comment    varchar(2048) NOT NULL,
    column_count     int           NOT NULL,
    column_hash      char(64)      NOT NULL,
    index_count      int           NOT NULL,
    index_hash       char(64)      NOT NULL,
    check_count      int           NOT NULL,
    check_disabled_count int       NOT NULL,
    check_hash       char(64)      NOT NULL,
    foreign_key_count int          NOT NULL,
    trigger_count    int           NOT NULL,
    inbound_foreign_key_count int  NOT NULL,
    PRIMARY KEY (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO tmp_iws_upgrade_control_expected
    (table_name, engine, table_collation, table_comment,
     column_count, column_hash, index_count, index_hash,
     check_count, check_disabled_count, check_hash,
     foreign_key_count, trigger_count, inbound_foreign_key_count)
VALUES
    ('wms_legacy_id_map', 'InnoDB', 'utf8mb4_0900_ai_ci',
     'WMS旧业务标识到目标内部标识映射',
     10, 'e4e0f8340077458678451b38c08ce53d037a32a6dd96f63f464550ef09b08511',
     3,  '0c456d0b86ded80a18d52ec1659be2f73eebc8aa1b5d4105345e347082631058',
     1,  0, 'fc10c162e855dfdb6e90b010b9bccdaeb4c2ab3701e0b2f77dfcff573b34ac13',
     0, 0, 0),
    ('wms_schema_version', 'InnoDB', 'utf8mb4_0900_ai_ci',
     'WMS组件结构与发布版本登记',
     10, '7a8fb7936c53add7995b4e84054b383491dca3d6ba55b91e0ad8e361aa0fe725',
     3,  '083eebcb287ff50f4d62d878b0e2f7eae9fe2faad56f9af96dd30b4dcdd736b1',
     1,  0, '59e6eeb83834a0f4580ca1a01cd2ea86886cd9b68aa44606eedeb94bcc2dbd02',
     0, 0, 0),
    ('wms_upgrade_checkpoint', 'InnoDB', 'utf8mb4_0900_ai_ci',
     'WMS库存升级步骤检查点',
     13, '6ffe74b4d05612741533f8891ffac14e805528c696ca4b21f48d909cb2698512',
     3,  'ef7faeaf3a28c6c7263c0ad5d77bd90e3b39683153b43b5cabe1b304322cbe55',
     2,  0, '48ebaeaf6ba44db32633d8d15060933d37d08e7d1e15ed0dada8aadb9325ae32',
     0, 0, 0),
    ('wms_upgrade_issue', 'InnoDB', 'utf8mb4_0900_ai_ci',
     'WMS库存升级中文阻碍清单',
     10, '577ab3252174ddc71241da295b8d324be8f0caf0f6e0fcb7f40ef53df16d8d53',
     3,  '0cf24111e17aa92904650d6f422de106357a5a08a1a7dd88e39fd75b43fca98b',
     2,  0, 'd3f7a08345430231db6d3e157339959896db845b80ae9c03d73ce5cc02c2cfc9',
     0, 0, 0),
    ('wms_upgrade_run', 'InnoDB', 'utf8mb4_0900_ai_ci',
     'WMS库存升级运行',
     14, '8eed82108af9a2422a931bcf62947df91a033a2bccd108296b611eaf47daa6eb',
     3,  '55899703c1d859ae6f0997b165e6f6469b5073777e224b843ea84f71052d8414',
     3,  0, 'dcc49f9a039b772f50aabfc4d4148540bde42783b5c9f2d750a82aa30394d8b3',
     0, 0, 0);

SET @computed_target_fingerprint = (
    SELECT SHA2(CONCAT('[',
        GROUP_CONCAT(
            CAST(JSON_ARRAY(
                table_name, engine, table_collation, table_comment,
                column_count, column_hash, index_count, index_hash,
                check_count, check_disabled_count, check_hash,
                foreign_key_count, trigger_count, inbound_foreign_key_count
            ) AS CHAR CHARACTER SET utf8mb4)
            ORDER BY table_name SEPARATOR ','
        ),
    ']'), 256)
    FROM tmp_iws_upgrade_control_expected
);
SET @declared_fingerprint_ok = (
    CAST(@computed_target_fingerprint AS BINARY)
    = CAST(@declared_target_fingerprint AS BINARY)
);

-- ----------------------------------------------------------------------------
-- 二、实际结构状态
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_iws_upgrade_control_actual;
CREATE TEMPORARY TABLE tmp_iws_upgrade_control_actual (
    table_name       varchar(64)   NOT NULL,
    table_type       varchar(64)   DEFAULT NULL,
    engine           varchar(64)   DEFAULT NULL,
    table_collation  varchar(64)   DEFAULT NULL,
    table_comment    varchar(2048) DEFAULT NULL,
    column_count     int           DEFAULT NULL,
    column_hash      char(64)      DEFAULT NULL,
    index_count      int           DEFAULT NULL,
    index_hash       char(64)      DEFAULT NULL,
    check_count      int           DEFAULT NULL,
    check_disabled_count int       DEFAULT NULL,
    check_hash       char(64)      DEFAULT NULL,
    foreign_key_count int          DEFAULT NULL,
    trigger_count    int           DEFAULT NULL,
    inbound_foreign_key_count int  DEFAULT NULL,
    PRIMARY KEY (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @probe_sql = '
INSERT INTO tmp_iws_upgrade_control_actual
    (table_name, table_type, engine, table_collation, table_comment,
     column_count, column_hash, index_count, index_hash,
     check_count, check_disabled_count, check_hash,
     foreign_key_count, trigger_count, inbound_foreign_key_count)
SELECT
    e.table_name,
    t.TABLE_TYPE,
    t.ENGINE,
    t.TABLE_COLLATION,
    t.TABLE_COMMENT,
    c.column_count,
    c.column_hash,
    i.index_count,
    i.index_hash,
    k.check_count,
    k.check_disabled_count,
    k.check_hash,
    COALESCE(f.foreign_key_count, 0),
    COALESCE(tr.trigger_count, 0),
    COALESCE(fkin.inbound_foreign_key_count, 0)
FROM tmp_iws_upgrade_control_expected e
LEFT JOIN information_schema.TABLES t
    ON t.TABLE_SCHEMA = DATABASE()
   AND t.TABLE_NAME = e.table_name
LEFT JOIN (
    SELECT TABLE_NAME, COUNT(*) AS column_count,
           SHA2(CONCAT(''['',
               GROUP_CONCAT(
                   CAST(JSON_ARRAY(
                       ORDINAL_POSITION, COLUMN_NAME, LOWER(COLUMN_TYPE),
                       IS_NULLABLE, COLUMN_DEFAULT, CHARACTER_SET_NAME,
                       COLLATION_NAME, EXTRA, GENERATION_EXPRESSION, COLUMN_COMMENT
                   ) AS CHAR CHARACTER SET utf8mb4)
                   ORDER BY ORDINAL_POSITION SEPARATOR '',''
               ),
           '']''), 256) AS column_hash
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME IN (''wms_schema_version'',''wms_upgrade_run'',''wms_upgrade_checkpoint'',''wms_legacy_id_map'',''wms_upgrade_issue'')
    GROUP BY TABLE_NAME
) c ON c.TABLE_NAME = e.table_name
LEFT JOIN (
    SELECT TABLE_NAME, COUNT(DISTINCT INDEX_NAME) AS index_count,
           SHA2(CONCAT(''['',
               GROUP_CONCAT(
                   CAST(JSON_ARRAY(
                       INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME,
                       SUB_PART, INDEX_TYPE, COLLATION, EXPRESSION,
                       IS_VISIBLE, INDEX_COMMENT
                   ) AS CHAR CHARACTER SET utf8mb4)
                   ORDER BY INDEX_NAME, SEQ_IN_INDEX SEPARATOR '',''
               ),
           '']''), 256) AS index_hash
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME IN (''wms_schema_version'',''wms_upgrade_run'',''wms_upgrade_checkpoint'',''wms_legacy_id_map'',''wms_upgrade_issue'')
    GROUP BY TABLE_NAME
) i ON i.TABLE_NAME = e.table_name
LEFT JOIN (
    SELECT tc.TABLE_NAME, COUNT(*) AS check_count,
           SUM(CASE WHEN tc.ENFORCED = ''YES'' THEN 0 ELSE 1 END) AS check_disabled_count,
           SHA2(CONCAT(''['',
               GROUP_CONCAT(
                   CAST(JSON_ARRAY(
                       cc.CONSTRAINT_NAME, tc.ENFORCED, cc.CHECK_CLAUSE
                   ) AS CHAR CHARACTER SET utf8mb4)
                   ORDER BY cc.CONSTRAINT_NAME SEPARATOR '',''
               ),
           '']''), 256) AS check_hash
    FROM information_schema.TABLE_CONSTRAINTS tc
    JOIN information_schema.CHECK_CONSTRAINTS cc
      ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
     AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
    WHERE tc.TABLE_SCHEMA = DATABASE()
      AND tc.CONSTRAINT_TYPE = ''CHECK''
      AND tc.TABLE_NAME IN (''wms_schema_version'',''wms_upgrade_run'',''wms_upgrade_checkpoint'',''wms_legacy_id_map'',''wms_upgrade_issue'')
    GROUP BY tc.TABLE_NAME
) k ON k.TABLE_NAME = e.table_name
LEFT JOIN (
    SELECT TABLE_NAME, COUNT(*) AS foreign_key_count
    FROM information_schema.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME IN (''wms_schema_version'',''wms_upgrade_run'',''wms_upgrade_checkpoint'',''wms_legacy_id_map'',''wms_upgrade_issue'')
    GROUP BY TABLE_NAME
) f ON f.TABLE_NAME = e.table_name
LEFT JOIN (
    SELECT EVENT_OBJECT_TABLE AS TABLE_NAME, COUNT(*) AS trigger_count
    FROM information_schema.TRIGGERS
    WHERE EVENT_OBJECT_SCHEMA = DATABASE()
      AND EVENT_OBJECT_TABLE IN (''wms_schema_version'',''wms_upgrade_run'',''wms_upgrade_checkpoint'',''wms_legacy_id_map'',''wms_upgrade_issue'')
    GROUP BY EVENT_OBJECT_TABLE
) tr ON tr.TABLE_NAME = e.table_name
LEFT JOIN (
    SELECT REFERENCED_TABLE_NAME AS TABLE_NAME,
           COUNT(*) AS inbound_foreign_key_count
    FROM information_schema.REFERENTIAL_CONSTRAINTS
    WHERE UNIQUE_CONSTRAINT_SCHEMA = DATABASE()
      AND REFERENCED_TABLE_NAME IN (''wms_schema_version'',''wms_upgrade_run'',''wms_upgrade_checkpoint'',''wms_legacy_id_map'',''wms_upgrade_issue'')
    GROUP BY REFERENCED_TABLE_NAME
) fkin ON fkin.TABLE_NAME = e.table_name
WHERE e.table_name = @probe_table';
PREPARE stmt_iws_control_probe FROM @probe_sql;

TRUNCATE TABLE tmp_iws_upgrade_control_actual;
SET @probe_table = 'wms_legacy_id_map';
EXECUTE stmt_iws_control_probe;
SET @probe_table = 'wms_schema_version';
EXECUTE stmt_iws_control_probe;
SET @probe_table = 'wms_upgrade_checkpoint';
EXECUTE stmt_iws_control_probe;
SET @probe_table = 'wms_upgrade_issue';
EXECUTE stmt_iws_control_probe;
SET @probe_table = 'wms_upgrade_run';
EXECUTE stmt_iws_control_probe;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_upgrade_control_state;
CREATE TEMPORARY TABLE tmp_iws_upgrade_control_state (
    table_name       varchar(64) NOT NULL,
    state            varchar(16) NOT NULL,
    table_type       varchar(64) DEFAULT NULL,
    engine           varchar(64) DEFAULT NULL,
    table_collation  varchar(64) DEFAULT NULL,
    table_comment    varchar(2048) DEFAULT NULL,
    column_count     int         DEFAULT NULL,
    column_hash      char(64)    DEFAULT NULL,
    index_count      int         DEFAULT NULL,
    index_hash       char(64)    DEFAULT NULL,
    check_count      int         DEFAULT NULL,
    check_disabled_count int     DEFAULT NULL,
    check_hash       char(64)    DEFAULT NULL,
    foreign_key_count int        DEFAULT NULL,
    trigger_count    int         DEFAULT NULL,
    inbound_foreign_key_count int DEFAULT NULL,
    PRIMARY KEY (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO tmp_iws_upgrade_control_state
SELECT
    e.table_name,
    CASE
        WHEN a.table_type IS NULL THEN 'MISSING'
        WHEN a.table_type <> 'BASE TABLE' THEN 'CONFLICT'
        WHEN NOT (CAST(a.engine AS BINARY) <=> CAST(e.engine AS BINARY)) THEN 'CONFLICT'
        WHEN NOT (CAST(a.table_collation AS BINARY) <=> CAST(e.table_collation AS BINARY)) THEN 'CONFLICT'
        WHEN NOT (CAST(a.table_comment AS BINARY) <=> CAST(e.table_comment AS BINARY)) THEN 'CONFLICT'
        WHEN NOT (a.column_count <=> e.column_count) THEN 'CONFLICT'
        WHEN NOT (CAST(a.column_hash AS BINARY) <=> CAST(e.column_hash AS BINARY)) THEN 'CONFLICT'
        WHEN NOT (a.index_count <=> e.index_count) THEN 'CONFLICT'
        WHEN NOT (CAST(a.index_hash AS BINARY) <=> CAST(e.index_hash AS BINARY)) THEN 'CONFLICT'
        WHEN NOT (a.check_count <=> e.check_count) THEN 'CONFLICT'
        WHEN NOT (a.check_disabled_count <=> e.check_disabled_count) THEN 'CONFLICT'
        WHEN NOT (CAST(a.check_hash AS BINARY) <=> CAST(e.check_hash AS BINARY)) THEN 'CONFLICT'
        WHEN NOT (a.foreign_key_count <=> e.foreign_key_count) THEN 'CONFLICT'
        WHEN NOT (a.trigger_count <=> e.trigger_count) THEN 'CONFLICT'
        WHEN NOT (a.inbound_foreign_key_count <=> e.inbound_foreign_key_count) THEN 'CONFLICT'
        ELSE 'MATCHED'
    END,
    a.table_type,
    a.engine,
    a.table_collation,
    a.table_comment,
    a.column_count,
    a.column_hash,
    a.index_count,
    a.index_hash,
    a.check_count,
    a.check_disabled_count,
    a.check_hash,
    a.foreign_key_count,
    a.trigger_count,
    a.inbound_foreign_key_count
FROM tmp_iws_upgrade_control_expected e
JOIN tmp_iws_upgrade_control_actual a
  ON a.table_name = e.table_name;

SET @matched_count = (SELECT COUNT(*) FROM tmp_iws_upgrade_control_state WHERE state = 'MATCHED');
SET @missing_count = (SELECT COUNT(*) FROM tmp_iws_upgrade_control_state WHERE state = 'MISSING');
SET @conflict_count = (SELECT COUNT(*) FROM tmp_iws_upgrade_control_state WHERE state = 'CONFLICT');
SET @check_disabled_count = (
    SELECT COALESCE(SUM(check_disabled_count), 0) FROM tmp_iws_upgrade_control_state
);
SET @trigger_count = (
    SELECT COALESCE(SUM(trigger_count), 0) FROM tmp_iws_upgrade_control_state
);
SET @inbound_fk_count = (
    SELECT COALESCE(SUM(inbound_foreign_key_count), 0) FROM tmp_iws_upgrade_control_state
);
SET @actual_target_fingerprint = (
    SELECT SHA2(CONCAT('[',
        GROUP_CONCAT(
            CAST(JSON_ARRAY(
                table_name, engine, table_collation, table_comment,
                column_count, column_hash, index_count, index_hash,
                check_count, check_disabled_count, check_hash,
                foreign_key_count, trigger_count, inbound_foreign_key_count
            ) AS CHAR CHARACTER SET utf8mb4)
            ORDER BY table_name SEPARATOR ','
        ),
    ']'), 256)
    FROM tmp_iws_upgrade_control_actual
    WHERE table_type = 'BASE TABLE'
);
SET @structure_ok = (
    @metadata_visibility_ok = 1
    AND @schema_ok = 1
    AND @version_ok = 1
    AND @declared_fingerprint_ok = 1
    AND @matched_count = @expected_control_table_count
    AND @missing_count = 0
    AND @conflict_count = 0
    AND @check_disabled_count = 0
    AND @trigger_count = 0
    AND @inbound_fk_count = 0
    AND CAST(@actual_target_fingerprint AS BINARY) = CAST(@declared_target_fingerprint AS BINARY)
);

SELECT
    s.table_name AS '控制表',
    s.state AS '状态',
    CASE s.state
        WHEN 'MATCHED' THEN '通过'
        WHEN 'MISSING' THEN '缺失'
        ELSE '定义冲突'
    END AS '中文结果',
    s.column_count AS '列数',
    CASE WHEN CAST(s.column_hash AS BINARY) = CAST(e.column_hash AS BINARY) THEN '一致' ELSE '不一致' END AS '列签名',
    s.index_count AS '索引数',
    CASE WHEN CAST(s.index_hash AS BINARY) = CAST(e.index_hash AS BINARY) THEN '一致' ELSE '不一致' END AS '索引签名',
    s.check_count AS 'CHECK数',
    s.check_disabled_count AS '禁用CHECK数',
    CASE WHEN CAST(s.check_hash AS BINARY) = CAST(e.check_hash AS BINARY) THEN '一致' ELSE '不一致' END AS 'CHECK签名',
    s.foreign_key_count AS '本表外键数',
    s.trigger_count AS '挂载触发器数',
    s.inbound_foreign_key_count AS '外部指入外键数'
FROM tmp_iws_upgrade_control_state s
JOIN tmp_iws_upgrade_control_expected e ON e.table_name = s.table_name
ORDER BY s.table_name;

SELECT
    @target_schema AS '目标库',
    DATABASE() AS '当前库',
    VERSION() AS '数据库版本',
    IF(@metadata_visibility_ok = 1, '通过', '权限不足') AS '元数据权限',
    @control_release_id AS '发布标识',
    @control_contract_version AS '控制契约',
    @matched_count AS '通过控制表数',
    @missing_count AS '缺失控制表数',
    @conflict_count AS '冲突控制表数',
    @check_disabled_count AS '禁用CHECK数',
    @trigger_count AS '未知触发器数',
    @inbound_fk_count AS '外部指入外键数',
    @declared_target_fingerprint AS '目标结构指纹',
    @actual_target_fingerprint AS '实际结构指纹',
    IF(@structure_ok = 1, '通过', '失败') AS '控制结构结果';

SELECT
    '禁用CHECK' AS '对象类型',
    tc.CONSTRAINT_NAME AS '对象名称',
    tc.TABLE_NAME AS '关联控制表',
    CONCAT('ENFORCED=', tc.ENFORCED) AS '说明'
FROM information_schema.TABLE_CONSTRAINTS tc
WHERE tc.TABLE_SCHEMA = DATABASE()
  AND tc.CONSTRAINT_TYPE = 'CHECK'
  AND tc.ENFORCED <> 'YES'
  AND tc.TABLE_NAME IN ('wms_schema_version','wms_upgrade_run','wms_upgrade_checkpoint','wms_legacy_id_map','wms_upgrade_issue')
UNION ALL
SELECT
    '未知触发器',
    tr.TRIGGER_NAME,
    tr.EVENT_OBJECT_TABLE,
    CONCAT(tr.ACTION_TIMING, ' ', tr.EVENT_MANIPULATION)
FROM information_schema.TRIGGERS tr
WHERE tr.EVENT_OBJECT_SCHEMA = DATABASE()
  AND tr.EVENT_OBJECT_TABLE IN ('wms_schema_version','wms_upgrade_run','wms_upgrade_checkpoint','wms_legacy_id_map','wms_upgrade_issue')
UNION ALL
SELECT
    '外部指入外键',
    rc.CONSTRAINT_NAME,
    rc.REFERENCED_TABLE_NAME,
    CONCAT('来自表 ', rc.CONSTRAINT_SCHEMA, '.', rc.TABLE_NAME, '，更新规则 ', rc.UPDATE_RULE, '，删除规则 ', rc.DELETE_RULE)
FROM information_schema.REFERENTIAL_CONSTRAINTS rc
WHERE rc.UNIQUE_CONSTRAINT_SCHEMA = DATABASE()
  AND rc.REFERENCED_TABLE_NAME IN ('wms_schema_version','wms_upgrade_run','wms_upgrade_checkpoint','wms_legacy_id_map','wms_upgrade_issue')
ORDER BY 1, 3, 2;

SELECT JSON_EXTRACT(IF(@structure_ok = 1, '{}', 'invalid'), '$') AS '控制结构前置门禁';

-- ----------------------------------------------------------------------------
-- 三、控制数据完整性与执行状态（只读）
-- ----------------------------------------------------------------------------
SET @run_count = (SELECT COUNT(*) FROM wms_upgrade_run);
SET @checkpoint_count = (SELECT COUNT(*) FROM wms_upgrade_checkpoint);
SET @legacy_map_count = (SELECT COUNT(*) FROM wms_legacy_id_map);
SET @issue_count = (SELECT COUNT(*) FROM wms_upgrade_issue);
SET @schema_version_count = (
    SELECT COUNT(*) FROM wms_schema_version
    WHERE release_id = @control_release_id
);
SET @orphan_checkpoint_count = (
    SELECT COUNT(*)
    FROM wms_upgrade_checkpoint c
    LEFT JOIN wms_upgrade_run r ON r.run_id = c.run_id
    WHERE r.run_id IS NULL
);
SET @orphan_issue_count = (
    SELECT COUNT(*)
    FROM wms_upgrade_issue i
    LEFT JOIN wms_upgrade_run r ON r.run_id = i.run_id
    WHERE r.run_id IS NULL
);
SET @latest_run_id = (SELECT MAX(run_id) FROM wms_upgrade_run);
SET @latest_run_status = (
    SELECT status FROM wms_upgrade_run WHERE run_id = @latest_run_id
);
SET @latest_run_phase = (
    SELECT phase FROM wms_upgrade_run WHERE run_id = @latest_run_id
);
SET @latest_blocking_issue_count = (
    SELECT COUNT(*) FROM wms_upgrade_issue
    WHERE run_id = @latest_run_id AND is_blocking = 1
);
SET @latest_open_checkpoint_count = (
    SELECT COUNT(*) FROM wms_upgrade_checkpoint
    WHERE run_id = @latest_run_id AND status IN ('PENDING','RUNNING')
);
SET @latest_failed_checkpoint_count = (
    SELECT COUNT(*) FROM wms_upgrade_checkpoint
    WHERE run_id = @latest_run_id AND status = 'FAILED'
);
SET @execution_state = CASE
    WHEN @latest_run_id IS NULL THEN 'NOT_STARTED'
    WHEN @latest_run_status = 'COMPLETED'
         AND @latest_blocking_issue_count = 0
         AND @latest_open_checkpoint_count = 0
         AND @latest_failed_checkpoint_count = 0 THEN 'COMPLETE'
    WHEN @latest_run_status = 'BLOCKED' OR @latest_blocking_issue_count > 0 THEN 'BLOCKED'
    WHEN @latest_run_status = 'FAILED' OR @latest_failed_checkpoint_count > 0 THEN 'FAILED'
    ELSE 'INCOMPLETE'
END;
SET @integrity_ok = (
    @metadata_visibility_ok = 1
    AND @structure_ok = 1
    AND @orphan_checkpoint_count = 0
    AND @orphan_issue_count = 0
);

SELECT
    @run_count AS '升级运行数',
    @checkpoint_count AS '检查点数',
    @legacy_map_count AS '旧标识映射数',
    @issue_count AS '升级问题数',
    @schema_version_count AS '本发布版本登记数';

SELECT
    @latest_run_id AS '最新运行ID',
    @latest_run_phase AS '最新阶段',
    @latest_run_status AS '最新状态',
    @latest_blocking_issue_count AS '最新运行阻断问题数',
    @latest_open_checkpoint_count AS '最新运行未完成检查点数',
    @latest_failed_checkpoint_count AS '最新运行失败检查点数',
    @execution_state AS '升级执行状态',
    CASE @execution_state
        WHEN 'NOT_STARTED' THEN '尚未登记升级运行；本切片只验证控制结构'
        WHEN 'COMPLETE' THEN '最新运行已完成且无阻断问题'
        WHEN 'BLOCKED' THEN '最新运行存在阻断问题或阻断状态'
        WHEN 'FAILED' THEN '最新运行或检查点失败'
        ELSE '最新运行尚未完成，禁止声称升级完成'
    END AS '中文说明';

SELECT
    @orphan_checkpoint_count AS '孤立检查点数',
    @orphan_issue_count AS '孤立问题数',
    IF(@metadata_visibility_ok = 1, '通过', '权限不足') AS '元数据权限',
    IF(@integrity_ok = 1, '通过', '失败') AS '控制数据完整性结果',
    IF(@integrity_ok = 1,
       '控制结构只读验收通过；回填、应用门禁和核心/初始化同版整合未验证',
       IF(@metadata_visibility_ok = 0,
          '权限不足：禁止进入回填或切换',
          '控制结构或控制数据完整性失败，禁止进入回填或切换')) AS '切片边界';

SELECT JSON_EXTRACT(IF(@integrity_ok = 1, '{}', 'invalid'), '$') AS '控制结构验收门禁';

DEALLOCATE PREPARE stmt_iws_control_probe;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_upgrade_control_state;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_upgrade_control_actual;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_upgrade_control_expected;

SET SESSION group_concat_max_len = @saved_group_concat_max_len;
