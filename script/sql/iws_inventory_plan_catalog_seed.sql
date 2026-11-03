-- ============================================================
-- IWS+ WMS 在库管理计划目录与挂载 seed
-- 目标：MySQL 8.0.45+，执行前必须已完整创建 WMS 与本计划事件结构。
-- 本脚本只补当前版本正式入口所需目录，不创建计划实例、运行事件或业务数据。
-- 重复执行只接受逐字段相同的既有行；冲突行不会被覆盖。
-- ============================================================

SET @seed_schedule_id = 1761400000000060001;
SET @seed_plan_type_id = 1761400000000060101;
SET @seed_space_schedule_id = 1761400000000060002;
SET @seed_space_change_type_id = 1761400000000060102;
SET @seed_space_retire_type_id = 1761400000000060103;
SET @seed_logical_schedule_id = 1761400000000060003;
SET @seed_logical_batch_type_id = 1761400000000060104;
SET @seed_logical_close_type_id = 1761400000000060105;
SET @seed_business_type = 'WMS_RULESET_PUBLISH';
SET @seed_business_name = '规则包发布';
SET @seed_space_business_type = 'WMS_SPACE_CHANGE';
SET @seed_space_business_name = '空间配置变更';
SET @seed_logical_business_type = 'WMS_LOGICAL_MERGE';
SET @seed_logical_business_name = '逻辑归属合并';
SET @seed_plan_type = 'RULESET_PUBLISH';
SET @seed_plan_type_name = '规则包发布';
SET @seed_schedule_code = 'INVENTORY_CONFIG';
SET @seed_schedule_name = '库存配置';
SET @seed_space_schedule_code = 'SPACE_CONFIG';
SET @seed_space_schedule_name = '空间配置';
SET @seed_logical_schedule_code = 'LOGICAL_MERGE';
SET @seed_logical_schedule_name = '逻辑归属合并';
SET @seed_contract_version = '1';
SET @seed_object_policy_key = 'WMS_RULESET_PUBLISH';
SET @seed_mount_id = 1761400000000060201;
SET @seed_space_mount_id = 1761400000000060202;
SET @seed_space_retire_mount_id = 1761400000000060203;
SET @seed_logical_batch_mount_id = 1761400000000060204;
SET @seed_logical_close_mount_id = 1761400000000060205;

INSERT INTO iws_business_schedule
    (id, business_code, business_name, enabled, fairness_window_size,
     revision, remark, del_flag, create_time, update_time)
SELECT @seed_schedule_id, @seed_schedule_code, @seed_schedule_name, 1, 32,
       0, 'WMS 在库管理配置类计划统一调度', '0',
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM iws_business_schedule
    WHERE CAST(business_code AS BINARY) = CAST(@seed_schedule_code AS BINARY)
);

INSERT INTO iws_business_schedule
    (id, business_code, business_name, enabled, fairness_window_size,
     revision, remark, del_flag, create_time, update_time)
SELECT @seed_logical_schedule_id, @seed_logical_schedule_code,
       @seed_logical_schedule_name, 1, 32, 0,
       'WMS 逻辑归属合并计划统一调度', '0',
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM iws_business_schedule
    WHERE CAST(business_code AS BINARY) = CAST(@seed_logical_schedule_code AS BINARY)
);

INSERT INTO iws_business_schedule
    (id, business_code, business_name, enabled, fairness_window_size,
     revision, remark, del_flag, create_time, update_time)
SELECT @seed_space_schedule_id, @seed_space_schedule_code, @seed_space_schedule_name,
       1, 32, 0, 'WMS 空间生命周期计划统一调度', '0',
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM iws_business_schedule
    WHERE CAST(business_code AS BINARY) = CAST(@seed_space_schedule_code AS BINARY)
);

INSERT INTO iws_business_schedule_plan_type
    (id, schedule_id, plan_type, plan_type_name, priority, enabled,
     create_time, update_time)
