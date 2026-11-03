/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配释放计划动作
 * @Version      : V1.0
 * @Description  : 按已冻结无效果证据释放本任务分配锁；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.process;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.process.runtime.ActionResult;
import com.innolux.iws.process.runtime.LocalAction;
import com.innolux.iws.process.runtime.LocalActionDescriptor;
import com.innolux.iws.process.runtime.LocalActionInput;
import com.innolux.iws.process.runtime.LocalActionInputProvider;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseCommand;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseIdentity;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseResult;
import com.innolux.iws.wms.allocation.release.service.WmsAllocationReleaseService;
import com.innolux.iws.wms.process.WmsFrozenActionInputReader;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;

/** RELEASE_ALLOCATION_LOCK 生产能力。 */
public class WmsAllocationReleaseAction implements LocalAction, LocalActionInputProvider {

    public static final String ACTION_KEY = "RELEASE_ALLOCATION_LOCK";

    public static final String LOCAL_EFFECT_KEY = "WMS:RELEASE_ALLOCATION_LOCK";

    private final WmsAllocationReleaseService releaseService;

    private final ProcessInstanceMapper instanceMapper;

    private final JsonMapper jsonMapper;

    private final WmsFrozenActionInputReader inputReader;

    public WmsAllocationReleaseAction(
        WmsAllocationReleaseService releaseService,
        ProcessInstanceMapper instanceMapper,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        this.releaseService = releaseService;
        this.instanceMapper = instanceMapper;
        this.jsonMapper = jsonMapper;
        this.inputReader = inputReader;
    }

    @Override
    public LocalActionDescriptor describe() {
        return new LocalActionDescriptor(ACTION_KEY, "1", "释放分配锁", "SAME_TX", true);
    }

    @Override
    public LocalActionInput provide(ActionContext context) {
        return inputReader.read(context, ACTION_KEY);
    }

    @Override
    public ActionResult execute(ActionContext context) {
        String actionName = "释放分配锁";
        WmsAllocationActionSupport.requirePayload(context, actionName, LOCAL_EFFECT_KEY);
        WmsAllocationReleaseCommand command = jsonMapper.readValue(
            context.payloadJson(), WmsAllocationReleaseCommand.class);
        if (command == null || command.identity() == null) {
            throw new ServiceException(actionName + "输入缺少释放任务身份");
        }
        if (!Objects.equals(command.inputHash(), context.inputHash())
            || !Objects.equals(command.effectKey(), "WMS:" + context.inputHash())) {
            throw new ServiceException(actionName + "输入摘要或效果键与平台冻结输入不一致");
        }
        WmsAllocationReleaseIdentity identity = command.identity();
        WmsAllocationActionSupport.requireInstance(
            context,
            actionName,
            instanceMapper,
            identity.planInstanceId(),
            identity.businessType(),
            identity.businessId(),
            identity.taskKey()
        );
        WmsAllocationReleaseResult result = releaseService.release(command);
        return new ActionResult(
            EffectResult.SUCCEEDED,
            context.evidenceRef(),
            null,
            null,
            null,
            null,
            result.checkpointJson(),
            "释放分配锁已提交"
        );
    }
}
