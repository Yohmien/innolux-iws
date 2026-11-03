/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则发布计划
 * @Version      : V1.0
 * @Description  : 解析并核对规则发布动作的冻结输入和计划实例身份；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.process;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.wms.rule.domain.command.WmsRulePackagePublishCommand;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;

/** 规则发布计划动作的共同输入核对。 */
final class WmsRulePublishActionSupport {

    private WmsRulePublishActionSupport() {
    }

    static WmsRulePackagePublishCommand readCommand(
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
        WmsRulePackagePublishCommand command = jsonMapper.readValue(
            context.payloadJson(),
            WmsRulePackagePublishCommand.class
        );
        ProcessInstance instance = instanceMapper.selectById(context.instanceId());
        if (command == null || instance == null
            || !WmsRulePlanContract.BUSINESS_TYPE.equals(instance.getBusinessType())
            || !Objects.equals(
                String.valueOf(command.getPackageVersionId()),
                instance.getBusinessId()
            )) {
            throw new ServiceException(actionName + "冻结输入与计划实例身份不一致");
        }
        if (command.getCommandKey() == null
            || !command.getCommandKey().equals(instance.getTaskKey())) {
            throw new ServiceException(actionName + "commandKey必须绑定计划任务键");
        }
        return command;
    }
}