SELECT @seed_plan_type_id, schedule.id, @seed_plan_type, @seed_plan_type_name,
       10, 1, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM iws_business_schedule schedule
WHERE CAST(schedule.business_code AS BINARY) = CAST(@seed_schedule_code AS BINARY)
  AND NOT EXISTS (
      SELECT 1 FROM iws_business_schedule_plan_type type_row
      WHERE type_row.schedule_id = schedule.id
        AND CAST(type_row.plan_type AS BINARY) = CAST(@seed_plan_type AS BINARY)
  );

INSERT INTO iws_business_schedule_plan_type
    (id, schedule_id, plan_type, plan_type_name, priority, enabled,
     create_time, update_time)
SELECT @seed_space_change_type_id, schedule.id, 'SPACE_CHANGE', '空间配置变更',
       10, 1, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM iws_business_schedule schedule
WHERE CAST(schedule.business_code AS BINARY) = CAST(@seed_space_schedule_code AS BINARY)
  AND NOT EXISTS (
      SELECT 1 FROM iws_business_schedule_plan_type type_row
      WHERE type_row.schedule_id = schedule.id
        AND CAST(type_row.plan_type AS BINARY) = CAST('SPACE_CHANGE' AS BINARY)
  );

INSERT INTO iws_business_schedule_plan_type
    (id, schedule_id, plan_type, plan_type_name, priority, enabled,
     create_time, update_time)
SELECT @seed_logical_batch_type_id, schedule.id, 'LOGICAL_MERGE',
       '逻辑合并批次', 10, 1, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM iws_business_schedule schedule
WHERE CAST(schedule.business_code AS BINARY) = CAST(@seed_logical_schedule_code AS BINARY)
  AND NOT EXISTS (
      SELECT 1 FROM iws_business_schedule_plan_type type_row
      WHERE type_row.schedule_id = schedule.id
        AND CAST(type_row.plan_type AS BINARY) = CAST('LOGICAL_MERGE' AS BINARY)
  );

INSERT INTO iws_business_schedule_plan_type
    (id, schedule_id, plan_type, plan_type_name, priority, enabled,
     create_time, update_time)
SELECT @seed_logical_close_type_id, schedule.id, 'LOGICAL_MERGE_CLOSE',
       '逻辑合并收敛', 20, 1, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM iws_business_schedule schedule
WHERE CAST(schedule.business_code AS BINARY) = CAST(@seed_logical_schedule_code AS BINARY)
  AND NOT EXISTS (
      SELECT 1 FROM iws_business_schedule_plan_type type_row
      WHERE type_row.schedule_id = schedule.id
        AND CAST(type_row.plan_type AS BINARY) = CAST('LOGICAL_MERGE_CLOSE' AS BINARY)
  );

INSERT INTO iws_business_schedule_plan_type
    (id, schedule_id, plan_type, plan_type_name, priority, enabled,
     create_time, update_time)
SELECT @seed_space_retire_type_id, schedule.id, 'SPACE_RETIRE', '空间退役',
       20, 1, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM iws_business_schedule schedule
WHERE CAST(schedule.business_code AS BINARY) = CAST(@seed_space_schedule_code AS BINARY)
  AND NOT EXISTS (
      SELECT 1 FROM iws_business_schedule_plan_type type_row
      WHERE type_row.schedule_id = schedule.id
        AND CAST(type_row.plan_type AS BINARY) = CAST('SPACE_RETIRE' AS BINARY)
  );

INSERT INTO wms_business_type
    (business_type_code, business_type_name, status, contract_version,
     object_policy_key, revision, create_time)
SELECT @seed_business_type, @seed_business_name, 'ACTIVE',
       @seed_contract_version, @seed_object_policy_key, 1, CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM wms_business_type
    WHERE CAST(business_type_code AS BINARY) = CAST(@seed_business_type AS BINARY)
);

