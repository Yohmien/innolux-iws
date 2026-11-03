-- ============================================================================
-- IWS+ WMS 已知直接写入冻结控制（INV-S2-F0）
-- ============================================================================
-- 目标数据库：MySQL 8.0.45
-- 角色：
--   本文件只操作 wms_upgrade_run / wms_upgrade_checkpoint，并以
--   WMS_DIRECT_WRITE 登记行作为共享写入锚点。
-- action=begin：
--   在单事务内先对已 VERIFIED 的 WMS_DIRECT_WRITE 登记行取得 FOR UPDATE；
--   该锁会等待此前已取得 FOR SHARE 的直接写事务提交。随后创建 RUNNING 运行行和
--   S2_DIRECT_WRITE_FENCE=VERIFIED 检查点，并提交。插入运行行后，后续直接写
--   门禁会拒绝；不把 NOT NULL write_fence_token 本身当作冻结证据。
--   所有 WMS 控制运行均参与判断；COMPLETED/COMPLETE/非空 completed_at 且
--   run 自身的 release/target 指纹恰对应一条 VERIFIED schema 登记才安全。
--   一条其他未安全完成运行即阻断，多个未安全完成运行明确 CONFLICT。
-- action=finish：
--   当前合同只冻结了“何时必须拒绝写入”，没有冻结可自动接受的外部解锁证据格式。
--   因此 finish 即使提供 run_id、同 token 和 evidence，也只输出
--   S2_DIRECT_WRITE_FENCE_RELEASE_CONTRACT_UNSUPPORTED 并保持运行原状。
--   不自动把 FAILED/BLOCKED/未知运行改为 COMPLETED。
-- 参数：
--   @action、@run_id、@write_fence_token、@unfreeze_evidence 和 @control_actor
--   集中在下方。begin 必须显式填写调用者 token；finish 还须显式填写 run_id。
-- 执行：
--   mysql --no-defaults --protocol=TCP --host=<主机> --port=<端口> \
--     --user=<受控运维账号> --default-character-set=utf8mb4 \
--     --database=<目标库> --batch --raw \
--     < script/sql/iws_inventory_direct_write_fence_control.sql
--   必须使用同一连接且不得添加 --force；首个 SQL 错误即停止并关闭连接。
-- ============================================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 集中参数：调用前显式修改
-- ----------------------------------------------------------------------------
SET @target_schema = 'innolux-iws';
SET @component_code = 'WMS_DIRECT_WRITE';
SET @schema_version = '1';
SET @release_id = 'INV-WMS-DIRECT-WRITE-1';
SET @target_fingerprint =
    '1b236346b990fbf9410aeea49bb323f174112e1a44c133998baccb2ddac06909';
SET @action = 'begin';
SET @run_id = NULL;
SET @write_fence_token = '';
SET @unfreeze_evidence = '';
SET @control_actor = 'OFFLINE_OPERATOR';
SET @fence_step_code = 'S2_DIRECT_WRITE_FENCE';
SET @fence_object_key = 'WMS_DIRECT_WRITE:1';

SET @schema_ok = (
    DATABASE() IS NOT NULL
    AND CAST(DATABASE() AS BINARY) = CAST(@target_schema AS BINARY)
);
SET @version_ok = (VERSION() = '8.0.45');
SET @action_supported = (@action IN ('begin', 'finish'));
SET @token_ok = (
    @write_fence_token IS NOT NULL
    AND CHAR_LENGTH(TRIM(@write_fence_token)) > 0
    AND CHAR_LENGTH(@write_fence_token) <= 128
);

SELECT
    @target_schema AS '目标库',
    VERSION() AS '数据库版本',
    @component_code AS '组件编码',
    @release_id AS '发布标识',
    @action AS '请求动作',
    IF(@action = 'begin' AND @token_ok = 1, '已提供', '未提供或无效')
        AS '显式调用者token',
    IF(@action = 'finish' AND @run_id IS NOT NULL, '已提供', '未提供')
        AS '显式run_id',
    IF(@schema_ok = 1 AND @version_ok = 1 AND @action_supported = 1, '通过', '失败')
        AS '参数前置';

SELECT JSON_EXTRACT(
    IF(@schema_ok = 1 AND @version_ok = 1 AND @action_supported = 1, '{}', 'invalid'),
    '$'
) AS '冻结控制参数门禁';

-- ----------------------------------------------------------------------------
-- 一、先排他锁定协议锚点
-- ----------------------------------------------------------------------------
START TRANSACTION;

SET @registered_component_code = NULL;
SET @registered_schema_version = NULL;
SET @registered_release_id = NULL;
SET @registered_source_fingerprint = NULL;
SET @registered_target_fingerprint = NULL;
SET @registered_status = NULL;

