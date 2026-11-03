/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 装拆计划动作
 * @Version      : V1.0
 * @Description  : 解析并核对装拆动作的冻结命令、效果键和计划实例身份；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.change.process;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.wms.load.change.domain.WmsLoadChangeCommand;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;

/** 装拆计划动作的共同输入核对。 */
final class WmsLoadChangeActionSupport {

    private WmsLoadChangeActionSupport() {
    }

    static WmsLoadChangeCommand readCommand(
        ActionContext context,
        String actionName,
        String localEffectKey,
        JsonMapper jsonMapper,
        ProcessInstanceMapper instanceMapper
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
        WmsLoadChangeCommand command = jsonMapper.readValue(
            context.payloadJson(), WmsLoadChangeCommand.class);
        if (command == null
            || !Objects.equals(command.inputHash(), context.inputHash())
            || !Objects.equals(command.effectKey(), "WMS:" + context.inputHash())) {
            throw new ServiceException(actionName + "输入摘要或效果键与平台冻结输入不一致");
        }
        ProcessInstance instance = instanceMapper.selectById(context.instanceId());
        if (instance == null
            || !Objects.equals(command.businessType(), instance.getBusinessType())
            || !Objects.equals(String.valueOf(command.businessId()), instance.getBusinessId())
            || !Objects.equals(command.taskKey(), instance.getTaskKey())
            || !Objects.equals(command.planInstanceId(), context.instanceId())) {
            throw new ServiceException(actionName + "父业务身份与计划实例不一致");
        }
        return command;
    }
}
