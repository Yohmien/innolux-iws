/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配释放计划动作
 * @Version      : V1.0
 * @Description  : 回读释放流水、分配锁和通道锁终态；
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
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseResult;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseVerifyCommand;
import com.innolux.iws.wms.allocation.release.service.WmsAllocationReleaseService;
import com.innolux.iws.wms.process.WmsFrozenActionInputReader;
import tools.jackson.databind.json.JsonMapper;

/** VERIFY_RELEASE 生产能力。 */
public class WmsAllocationVerifyReleaseAction implements LocalAction, LocalActionInputProvider {

    public static final String ACTION_KEY = "VERIFY_RELEASE";

    public static final String LOCAL_EFFECT_KEY = "WMS:VERIFY_RELEASE";

    private final WmsAllocationReleaseService releaseService;

    private final ProcessInstanceMapper instanceMapper;

    private final JsonMapper jsonMapper;

    private final WmsFrozenActionInputReader inputReader;

    public WmsAllocationVerifyReleaseAction(
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
        return new LocalActionDescriptor(ACTION_KEY, "1", "释放终态核验", "SAME_TX", true);
    }

    @Override
    public LocalActionInput provide(ActionContext context) {
        LocalActionInput input = inputReader.read(context, ACTION_KEY);
        WmsAllocationReleaseCommand release = jsonMapper.readValue(
            input.payloadJson(), WmsAllocationReleaseCommand.class);
        if (release == null || release.identity() == null || release.expectations() == null) {
            throw new ServiceException("释放计划冻结输入缺少释放命令");
        }
        WmsAllocationReleaseVerifyCommand verify = new WmsAllocationReleaseVerifyCommand(
            release.effectKey(),
            release.evidenceHash(),
            release.identity(),
            release.expectations().stream()
                .map(item -> item.allocationLockId())
                .toList()
        );
        return new LocalActionInput(
            jsonMapper.writeValueAsString(verify),
            input.evidenceRef(),
            input.facts()
        );
    }

    @Override
    public ActionResult execute(ActionContext context) {
        String actionName = "释放终态核验";
        WmsAllocationActionSupport.requirePayload(context, actionName, LOCAL_EFFECT_KEY);
        WmsAllocationReleaseVerifyCommand command = jsonMapper.readValue(
            context.payloadJson(), WmsAllocationReleaseVerifyCommand.class);
        if (command == null || command.identity() == null) {
            throw new ServiceException(actionName + "输入缺少释放任务身份");
        }
        WmsAllocationActionSupport.requireInstance(
            context,
            actionName,
            instanceMapper,
            command.identity().planInstanceId(),
            command.identity().businessType(),
            command.identity().businessId(),
            command.identity().taskKey()
        );
        WmsAllocationReleaseResult result = releaseService.verifyRelease(command);
        return new ActionResult(
            EffectResult.SUCCEEDED,
            context.evidenceRef(),
            null,
            null,
            null,
            null,
            result.checkpointJson(),
            "释放终态核验通过"
        );
    }
}
