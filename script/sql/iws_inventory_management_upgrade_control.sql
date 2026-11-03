-- ============================================================================
-- IWS+ 在库空间升级持久控制结构（INV-P1-03）
-- ============================================================================
-- 目标数据库：MySQL 8.0；本切片已在本机 MySQL 8.0.45 隔离实例验证
-- 目标对象：
--   wms_schema_version      组件结构与发布版本登记
--   wms_upgrade_run         升级运行、阶段、写冻结令牌和错误状态
--   wms_upgrade_checkpoint  运行内步骤/对象检查点
--   wms_legacy_id_map       旧业务标识到目标内部标识映射
--   wms_upgrade_issue       中文脱敏阻碍清单
-- 契约来源：
--   docs/inventory-p1-contract-20261027.md §5.6、§6、§11.1
--   docs/inventory-management-sql-upgrade-plan-20261026.md §3—§5、§8
--   主方案 A18、A37—A39；任务 INV-P1-03 的 TG05 断点与重跑验收
-- 本切片边界：
--   只创建五张控制表。脚本不创建运行、检查点、映射或问题业务行；不执行
--   S0—S5 回填，不实现应用启动门禁，不修改公司/空间 S1/核心/初始化共享 SQL。
--   同一业务键异摘要冲突由唯一键先拒绝重复写入，执行器必须读取既有行、
--   比较摘要并决定跳过、更新或阻断；该比较逻辑属于后续升级执行器，不在本文件。
-- 幂等与状态：
--   每次执行从 information_schema 读取五张表的完整定义：
--     ABSENT   五张均缺失，创建全部；
--     PARTIAL  部分表已与目标完全一致，只补缺失表；
--     TARGET   五张均与目标完全一致，全部跳过；
--     CONFLICT 任一同名对象缺失、类型/引擎/排序规则/注释/列/索引/CHECK/
--              外键/触发器/外部指入外键与目标不一致，前置门禁失败，不执行
--              任何 CREATE。
--   多语句 DDL 不承诺整体回滚；每张表创建后按完整目标元数据复核，一致才
--   推进下一张；DDL 出错或复核不一致时不推进。
--   首个 SQL 错误必须停止并保留现场；禁止 --force。
-- 前置：
--   连接已选择唯一目标库；部署账号可读 information_schema，并具备 CREATE、
--   CREATE TEMPORARY TABLES 权限。本脚本不创建存储过程、函数、触发器和事件。
-- 参数：
--   目标库、发布标识、控制契约、MySQL 分支和目标结构指纹集中在下方。
--   执行前只修改 @target_schema；其他参数必须与批准的契约清单同版。
-- 执行：
--   mysql --no-defaults --protocol=TCP --host=<主机> --port=<端口> \
--     --user=<账号> --default-character-set=utf8mb4 --database=<目标库> \
--     --batch --raw < script/sql/iws_inventory_management_upgrade_control.sql
--   使用 stdin 重定向；MySQL 8.0.45 客户端不支持 --abort-source-on-error，
--   默认（未加 --force）在首个 SQL 错误处终止并返回非 0。本机实测 stdin 与
--   -e "source <文件>" 均为首错退出 1 且后续语句不执行。
--   必须同一连接串行执行，保持同一会话临时表。任意 GUI 或其他自行续错的
--   执行器不在支持范围，脚本不承诺其失败后不推进；禁止添加 --force。
-- 后续集成清单：
--   iws_wms_smt_core.sql、iws_init_from_empty.sql、iws_inventory_management_upgrade.sql、
--   iws_inventory_management_verify.sql、iws_inventory_management_acceptance.sql 与应用
--   启动门禁须在同一 release 纳入本文件的对象定义、目标指纹和执行状态。
-- ============================================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 集中参数
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

-- ----------------------------------------------------------------------------
-- 一、目标库与版本前置
-- ----------------------------------------------------------------------------
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
    VERSION() LIKE CONCAT(@expected_mysql_version_prefix, '%')
    AND VERSION() NOT LIKE '%MariaDB%'
);

