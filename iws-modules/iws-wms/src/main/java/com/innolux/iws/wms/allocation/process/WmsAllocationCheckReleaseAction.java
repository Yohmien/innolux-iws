/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配释放计划动作
 * @Version      : V1.0
 * @Description  : 锁定待释放分配锁并冻结无外部效果证据；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.process;

import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.process.runtime.ActionResult;
import com.innolux.iws.process.runtime.LocalAction;
import com.innolux.iws.process.runtime.LocalActionDescriptor;
import com.innolux.iws.process.runtime.LocalActionInput;
import com.innolux.iws.process.runtime.LocalActionInputProvider;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationEvidenceResult;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseCheckCommand;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseCommand;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseIdentity;
import com.innolux.iws.wms.allocation.release.service.WmsAllocationReleaseService;
import com.innolux.iws.wms.process.WmsFrozenActionInputReader;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** CHECK_RELEASE_EVIDENCE 生产能力。 */
public class WmsAllocationCheckReleaseAction implements LocalAction, LocalActionInputProvider {

    public static final String ACTION_KEY = "CHECK_RELEASE_EVIDENCE";

    public static final String LOCAL_EFFECT_KEY = "WMS:CHECK_RELEASE_EVIDENCE";

    private final WmsAllocationReleaseService releaseService;

    private final ProcessInstanceMapper instanceMapper;

    private final JsonMapper jsonMapper;

    private final WmsFrozenActionInputReader inputReader;

    public WmsAllocationCheckReleaseAction(
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
        return new LocalActionDescriptor(ACTION_KEY, "1", "释放证据检查", "SAME_TX", true);
    }

    @Override
    public LocalActionInput provide(ActionContext context) {
        LocalActionInput input = inputReader.read(context, ACTION_KEY);
        WmsAllocationReleaseCommand release = jsonMapper.readValue(
            input.payloadJson(), WmsAllocationReleaseCommand.class);
        if (release == null || release.identity() == null || release.expectations() == null) {
            throw new com.innolux.iws.common.core.exception.ServiceException(
                "释放计划冻结输入缺少释放命令");
        }
        WmsAllocationReleaseCheckCommand check = new WmsAllocationReleaseCheckCommand(
            release.identity(),
            release.normalizedEvidenceType(),
            release.evidenceRef(),
            release.expectations().stream()
                .map(item -> item.allocationLockId())
                .toList()
        );
        return new LocalActionInput(
            jsonMapper.writeValueAsString(check),
            input.evidenceRef(),
            input.facts()
        );
    }

    @Override
    public ActionResult execute(ActionContext context) {
        String actionName = "释放证据检查";
        WmsAllocationActionSupport.requirePayload(context, actionName, LOCAL_EFFECT_KEY);
        WmsAllocationReleaseCheckCommand command = jsonMapper.readValue(
            context.payloadJson(), WmsAllocationReleaseCheckCommand.class);
        if (command == null || command.identity() == null) {
            throw new com.innolux.iws.common.core.exception.ServiceException(
                actionName + "输入缺少释放任务身份");
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
        WmsAllocationEvidenceResult result = releaseService.checkEvidence(command);
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("status", "EVIDENCE_CHECKED");
        checkpoint.put("evidenceHash", result.evidenceHash());
        checkpoint.put("evidenceRevision", result.evidenceRevision());
        checkpoint.put("channelLockIds", result.channelLockIds());
        checkpoint.put("expectations", result.expectations());
        checkpoint.put("allocationLockIds", result.expectations().stream()
            .map(item -> item.allocationLockId()).toList());
        checkpoint.put("evidenceType", command.evidenceType());
        checkpoint.put("evidenceRef", command.evidenceRef());
        return new ActionResult(
            EffectResult.SUCCEEDED,
            context.evidenceRef(),
            null,
            null,
            null,
            null,
            jsonMapper.writeValueAsString(checkpoint),
            "释放证据检查已提交"
        );
    }
}