INSERT INTO wms_business_type
    (business_type_code, business_type_name, status, contract_version,
     object_policy_key, revision, create_time)
SELECT @seed_logical_business_type, @seed_logical_business_name, 'ACTIVE', '1',
       @seed_logical_business_type, 1, CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM wms_business_type
    WHERE CAST(business_type_code AS BINARY) = CAST(@seed_logical_business_type AS BINARY)
);

INSERT INTO wms_business_type
    (business_type_code, business_type_name, status, contract_version,
     object_policy_key, revision, create_time)
SELECT @seed_space_business_type, @seed_space_business_name, 'ACTIVE', '1',
       @seed_space_business_type, 1, CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM wms_business_type
    WHERE CAST(business_type_code AS BINARY) = CAST(@seed_space_business_type AS BINARY)
);

INSERT INTO wms_business_plan_mount
    (mount_id, business_type_code, plan_type_code, schedule_code,
     contract_version, enabled, revision, create_time)
SELECT @seed_logical_batch_mount_id, @seed_logical_business_type, 'LOGICAL_MERGE',
       @seed_logical_schedule_code, '1', 1, 1, CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM wms_business_plan_mount
    WHERE CAST(business_type_code AS BINARY) = CAST(@seed_logical_business_type AS BINARY)
      AND CAST(plan_type_code AS BINARY) = CAST('LOGICAL_MERGE' AS BINARY)
      AND CAST(schedule_code AS BINARY) = CAST(@seed_logical_schedule_code AS BINARY)
);

INSERT INTO wms_business_plan_mount
    (mount_id, business_type_code, plan_type_code, schedule_code,
     contract_version, enabled, revision, create_time)
SELECT @seed_logical_close_mount_id, @seed_logical_business_type,
       'LOGICAL_MERGE_CLOSE', @seed_logical_schedule_code, '1', 1, 1,
       CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM wms_business_plan_mount
    WHERE CAST(business_type_code AS BINARY) = CAST(@seed_logical_business_type AS BINARY)
      AND CAST(plan_type_code AS BINARY) = CAST('LOGICAL_MERGE_CLOSE' AS BINARY)
      AND CAST(schedule_code AS BINARY) = CAST(@seed_logical_schedule_code AS BINARY)
);

INSERT INTO wms_business_plan_mount
    (mount_id, business_type_code, plan_type_code, schedule_code,
     contract_version, enabled, revision, create_time)
SELECT @seed_mount_id, @seed_business_type, @seed_plan_type,
       @seed_schedule_code, @seed_contract_version, 1, 1, CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM wms_business_plan_mount
    WHERE CAST(business_type_code AS BINARY) = CAST(@seed_business_type AS BINARY)
      AND CAST(plan_type_code AS BINARY) = CAST(@seed_plan_type AS BINARY)
      AND CAST(schedule_code AS BINARY) = CAST(@seed_schedule_code AS BINARY)
);

INSERT INTO wms_business_plan_mount
    (mount_id, business_type_code, plan_type_code, schedule_code,
     contract_version, enabled, revision, create_time)
SELECT @seed_space_mount_id, @seed_space_business_type, 'SPACE_CHANGE',
       @seed_space_schedule_code, '1', 1, 1, CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM wms_business_plan_mount
    WHERE CAST(business_type_code AS BINARY) = CAST(@seed_space_business_type AS BINARY)
      AND CAST(plan_type_code AS BINARY) = CAST('SPACE_CHANGE' AS BINARY)
      AND CAST(schedule_code AS BINARY) = CAST(@seed_space_schedule_code AS BINARY)
);

INSERT INTO wms_business_plan_mount
    (mount_id, business_type_code, plan_type_code, schedule_code,
     contract_version, enabled, revision, create_time)