-- ----------------------------------------------------------------------------
-- 二、目标定义期望（仅会话临时表，不落库）
-- ----------------------------------------------------------------------------
-- 表级签名覆盖：表类型、InnoDB、排序规则、表注释、列数/完整列签名、
-- 索引数/完整索引签名、CHECK 数/启用状态/完整签名、本表外键数量、
-- 挂载触发器数量、外部指入外键数量。
-- CHECK 签名包含 TABLE_CONSTRAINTS.ENFORCED，必须为 YES；挂载触发器必须
-- 为 0，外部指入外键必须为 0。触发器按 EVENT_OBJECT_TABLE 归属五表统计；
-- 其他表上的触发器不在本切片识别范围，不能据此声称全库无触发器。
-- 注意：MySQL 8.0.45 上 ALTER CHECK ... NOT ENFORCED 会把 CHECK_CLAUSE 的
-- 字符集前缀改为 ascii，再 ENFORCED 也不会还原；本脚本按存储文本比较，
-- 因此会继续报定义冲突，须按目标 DDL 重建约束。
-- 列签名包含顺序、名称、类型、空值、默认值、字符集、排序规则、额外属性、
-- 生成表达式和注释；索引签名包含唯一性、顺序、列、前缀、类型、排序方向、
-- 表达式、可见性和索引注释。
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
-- 三、读取当前控制表状态
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

-- 用同一段元数据 SQL 读取指定表：@probe_table 决定范围。前置、逐表 DDL 后
-- 与最终复核复用该语句，避免三处口径漂移。
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

SET @control_matched_before = (SELECT COUNT(*) FROM tmp_iws_upgrade_control_state WHERE state = 'MATCHED');
SET @control_missing_before = (SELECT COUNT(*) FROM tmp_iws_upgrade_control_state WHERE state = 'MISSING');
SET @control_conflict_before = (SELECT COUNT(*) FROM tmp_iws_upgrade_control_state WHERE state = 'CONFLICT');
SET @control_check_disabled_before = (
    SELECT COALESCE(SUM(check_disabled_count), 0) FROM tmp_iws_upgrade_control_state
);
SET @control_trigger_before = (
    SELECT COALESCE(SUM(trigger_count), 0) FROM tmp_iws_upgrade_control_state
);
SET @control_inbound_fk_before = (
    SELECT COALESCE(SUM(inbound_foreign_key_count), 0) FROM tmp_iws_upgrade_control_state
);
SET @control_state_before = CASE
    WHEN @control_conflict_before > 0 THEN 'CONFLICT'
    WHEN @control_matched_before = @expected_control_table_count THEN 'TARGET'
    WHEN @control_matched_before = 0 AND @control_missing_before = @expected_control_table_count THEN 'ABSENT'
    ELSE 'PARTIAL'
END;

-- ----------------------------------------------------------------------------
-- 四、前置汇总与错误门控
-- ----------------------------------------------------------------------------
SET @control_preflight_ok = (
    @metadata_visibility_ok = 1
    AND @schema_ok = 1
    AND @schema_exists = 1
    AND @version_ok = 1
    AND @declared_fingerprint_ok = 1
    AND @control_conflict_before = 0
);

SELECT
    @target_schema AS '目标库参数',
    DATABASE() AS '当前库',
    VERSION() AS '数据库版本',
    IF(@metadata_visibility_ok = 1, '通过', '权限不足') AS '元数据权限',
    @control_release_id AS '发布标识',
    @control_contract_version AS '控制契约',
    @control_state_before AS '源结构状态',
    @control_matched_before AS '已满足控制表数',
    @control_missing_before AS '缺失控制表数',
    @control_conflict_before AS '结构冲突数',
    @control_check_disabled_before AS '禁用CHECK数',
    @control_trigger_before AS '未知触发器数',
    @control_inbound_fk_before AS '外部指入外键数',
    @declared_fingerprint_ok AS '声明指纹与目标定义一致',
    IF(@control_preflight_ok = 1, '通过', '失败：元数据权限、目标库、版本、目标定义、控制表结构或未知对象门禁') AS '前置结果';

SELECT
    table_name AS '控制表',
    state AS '源结构状态',
    CASE state
        WHEN 'MATCHED' THEN '已满足'
        WHEN 'MISSING' THEN '缺失'
        ELSE '定义冲突'
    END AS '中文状态',
    CASE
        WHEN @control_preflight_ok = 0 THEN 'BLOCK'
        WHEN state = 'MISSING' THEN 'CREATE'
        WHEN state = 'MATCHED' THEN 'SKIP'
        ELSE 'BLOCK'
    END AS '本次动作'