SELECT component_code, schema_version, release_id,
       source_fingerprint, target_fingerprint, status
  INTO @registered_component_code, @registered_schema_version,
       @registered_release_id, @registered_source_fingerprint,
       @registered_target_fingerprint, @registered_status
  FROM wms_schema_version
 WHERE component_code = @component_code
   AND schema_version = @schema_version
 FOR UPDATE;

SET @registration_ok = (
    @registered_component_code IS NOT NULL
    AND CAST(@registered_component_code AS BINARY) = CAST(@component_code AS BINARY)
    AND CAST(@registered_schema_version AS BINARY) = CAST(@schema_version AS BINARY)
    AND CAST(@registered_release_id AS BINARY) = CAST(@release_id AS BINARY)
    AND CAST(@registered_source_fingerprint AS BINARY)
        = CAST(@target_fingerprint AS BINARY)
    AND CAST(@registered_target_fingerprint AS BINARY)
        = CAST(@target_fingerprint AS BINARY)
    AND CAST(@registered_status AS BINARY) = CAST('VERIFIED' AS BINARY)
);

-- ----------------------------------------------------------------------------
-- 二、锁定全部 WMS 运行及其完成证据
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_iws_direct_write_runs;
CREATE TEMPORARY TABLE tmp_iws_direct_write_runs (
    run_id             bigint       NOT NULL,
    release_id         varchar(64)  NOT NULL,
    phase              varchar(32)  NOT NULL,
    write_fence_token  varchar(128) NOT NULL,
    status             varchar(16)  NOT NULL,
    target_fingerprint char(64)     NOT NULL,
    completed_at       datetime(3)  DEFAULT NULL,
    registration_match_count int    NOT NULL DEFAULT 0,
    PRIMARY KEY (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SELECT run_id
  FROM wms_upgrade_run
 ORDER BY run_id
 FOR UPDATE;

SELECT schema_version_id
  FROM wms_schema_version
 WHERE status = 'VERIFIED'
 ORDER BY schema_version_id
 FOR SHARE;

INSERT INTO tmp_iws_direct_write_runs
    (run_id, release_id, phase, write_fence_token, status,
     target_fingerprint, completed_at)
SELECT run_id, release_id, phase, write_fence_token, status,
       target_fingerprint, completed_at
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

SET @token_run_count = (
    SELECT COUNT(*)
      FROM tmp_iws_direct_write_runs
     WHERE CAST(write_fence_token AS BINARY) = CAST(@write_fence_token AS BINARY)
);

SET @same_token_unsafe_count = (
    SELECT COUNT(*)
      FROM tmp_iws_direct_write_runs
     WHERE CAST(write_fence_token AS BINARY) = CAST(@write_fence_token AS BINARY)
       AND NOT (
           status = 'COMPLETED'
           AND phase = 'COMPLETE'
           AND completed_at IS NOT NULL
           AND registration_match_count = 1
       )
);

SET @same_token_run_id = (
    SELECT MIN(run_id)
      FROM tmp_iws_direct_write_runs
     WHERE CAST(write_fence_token AS BINARY) = CAST(@write_fence_token AS BINARY)
       AND CAST(release_id AS BINARY) = CAST(@release_id AS BINARY)
       AND NOT (
           status = 'COMPLETED'
           AND phase = 'COMPLETE'
           AND completed_at IS NOT NULL
           AND registration_match_count = 1
       )
);

SET @same_token_checkpoint_ok = (
    SELECT COUNT(*) = 1
      FROM wms_upgrade_checkpoint
     WHERE run_id = @same_token_run_id
       AND step_code = @fence_step_code
       AND CAST(object_key AS BINARY) = CAST(@fence_object_key AS BINARY)
       AND status = 'VERIFIED'
       AND completed_at IS NOT NULL
       AND CAST(after_digest AS BINARY) = CAST(@target_fingerprint AS BINARY)
);

SET @begin_insert = (
    @registration_ok = 1
    AND @action = 'begin'
    AND @token_ok = 1
    AND @unsafe_run_count = 0
    AND @token_run_count = 0
);
SET @begin_idempotent = (
    @registration_ok = 1
    AND @action = 'begin'
    AND @token_ok = 1
    AND @unsafe_run_count = 1
    AND @same_token_unsafe_count = 1
    AND @token_run_count = 1
    AND @same_token_run_id IS NOT NULL
    AND @same_token_checkpoint_ok = 1
);
SET @begin_ok = (@begin_insert = 1 OR @begin_idempotent = 1);
SET @finish_contract_supported = 0;
SET @control_ok = (
    @registration_ok = 1
    AND (
        @begin_ok = 1
        OR (@action = 'finish' AND @finish_contract_supported = 1)
    )
);

SELECT
    @registration_ok AS '登记合同有效',
    @unsafe_run_count AS '未安全完成运行数',
    @same_token_unsafe_count AS '同token未完成运行数',
    @same_token_checkpoint_ok AS '同token检查点有效',
    @begin_insert AS '需要新建冻结运行',
    @begin_idempotent AS '可幂等重放',
    @control_ok AS '控制合同可执行';

-- begin 只在前置合同满足且没有其他未安全完成运行时插入。
INSERT INTO wms_upgrade_run
    (release_id, source_fingerprint, target_fingerprint, phase,
     write_fence_token, status, attempt_count, started_by, started_at, updated_at)
SELECT @release_id, @target_fingerprint, @target_fingerprint, 'CUTOVER_READY',
       @write_fence_token, 'RUNNING', 0, @control_actor,
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
 WHERE @begin_insert = 1;

SET @begin_run_id = CASE
    WHEN @begin_insert = 1 THEN LAST_INSERT_ID()
    WHEN @begin_idempotent = 1 THEN @same_token_run_id
    ELSE NULL
END;

INSERT INTO wms_upgrade_checkpoint
    (run_id, step_code, object_key, status,
     before_digest, after_digest, attempt_count,
     started_at, updated_at, completed_at)
SELECT @begin_run_id, @fence_step_code, @fence_object_key, 'VERIFIED',
       @target_fingerprint, @target_fingerprint, 1,
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
 WHERE @begin_insert = 1;

SET @persisted_begin_ok = (
    @begin_ok = 1
    AND (SELECT COUNT(*) = 1
           FROM wms_upgrade_run
          WHERE run_id = @begin_run_id
            AND CAST(release_id AS BINARY) = CAST(@release_id AS BINARY)
            AND CAST(source_fingerprint AS BINARY) = CAST(@target_fingerprint AS BINARY)
            AND CAST(target_fingerprint AS BINARY) = CAST(@target_fingerprint AS BINARY)
            AND phase = 'CUTOVER_READY'
            AND status = 'RUNNING'
            AND CAST(write_fence_token AS BINARY) = CAST(@write_fence_token AS BINARY))
    AND (SELECT COUNT(*) = 1
           FROM wms_upgrade_checkpoint
          WHERE run_id = @begin_run_id
            AND step_code = @fence_step_code
            AND CAST(object_key AS BINARY) = CAST(@fence_object_key AS BINARY)
            AND status = 'VERIFIED'
            AND completed_at IS NOT NULL
            AND CAST(after_digest AS BINARY) = CAST(@target_fingerprint AS BINARY))
);

SELECT
    CASE
        WHEN @action = 'finish' THEN
            'S2_DIRECT_WRITE_FENCE_RELEASE_CONTRACT_UNSUPPORTED'
        WHEN @registration_ok = 0 THEN 'SCHEMA_STATE_UNSUPPORTED'
        WHEN @action = 'begin' AND @token_ok = 0 THEN 'DIRECT_WRITE_FENCE_TOKEN_REQUIRED'
        WHEN @unsafe_run_count > 1 THEN 'DIRECT_WRITE_FENCE_CONFLICT'
        WHEN @unsafe_run_count = 1 AND @begin_idempotent = 0 THEN 'DIRECT_WRITE_FROZEN'
        WHEN @begin_ok = 0 THEN 'DIRECT_WRITE_FENCE_CONFLICT'
        WHEN @persisted_begin_ok = 0 THEN 'DIRECT_WRITE_FENCE_PERSISTENCE_FAILED'
        ELSE 'DIRECT_WRITE_FENCE_ACTIVE'
    END AS '控制结果码',
    @begin_run_id AS 'run_id',
    IF(@action = 'finish',
       '未冻结可接受的外部解锁证据格式；不自动解除',
       IF(@begin_ok = 1, '只有已知直接入口已冻结，不代表完整S2完成', '未改变控制状态'))
        AS '范围说明';

SELECT JSON_EXTRACT(IF(@persisted_begin_ok = 1, '{}', 'invalid'), '$')
    AS '冻结控制硬门禁';

COMMIT;

SELECT
    run_id AS 'run_id',
    release_id AS '发布标识',
    phase AS '阶段',
    status AS '状态',
    write_fence_token AS '冻结token',
    completed_at AS '完成时间'
  FROM wms_upgrade_run
 WHERE run_id = @begin_run_id;

SELECT
    checkpoint_id AS '检查点ID',
    run_id AS 'run_id',
    step_code AS '步骤',
    object_key AS '对象键',
    status AS '状态',
    after_digest AS '目标指纹',
    completed_at AS '完成时间'
  FROM wms_upgrade_checkpoint
 WHERE run_id = @begin_run_id
   AND step_code = @fence_step_code;
