/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P2 空间生命周期
 * @Version      : V1.0
 * @Description  : 核对空间动作的计划实例身份与web冻结输入；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.space.change.process;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.wms.rule.support.WmsRuleDigest;
import com.innolux.iws.wms.space.change.service.WmsSpaceChangeService;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Objects;

/** 空间动作共同输入核对。 */
final class WmsSpaceActionSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private WmsSpaceActionSupport() {
    }

    static long requireOrder(
        ActionContext context,
        String actionName,
        String localEffectKey,
        ProcessInstanceMapper instanceMapper,
        JdbcTemplate jdbcTemplate
    ) {
        if (context == null || context.direction() != EventDirection.INTERNAL) {
            throw new ServiceException(actionName + "只允许INTERNAL事件");
        }
        if (!localEffectKey.equals(context.localEffectKey())) {
            throw new ServiceException(actionName + "本地效果键不匹配");
        }
        ProcessInstance instance = instanceMapper.selectById(context.instanceId());
        if (instance == null
            || !WmsSpaceChangeService.BUSINESS_TYPE.equals(instance.getBusinessType())
            || instance.getBusinessId() == null
            || instance.getTaskKey() == null) {
            throw new ServiceException(actionName + "计划实例不是空间变更业务");
        }
        long orderId;
        try {
            orderId = Long.parseLong(instance.getBusinessId());
        } catch (NumberFormatException error) {
            throw new ServiceException(actionName + "计划业务ID不是空间变更单ID");
        }
        Map<String, Object> order = jdbcTemplate.queryForMap("""
            select command_key, input_hash, input_json
              from wms_space_change_order where change_order_id = ?
            """, orderId);
        if (!Objects.equals(instance.getTaskKey(), order.get("command_key"))
            || !Objects.equals(context.inputHash(), order.get("input_hash"))
            || !sameJson(context.payloadJson(), order.get("input_json"))) {
            throw new ServiceException(actionName + "冻结输入与空间变更单不一致");
        }
        return orderId;
    }

    private static boolean sameJson(String left, Object right) {
        try {
            return right != null && Objects.equals(
                WmsRuleDigest.canonical(JSON.readTree(left)),
                WmsRuleDigest.canonical(JSON.readTree(String.valueOf(right)))
            );
        } catch (RuntimeException error) {
            return Objects.equals(left, right);
        }
    }
}
