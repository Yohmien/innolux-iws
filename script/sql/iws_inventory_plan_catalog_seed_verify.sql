-- 校验 WMS 规则发布计划目录、调度类型与挂载种子。
SELECT
    (SELECT COUNT(*) FROM iws_business_schedule
     WHERE id = 1761400000000060001
       AND CAST(business_code AS BINARY) = CAST('INVENTORY_CONFIG' AS BINARY)
       AND business_name = '库存配置'
       AND enabled = 1 AND del_flag = '0') AS `调度符合`,
    (SELECT COUNT(*) FROM iws_business_schedule_plan_type
     WHERE id = 1761400000000060101
       AND schedule_id = 1761400000000060001
       AND CAST(plan_type AS BINARY) = CAST('RULESET_PUBLISH' AS BINARY)
       AND plan_type_name = '规则包发布'
       AND enabled = 1) AS `计划类型符合`,
    (SELECT COUNT(*) FROM wms_business_type
     WHERE CAST(business_type_code AS BINARY) = CAST('WMS_RULESET_PUBLISH' AS BINARY)
       AND business_type_name = '规则包发布'
       AND status = 'ACTIVE'
       AND CAST(contract_version AS BINARY) = CAST('1' AS BINARY)) AS `业务类型符合`,
    (SELECT COUNT(*) FROM wms_business_plan_mount
     WHERE mount_id = 1761400000000060201
       AND CAST(business_type_code AS BINARY) = CAST('WMS_RULESET_PUBLISH' AS BINARY)
       AND CAST(plan_type_code AS BINARY) = CAST('RULESET_PUBLISH' AS BINARY)
       AND CAST(schedule_code AS BINARY) = CAST('INVENTORY_CONFIG' AS BINARY)
       AND enabled = 1) AS `规则挂载符合`,
    (SELECT COUNT(*) FROM iws_business_schedule
     WHERE id = 1761400000000060002
       AND CAST(business_code AS BINARY) = CAST('SPACE_CONFIG' AS BINARY)
       AND business_name = '空间配置'
       AND enabled = 1 AND del_flag = '0') AS `空间调度符合`,
    (SELECT COUNT(*) FROM iws_business_schedule_plan_type
     WHERE id IN (1761400000000060102, 1761400000000060103)
       AND schedule_id = 1761400000000060002
       AND enabled = 1) AS `空间计划类型符合`,
    (SELECT COUNT(*) FROM wms_business_type
     WHERE CAST(business_type_code AS BINARY) = CAST('WMS_SPACE_CHANGE' AS BINARY)
       AND status = 'ACTIVE') AS `空间业务类型符合`,
    (SELECT COUNT(*) FROM wms_business_plan_mount
     WHERE mount_id IN (1761400000000060202, 1761400000000060203)
       AND enabled = 1) AS `空间挂载符合`,
    (SELECT COUNT(*) FROM iws_business_schedule
     WHERE id = 1761400000000060003
       AND CAST(business_code AS BINARY) = CAST('LOGICAL_MERGE' AS BINARY)
       AND business_name = '逻辑归属合并'
       AND enabled = 1 AND del_flag = '0') AS `逻辑合并调度符合`,
    (SELECT COUNT(*) FROM iws_business_schedule_plan_type
     WHERE id IN (1761400000000060104, 1761400000000060105)
       AND schedule_id = 1761400000000060003
       AND enabled = 1) AS `逻辑合并计划类型符合`,
    (SELECT COUNT(*) FROM wms_business_type
     WHERE CAST(business_type_code AS BINARY) = CAST('WMS_LOGICAL_MERGE' AS BINARY)
       AND status = 'ACTIVE') AS `逻辑合并业务类型符合`,
    (SELECT COUNT(*) FROM wms_business_plan_mount
     WHERE mount_id IN (1761400000000060204, 1761400000000060205)
       AND enabled = 1) AS `逻辑合并挂载符合`;