FROM tmp_iws_upgrade_control_state
ORDER BY table_name;

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

SELECT JSON_EXTRACT(IF(@control_preflight_ok = 1, '{}', 'invalid'), '$') AS '控制表前置门禁';

-- ----------------------------------------------------------------------------
-- 五、附加 DDL：逐表由前置状态和成功链门控
-- ----------------------------------------------------------------------------
SET @ddl_chain_ok = @control_preflight_ok;

SET @wms_schema_version_action = (
    SELECT state FROM tmp_iws_upgrade_control_state WHERE table_name = 'wms_schema_version'
);
SET @ddl_sql = IF(
    @ddl_chain_ok = 1 AND @wms_schema_version_action = 'MISSING',
    'CREATE TABLE wms_schema_version (
        schema_version_id bigint NOT NULL AUTO_INCREMENT COMMENT ''主键，系统生成'',
        component_code varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT ''组件编码，机器稳定标识'',
        schema_version varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT ''组件结构版本'',
        release_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT ''发布标识'',
        source_fingerprint char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT ''升级前源结构指纹，SHA-256 小写十六进制'',
        target_fingerprint char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT ''目标结构指纹，SHA-256 小写十六进制'',
        status varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT ''状态：PENDING/APPLIED/VERIFIED/FAILED'',
        applied_by varchar(64) NOT NULL COMMENT ''应用人或执行器标识'',
        applied_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''应用时间'',
        remark varchar(500) DEFAULT NULL COMMENT ''备注'',
        PRIMARY KEY (schema_version_id),
        UNIQUE KEY uk_wms_schema_version_component_version (component_code, schema_version),
        KEY idx_wms_schema_version_component_status (component_code, status, applied_at),
        CONSTRAINT ck_wms_schema_version_status CHECK (status IN (''PENDING'',''APPLIED'',''VERIFIED'',''FAILED''))
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS组件结构与发布版本登记''',
    'DO 0'
);
PREPARE stmt_iws_control_schema_version FROM @ddl_sql;
EXECUTE stmt_iws_control_schema_version;
DEALLOCATE PREPARE stmt_iws_control_schema_version;
DELETE FROM tmp_iws_upgrade_control_actual WHERE table_name = 'wms_schema_version';
SET @probe_table = 'wms_schema_version';
EXECUTE stmt_iws_control_probe;
SET @ddl_chain_ok = IF(
    @ddl_chain_ok = 1
    AND (SELECT COUNT(*) = 1
         FROM tmp_iws_upgrade_control_actual a
         JOIN tmp_iws_upgrade_control_expected e
           ON e.table_name = a.table_name
         WHERE a.table_name = 'wms_schema_version'
           AND a.table_type <=> 'BASE TABLE'
           AND CAST(a.engine AS BINARY) <=> CAST(e.engine AS BINARY)
           AND CAST(a.table_collation AS BINARY) <=> CAST(e.table_collation AS BINARY)
           AND CAST(a.table_comment AS BINARY) <=> CAST(e.table_comment AS BINARY)
           AND a.column_count <=> e.column_count
           AND CAST(a.column_hash AS BINARY) <=> CAST(e.column_hash AS BINARY)
           AND a.index_count <=> e.index_count
           AND CAST(a.index_hash AS BINARY) <=> CAST(e.index_hash AS BINARY)
           AND a.check_count <=> e.check_count
           AND a.check_disabled_count <=> e.check_disabled_count
           AND CAST(a.check_hash AS BINARY) <=> CAST(e.check_hash AS BINARY)
           AND a.foreign_key_count <=> e.foreign_key_count
           AND a.trigger_count <=> e.trigger_count
           AND a.inbound_foreign_key_count <=> e.inbound_foreign_key_count),
    1, 0
);

SET @wms_upgrade_run_action = (
    SELECT state FROM tmp_iws_upgrade_control_state WHERE table_name = 'wms_upgrade_run'
);
SET @ddl_sql = IF(
    @ddl_chain_ok = 1 AND @wms_upgrade_run_action = 'MISSING',
    'CREATE TABLE wms_upgrade_run (
        run_id bigint NOT NULL AUTO_INCREMENT COMMENT ''升级运行主键，系统生成'',
        release_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT ''发布标识'',
        source_fingerprint char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT ''源结构指纹，SHA-256 小写十六进制'',
        target_fingerprint char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT ''目标结构指纹，SHA-256 小写十六进制'',
        phase varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''SOURCE_VERIFIED'' COMMENT ''阶段：SOURCE_VERIFIED/CONTROL_READY/DDL_PARTIAL/DDL_TARGET/BACKFILL_PARTIAL/DATA_TARGET/CUTOVER_READY/COMPLETE/BLOCKED'',
        write_fence_token varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT ''写冻结令牌，用于关联停止旧写入的证据'',
        status varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''RUNNING'' COMMENT ''状态：RUNNING/BLOCKED/FAILED/COMPLETED'',
        attempt_count int NOT NULL DEFAULT 0 COMMENT ''已尝试次数'',
        error_code varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT ''失败或阻断机器码'',
        error_message varchar(1000) DEFAULT NULL COMMENT ''中文失败或阻断说明，禁止写入敏感原文'',
        started_by varchar(64) NOT NULL COMMENT ''启动人或执行器标识'',
        started_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''开始时间'',
        updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',
        completed_at datetime(3) DEFAULT NULL COMMENT ''完成时间'',
        PRIMARY KEY (run_id),
        KEY idx_wms_upgrade_run_release_started (release_id, started_at),
        KEY idx_wms_upgrade_run_status_phase (status, phase, updated_at),
        CONSTRAINT ck_wms_upgrade_run_phase CHECK (phase IN (''SOURCE_VERIFIED'',''CONTROL_READY'',''DDL_PARTIAL'',''DDL_TARGET'',''BACKFILL_PARTIAL'',''DATA_TARGET'',''CUTOVER_READY'',''COMPLETE'',''BLOCKED'')),
        CONSTRAINT ck_wms_upgrade_run_status CHECK (status IN (''RUNNING'',''BLOCKED'',''FAILED'',''COMPLETED'')),
        CONSTRAINT ck_wms_upgrade_run_attempt CHECK (attempt_count >= 0)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS库存升级运行''',
    'DO 0'
);
PREPARE stmt_iws_control_run FROM @ddl_sql;
EXECUTE stmt_iws_control_run;
DEALLOCATE PREPARE stmt_iws_control_run;
DELETE FROM tmp_iws_upgrade_control_actual WHERE table_name = 'wms_upgrade_run';
SET @probe_table = 'wms_upgrade_run';
EXECUTE stmt_iws_control_probe;
SET @ddl_chain_ok = IF(
    @ddl_chain_ok = 1
    AND (SELECT COUNT(*) = 1
         FROM tmp_iws_upgrade_control_actual a
         JOIN tmp_iws_upgrade_control_expected e
           ON e.table_name = a.table_name
         WHERE a.table_name = 'wms_upgrade_run'
           AND a.table_type <=> 'BASE TABLE'
           AND CAST(a.engine AS BINARY) <=> CAST(e.engine AS BINARY)
           AND CAST(a.table_collation AS BINARY) <=> CAST(e.table_collation AS BINARY)
           AND CAST(a.table_comment AS BINARY) <=> CAST(e.table_comment AS BINARY)
           AND a.column_count <=> e.column_count
           AND CAST(a.column_hash AS BINARY) <=> CAST(e.column_hash AS BINARY)
           AND a.index_count <=> e.index_count
           AND CAST(a.index_hash AS BINARY) <=> CAST(e.index_hash AS BINARY)
           AND a.check_count <=> e.check_count
           AND a.check_disabled_count <=> e.check_disabled_count
           AND CAST(a.check_hash AS BINARY) <=> CAST(e.check_hash AS BINARY)
           AND a.foreign_key_count <=> e.foreign_key_count
           AND a.trigger_count <=> e.trigger_count
           AND a.inbound_foreign_key_count <=> e.inbound_foreign_key_count),
    1, 0
);

