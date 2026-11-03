/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配计划动作
 * @Version      : V1.0
 * @Description  : 解析并核对分配 LocalAction 的冻结命令与计划实例身份；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.process;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationBusinessContext;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationReservationResult;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationReserveCommand;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 分配动作的共同输入核对。 */
final class WmsAllocationActionSupport {

    private WmsAllocationActionSupport() {
    }

    static WmsAllocationReserveCommand readCommand(
        ActionContext context,
        String actionName,
        String localEffectKey,
        JsonMapper jsonMapper,
        ProcessInstanceMapper instanceMapper
    ) {
        requirePayload(context, actionName, localEffectKey);
        WmsAllocationReserveCommand command = jsonMapper.readValue(
            context.payloadJson(), WmsAllocationReserveCommand.class);
        if (command == null || command.business() == null) {
            throw new ServiceException(actionName + "输入缺少父业务上下文");
        }
        if (!Objects.equals(command.inputHash(), context.inputHash())
            || !Objects.equals(command.effectKey(), "WMS:" + context.inputHash())) {
            throw new ServiceException(actionName + "输入摘要或效果键与平台冻结输入不一致");
        }
        WmsAllocationBusinessContext business = command.business();
        requireInstance(
            context,
            actionName,
            instanceMapper,
            business.planInstanceId(),
            business.businessType(),
            business.businessId(),
            business.taskKey()
        );
        return command;
    }

    static void requirePayload(
        ActionContext context,
        String actionName,
        String localEffectKey
    ) {
        if (context == null || context.direction() != EventDirection.INTERNAL) {
            throw new ServiceException(actionName + "只允许INTERNAL事件");
        }
        if (!localEffectKey.equals(context.localEffectKey())) {
            throw new ServiceException(actionName + "本地效果键不匹配");
        }
        if (context.payloadJson() == null || context.payloadJson().isBlank()) {
            throw new ServiceException(actionName + "缺少冻结输入");
        }
    }

    static void requireInstance(
        ActionContext context,
        String actionName,
        ProcessInstanceMapper instanceMapper,
        long planInstanceId,
        String businessType,
        long businessId,
        String taskKey
    ) {
        ProcessInstance instance = instanceMapper.selectById(context.instanceId());
        if (instance == null) {
            throw new ServiceException(actionName + "计划实例不存在");
        }
        if (planInstanceId != context.instanceId()
            || !Objects.equals(businessType, instance.getBusinessType())
            || !Objects.equals(String.valueOf(businessId), instance.getBusinessId())
            || !Objects.equals(taskKey, instance.getTaskKey())) {
            throw new ServiceException(actionName + "父业务身份与计划实例不一致");
        }
    }

    static String checkpoint(
        JsonMapper jsonMapper,
        WmsAllocationReservationResult result
    ) {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("status", result.result());
        checkpoint.put("decisionId", result.decisionId());
        checkpoint.put("selectedStorageId", result.selectedStorageId());
        checkpoint.put("selectedCandidateId", result.selectedCandidateId());
        checkpoint.put("channelLockIds", result.channelLockIds());
        checkpoint.put("allocationLockIds", result.allocationLockIds());
        return jsonMapper.writeValueAsString(checkpoint);
    }
}