SELECT @seed_space_retire_mount_id, @seed_space_business_type, 'SPACE_RETIRE',
       @seed_space_schedule_code, '1', 1, 1, CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM wms_business_plan_mount
    WHERE CAST(business_type_code AS BINARY) = CAST(@seed_space_business_type AS BINARY)
      AND CAST(plan_type_code AS BINARY) = CAST('SPACE_RETIRE' AS BINARY)
      AND CAST(schedule_code AS BINARY) = CAST(@seed_space_schedule_code AS BINARY)
);

SELECT
    IF(
        (SELECT COUNT(*) FROM iws_business_schedule
         WHERE id = @seed_schedule_id
           AND CAST(business_code AS BINARY) = CAST(@seed_schedule_code AS BINARY)
           AND enabled = 1 AND del_flag = '0') = 1
        AND
        (SELECT COUNT(*) FROM iws_business_schedule_plan_type
         WHERE id = @seed_plan_type_id AND schedule_id = @seed_schedule_id
           AND CAST(plan_type AS BINARY) = CAST(@seed_plan_type AS BINARY)
           AND enabled = 1) = 1
        AND
        (SELECT COUNT(*) FROM wms_business_type
         WHERE CAST(business_type_code AS BINARY) = CAST(@seed_business_type AS BINARY)
           AND status = 'ACTIVE'
           AND CAST(contract_version AS BINARY) = CAST(@seed_contract_version AS BINARY)) = 1
        AND
        (SELECT COUNT(*) FROM iws_business_schedule
         WHERE id = @seed_space_schedule_id
           AND CAST(business_code AS BINARY) = CAST(@seed_space_schedule_code AS BINARY)
           AND enabled = 1 AND del_flag = '0') = 1
        AND
        (SELECT COUNT(*) FROM iws_business_schedule_plan_type
         WHERE id IN (@seed_space_change_type_id, @seed_space_retire_type_id)
           AND schedule_id = @seed_space_schedule_id
           AND enabled = 1) = 2
        AND
        (SELECT COUNT(*) FROM wms_business_type
         WHERE CAST(business_type_code AS BINARY) = CAST(@seed_space_business_type AS BINARY)
           AND status = 'ACTIVE') = 1
        AND
        (SELECT COUNT(*) FROM iws_business_schedule
         WHERE id = @seed_logical_schedule_id
           AND CAST(business_code AS BINARY) = CAST(@seed_logical_schedule_code AS BINARY)
           AND enabled = 1 AND del_flag = '0') = 1
        AND
        (SELECT COUNT(*) FROM iws_business_schedule_plan_type
         WHERE id IN (@seed_logical_batch_type_id, @seed_logical_close_type_id)
           AND schedule_id = @seed_logical_schedule_id
           AND enabled = 1) = 2
        AND
        (SELECT COUNT(*) FROM wms_business_type
         WHERE CAST(business_type_code AS BINARY) = CAST(@seed_logical_business_type AS BINARY)
           AND status = 'ACTIVE') = 1
        AND
        (SELECT COUNT(*) FROM wms_business_plan_mount
         WHERE mount_id IN (@seed_logical_batch_mount_id, @seed_logical_close_mount_id)
           AND enabled = 1) = 2
        AND
        (SELECT COUNT(*) FROM wms_business_plan_mount
         WHERE mount_id IN (@seed_space_mount_id, @seed_space_retire_mount_id)
           AND enabled = 1) = 2
        AND
        (SELECT COUNT(*) FROM wms_business_plan_mount
         WHERE mount_id = @seed_mount_id
           AND CAST(business_type_code AS BINARY) = CAST(@seed_business_type AS BINARY)
           AND CAST(plan_type_code AS BINARY) = CAST(@seed_plan_type AS BINARY)
           AND CAST(schedule_code AS BINARY) = CAST(@seed_schedule_code AS BINARY)
           AND enabled = 1) = 1,
        'WMS规则发布计划目录与挂载验证通过',
        'WMS规则发布计划目录或挂载冲突，请检查既有行'
    ) AS `执行结果`;