SET @wms_upgrade_checkpoint_action = (
    SELECT state FROM tmp_iws_upgrade_control_state WHERE table_name = 'wms_upgrade_checkpoint'
);
SET @ddl_sql = IF(
    @ddl_chain_ok = 1 AND @wms_upgrade_checkpoint_action = 'MISSING',
    'CREATE TABLE wms_upgrade_checkpoint (
        checkpoint_id bigint NOT NULL AUTO_INCREMENT COMMENT ''升级检查点主键，系统生成'',
        run_id bigint NOT NULL COMMENT ''所属升级运行，逻辑关联 wms_upgrade_run.run_id'',
        step_code varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT ''步骤编码，机器稳定标识'',
        object_key varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''对象或批次稳定键，精确比较'',
        status varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''PENDING'' COMMENT ''状态：PENDING/RUNNING/APPLIED/VERIFIED/FAILED/SKIPPED'',
        before_digest char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT ''执行前摘要，SHA-256 小写十六进制'',
        after_digest char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT ''执行后摘要，SHA-256 小写十六进制'',
        attempt_count int NOT NULL DEFAULT 0 COMMENT ''该步骤已尝试次数'',
        error_code varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT ''失败或阻断机器码'',
        error_message varchar(1000) DEFAULT NULL COMMENT ''中文失败或阻断说明，禁止写入敏感原文'',
        started_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''开始时间'',
        updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',
        completed_at datetime(3) DEFAULT NULL COMMENT ''完成时间'',
        PRIMARY KEY (checkpoint_id),
        UNIQUE KEY uk_wms_upgrade_checkpoint_run_step_object (run_id, step_code, object_key),
        KEY idx_wms_upgrade_checkpoint_run_status (run_id, status, updated_at),
        CONSTRAINT ck_wms_upgrade_checkpoint_status CHECK (status IN (''PENDING'',''RUNNING'',''APPLIED'',''VERIFIED'',''FAILED'',''SKIPPED'')),
        CONSTRAINT ck_wms_upgrade_checkpoint_attempt CHECK (attempt_count >= 0)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS库存升级步骤检查点''',
    'DO 0'
);
PREPARE stmt_iws_control_checkpoint FROM @ddl_sql;
EXECUTE stmt_iws_control_checkpoint;
DEALLOCATE PREPARE stmt_iws_control_checkpoint;
DELETE FROM tmp_iws_upgrade_control_actual WHERE table_name = 'wms_upgrade_checkpoint';
SET @probe_table = 'wms_upgrade_checkpoint';
EXECUTE stmt_iws_control_probe;
SET @ddl_chain_ok = IF(
    @ddl_chain_ok = 1
    AND (SELECT COUNT(*) = 1
         FROM tmp_iws_upgrade_control_actual a
         JOIN tmp_iws_upgrade_control_expected e
           ON e.table_name = a.table_name
         WHERE a.table_name = 'wms_upgrade_checkpoint'
           AND a.table_type <=> 'BASE TABLE'
           AND CAST(a.engine AS BINARY) <=> CAST(e.engine AS BINARY)
           AND CAST(a.table_collation AS BINARY) <=> CAST(e.table_collation AS BINARY)
           AND CAST(a.table_comment AS BINARY) <=> CAST(e.table_comment AS BINARY)
           AND a.column_count <=> e.column_count
           AND CAST(a.column_hash AS BINARY) <=> CAST(e.column_hash AS BINARY)
           AND a.index_count <=> e.index_count
           AND CAST(a.index_hash AS BINARY) <=> CAST(e.index_hash AS BINARY)
           AND a.check_count <=> e.check_count
           AND a.check_disabled_count <=> e.check_disabled_count
           AND CAST(a.check_hash AS BINARY) <=> CAST(e.check_hash AS BINARY)
           AND a.foreign_key_count <=> e.foreign_key_count
           AND a.trigger_count <=> e.trigger_count
           AND a.inbound_foreign_key_count <=> e.inbound_foreign_key_count),
    1, 0
);

SET @wms_legacy_id_map_action = (
    SELECT state FROM tmp_iws_upgrade_control_state WHERE table_name = 'wms_legacy_id_map'
);
SET @ddl_sql = IF(
    @ddl_chain_ok = 1 AND @wms_legacy_id_map_action = 'MISSING',
    'CREATE TABLE wms_legacy_id_map (
        map_id bigint NOT NULL AUTO_INCREMENT COMMENT ''旧标识映射主键，系统生成'',
        domain varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT ''映射域，如 COMPANY/WAREHOUSE/STORAGE'',
        source_table varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT ''源表名'',
        source_key varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''源业务键，精确比较'',
        source_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT ''源业务摘要，SHA-256 小写十六进制'',
        target_type varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT ''目标对象类型'',
        target_id bigint NOT NULL COMMENT ''目标内部主键'',
        mapping_status varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''MAPPED'' COMMENT ''状态：MAPPED/VERIFIED'',
        created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',
        updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',
        PRIMARY KEY (map_id),
        UNIQUE KEY uk_wms_legacy_id_map_source (domain, source_table, source_key),
        UNIQUE KEY uk_wms_legacy_id_map_target (target_type, target_id),
        CONSTRAINT ck_wms_legacy_id_map_status CHECK (mapping_status IN (''MAPPED'',''VERIFIED''))
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS旧业务标识到目标内部标识映射''',
    'DO 0'
);
PREPARE stmt_iws_control_legacy_map FROM @ddl_sql;
EXECUTE stmt_iws_control_legacy_map;
DEALLOCATE PREPARE stmt_iws_control_legacy_map;
DELETE FROM tmp_iws_upgrade_control_actual WHERE table_name = 'wms_legacy_id_map';
SET @probe_table = 'wms_legacy_id_map';
EXECUTE stmt_iws_control_probe;
SET @ddl_chain_ok = IF(
    @ddl_chain_ok = 1
    AND (SELECT COUNT(*) = 1
         FROM tmp_iws_upgrade_control_actual a
         JOIN tmp_iws_upgrade_control_expected e
           ON e.table_name = a.table_name
         WHERE a.table_name = 'wms_legacy_id_map'
           AND a.table_type <=> 'BASE TABLE'
           AND CAST(a.engine AS BINARY) <=> CAST(e.engine AS BINARY)
           AND CAST(a.table_collation AS BINARY) <=> CAST(e.table_collation AS BINARY)
           AND CAST(a.table_comment AS BINARY) <=> CAST(e.table_comment AS BINARY)
           AND a.column_count <=> e.column_count
           AND CAST(a.column_hash AS BINARY) <=> CAST(e.column_hash AS BINARY)
           AND a.index_count <=> e.index_count
           AND CAST(a.index_hash AS BINARY) <=> CAST(e.index_hash AS BINARY)
           AND a.check_count <=> e.check_count
           AND a.check_disabled_count <=> e.check_disabled_count
           AND CAST(a.check_hash AS BINARY) <=> CAST(e.check_hash AS BINARY)
           AND a.foreign_key_count <=> e.foreign_key_count
           AND a.trigger_count <=> e.trigger_count
           AND a.inbound_foreign_key_count <=> e.inbound_foreign_key_count),
    1, 0
);

