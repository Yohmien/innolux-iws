/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 核对逻辑合并父/子计划实例身份及冻结输入；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.logical.merge.process;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.runtime.ActionContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Objects;

/** 逻辑合并动作共同输入核对。 */
final class WmsLogicalMergeActionSupport {

    private WmsLogicalMergeActionSupport() {
    }

    static long requireBusinessId(
        ActionContext context,
        String actionName,
        String effect,
        String expectedPlanType,
        ProcessInstanceMapper instanceMapper,
        JdbcTemplate jdbcTemplate
    ) {
        if (context == null || context.direction() != EventDirection.INTERNAL) {
            throw new ServiceException(actionName + "只允许INTERNAL事件");
        }
        if (!effect.equals(context.localEffectKey())) {
            throw new ServiceException(actionName + "本地效果键不匹配");
        }
        ProcessInstance instance = instanceMapper.selectById(context.instanceId());
        if (instance == null
            || !WmsLogicalMergePlanContract.BUSINESS_TYPE.equals(instance.getBusinessType())
            || !expectedPlanType.equals(instance.getPlanType())
            || instance.getBusinessId() == null) {
            throw new ServiceException(actionName + "计划实例身份不匹配");
        }
        long businessId;
        try {
            businessId = Long.parseLong(instance.getBusinessId());
        } catch (NumberFormatException error) {
            throw new ServiceException(actionName + "计划业务ID不是数字");
        }
        String sql = WmsLogicalMergePlanContract.CLOSE_PLAN_TYPE.equals(expectedPlanType)
            ? "select merge_order_id, input_hash, input_json from wms_logical_merge_order "
                + "where merge_order_id = ?"
            : "select batch_id, input_hash, input_json from wms_logical_merge_batch "
                + "where batch_id = ?";
        Map<String, Object> row = jdbcTemplate.queryForMap(sql, businessId);
        if (!Objects.equals(context.inputHash(), row.get("input_hash"))
            || !Objects.equals(context.payloadJson(), row.get("input_json"))) {
            throw new ServiceException(actionName + "冻结输入与持久批次不一致");
        }
        return businessId;
    }
}