SET @wms_upgrade_issue_action = (
    SELECT state FROM tmp_iws_upgrade_control_state WHERE table_name = 'wms_upgrade_issue'
);
SET @ddl_sql = IF(
    @ddl_chain_ok = 1 AND @wms_upgrade_issue_action = 'MISSING',
    'CREATE TABLE wms_upgrade_issue (
        issue_id bigint NOT NULL AUTO_INCREMENT COMMENT ''升级问题主键，系统生成'',
        run_id bigint NOT NULL COMMENT ''所属升级运行，逻辑关联 wms_upgrade_run.run_id'',
        issue_code varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT ''问题机器码，稳定标识'',
        issue_category varchar(255) NOT NULL COMMENT ''中文问题类别'',
        redacted_source_key varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''脱敏源业务键，禁止写入凭据或完整敏感报文'',
        detail_summary varchar(1000) NOT NULL COMMENT ''中文脱敏问题摘要'',
        blocking_phase varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT ''被阻止的阶段'',
        suggested_action varchar(1000) NOT NULL COMMENT ''建议人工动作'',
        is_blocking tinyint(1) NOT NULL DEFAULT 1 COMMENT ''是否阻断后续阶段：1 阻断，0 仅提示'',
        detected_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''检测时间'',
        PRIMARY KEY (issue_id),
        UNIQUE KEY uk_wms_upgrade_issue_run_code_key (run_id, issue_code, redacted_source_key),
        KEY idx_wms_upgrade_issue_run_blocking (run_id, blocking_phase, is_blocking),
        CONSTRAINT ck_wms_upgrade_issue_blocking CHECK (is_blocking IN (0,1)),
        CONSTRAINT ck_wms_upgrade_issue_phase CHECK (blocking_phase IN (''SOURCE_VERIFIED'',''CONTROL_READY'',''DDL_PARTIAL'',''DDL_TARGET'',''BACKFILL_PARTIAL'',''DATA_TARGET'',''CUTOVER_READY'',''COMPLETE'',''BLOCKED''))
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS库存升级中文阻碍清单''',
    'DO 0'
);
PREPARE stmt_iws_control_issue FROM @ddl_sql;
EXECUTE stmt_iws_control_issue;
DEALLOCATE PREPARE stmt_iws_control_issue;
DELETE FROM tmp_iws_upgrade_control_actual WHERE table_name = 'wms_upgrade_issue';
SET @probe_table = 'wms_upgrade_issue';
EXECUTE stmt_iws_control_probe;
SET @ddl_chain_ok = IF(
    @ddl_chain_ok = 1
    AND (SELECT COUNT(*) = 1
         FROM tmp_iws_upgrade_control_actual a
         JOIN tmp_iws_upgrade_control_expected e
           ON e.table_name = a.table_name
         WHERE a.table_name = 'wms_upgrade_issue'
           AND a.table_type <=> 'BASE TABLE'
           AND CAST(a.engine AS BINARY) <=> CAST(e.engine AS BINARY)
           AND CAST(a.table_collation AS BINARY) <=> CAST(e.table_collation AS BINARY)
           AND CAST(a.table_comment AS BINARY) <=> CAST(e.table_comment AS BINARY)
           AND a.column_count <=> e.column_count
           AND CAST(a.column_hash AS BINARY) <=> CAST(e.column_hash AS BINARY)
           AND a.index_count <=> e.index_count
           AND CAST(a.index_hash AS BINARY) <=> CAST(e.index_hash AS BINARY)
           AND a.check_count <=> e.check_count
           AND a.check_disabled_count <=> e.check_disabled_count
           AND CAST(a.check_hash AS BINARY) <=> CAST(e.check_hash AS BINARY)
           AND a.foreign_key_count <=> e.foreign_key_count
           AND a.trigger_count <=> e.trigger_count
           AND a.inbound_foreign_key_count <=> e.inbound_foreign_key_count),
    1, 0
);

-- ----------------------------------------------------------------------------
-- 六、后置结构验收与切片边界
-- ----------------------------------------------------------------------------
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

TRUNCATE TABLE tmp_iws_upgrade_control_state;
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

SET @control_matched_after = (SELECT COUNT(*) FROM tmp_iws_upgrade_control_state WHERE state = 'MATCHED');
SET @control_missing_after = (SELECT COUNT(*) FROM tmp_iws_upgrade_control_state WHERE state = 'MISSING');
SET @control_conflict_after = (SELECT COUNT(*) FROM tmp_iws_upgrade_control_state WHERE state = 'CONFLICT');
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

SET @control_structure_ok = (
    @metadata_visibility_ok = 1
    AND @ddl_chain_ok = 1
    AND @control_matched_after = @expected_control_table_count
    AND @control_missing_after = 0
    AND @control_conflict_after = 0
    AND CAST(@actual_target_fingerprint AS BINARY) = CAST(@declared_target_fingerprint AS BINARY)
);

SELECT
    s.table_name AS '控制表',
    s.state AS '最终状态',
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
    @control_release_id AS '发布标识',
    @control_contract_version AS '控制契约',
    @control_state_before AS '执行前状态',
    IF(@metadata_visibility_ok = 1, '通过', '权限不足') AS '元数据权限',
    (@control_matched_after - @control_matched_before) AS '新增匹配控制表数',
    @control_matched_after AS '目标态控制表数',
    @control_conflict_after AS '最终冲突数',
    @ddl_chain_ok AS '逐表成功链',
    @declared_target_fingerprint AS '目标结构指纹',
    @actual_target_fingerprint AS '实际结构指纹',
    IF(@control_structure_ok = 1, '通过', '失败') AS '控制结构结果',
    IF(@control_structure_ok = 1,
       '控制结构完成；S0—S5回填、应用启动门禁、核心/初始化同版整合尚未实现',
       IF(@metadata_visibility_ok = 0,
          '权限不足：控制结构不能报告成功，且不得进入后续升级',
          '控制结构验收失败，请检查上述表定义与目标指纹')) AS '切片边界';

SELECT JSON_EXTRACT(IF(@control_structure_ok = 1, '{}', 'invalid'), '$') AS '控制结构完成门禁';

DEALLOCATE PREPARE stmt_iws_control_probe;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_upgrade_control_state;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_upgrade_control_actual;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_upgrade_control_expected;

SET SESSION group_concat_max_len = @saved_group_concat_max_len;
