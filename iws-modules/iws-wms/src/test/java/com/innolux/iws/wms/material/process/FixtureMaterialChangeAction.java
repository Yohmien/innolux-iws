/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 SAME_TX 集成测试
 * @Version      : V1.0
 * @Description  : 仅测试使用的 APPLY_MATERIAL_CHANGE LocalAction 输入适配；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.process;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.process.runtime.ActionResult;
import com.innolux.iws.process.runtime.LocalAction;
import com.innolux.iws.process.runtime.LocalActionDescriptor;
import com.innolux.iws.wms.material.change.domain.WmsMaterialChangeCommand;
import com.innolux.iws.wms.material.change.domain.WmsMaterialChangeResult;
import com.innolux.iws.wms.material.change.domain.WmsMaterialAllocationConsumption;
import com.innolux.iws.wms.material.change.service.WmsMaterialChangeService;
import com.innolux.iws.wms.load.change.domain.WmsLoadChangeCommand;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 测试专用 APPLY LocalAction。
 *
 * @author YONGJIE.PENG
 */
public final class FixtureMaterialChangeAction implements LocalAction {

    public static final String ACTION_KEY = "APPLY_MATERIAL_CHANGE";

    public static final String LOCAL_EFFECT_KEY = "WMS:APPLY_MATERIAL_CHANGE";

    private static final String BUSINESS_NAME = "在库物料位置变更fixture";

    private final WmsMaterialChangeService changeService;

    private final ProcessInstanceMapper instanceMapper;

    private final JsonMapper jsonMapper;

    private Fixture fixture;

    public FixtureMaterialChangeAction(WmsMaterialChangeService changeService,
                                       ProcessInstanceMapper instanceMapper,
                                       JsonMapper jsonMapper) {
        this.changeService = changeService;
        this.instanceMapper = instanceMapper;
        this.jsonMapper = jsonMapper;
    }

    public void prepare(long inventoryId, int expectedVersion, long targetStorageId,
                        LocalDateTime occurredAt, String fault) {
        prepare(inventoryId, expectedVersion, targetStorageId, occurredAt, fault, null, null);
    }

    public void prepare(long inventoryId, int expectedVersion, long targetStorageId,
                        LocalDateTime occurredAt, String fault, WmsLoadChangeCommand loadChange,
                        WmsMaterialAllocationConsumption allocationConsumption) {
        this.fixture = new Fixture(
            inventoryId,
            expectedVersion,
            targetStorageId,
            occurredAt,
            fault,
            loadChange,
            allocationConsumption
        );
    }

    @Override
    public LocalActionDescriptor describe() {
        return new LocalActionDescriptor(ACTION_KEY, "1", "物料位置事实", "SAME_TX", true);
    }

    @Override
    public ActionResult execute(ActionContext context) {
        if (context == null || context.direction() != EventDirection.INTERNAL) {
            throw new ServiceException("物料位置fixture只允许INTERNAL事件");
        }
        if (!LOCAL_EFFECT_KEY.equals(context.localEffectKey())) {
            throw new ServiceException("物料位置fixture本地效果键不匹配");
        }
        if (fixture == null) {
            throw new IllegalStateException("未准备物料位置fixture");
        }
        ProcessInstance instance = instanceMapper.selectById(context.instanceId());
        if (instance == null) {
            throw new ServiceException("计划实例不存在");
        }
        WmsMaterialChangeCommand command = new WmsMaterialChangeCommand(
            "WMS:" + context.inputHash(),
            context.inputHash(),
            fixture.inventoryId(),
            fixture.expectedVersion(),
            fixture.targetStorageId(),
            fixture.occurredAt(),
            "fixture物料位置事实",
            context.evidenceRef(),
            "IWS",
            context.requestId(),
            instance.getBusinessType(),
            BUSINESS_NAME,
            positiveLong(instance.getBusinessId(), "businessId"),
            "BIZ-001",
            null,
            instance.getBusinessType(),
            positiveLong(instance.getBusinessId(), "businessId"),
            instance.getTaskKey(),
            context.instanceId(),
            instance.getPlanType(),
            instance.getPlanType(),
            String.valueOf(instance.getDefinitionVersionId()),
            context.eventId(),
            context.attemptId(),
            instance.getCreateBy(),
            instance.getCreateDept()
        );
        WmsMaterialChangeResult result = changeService.apply(
            command,
            bindLoadChange(command, fixture.loadChange(), context),
            fixture.allocationConsumption()
        );
        return new ActionResult(
            EffectResult.SUCCEEDED,
            context.evidenceRef(),
            null,
            null,
            null,
            null,
            checkpoint(result),
            "物料位置事实已提交"
        );
    }

    private String checkpoint(WmsMaterialChangeResult result) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", "APPLIED");
        value.put("traceId", result.trace().history().traceId());
        value.put("flowId", result.flowId());
        value.put("inventoryId", result.inventoryId());
        value.put("fromStorageId", result.fromStorageId());
        value.put("toStorageId", result.toStorageId());
        value.put("quantity", result.quantity());
        value.put("traceEffectResult", result.trace().history().effectResult());
        if (result.loadChange() != null) {
            value.put("occupancyEffectId", result.loadChange().occupancyEffectId());
            value.put("activeOccupancyIds", result.loadChange().activeOccupancyIds());
            value.put("closedOccupancyIds", result.loadChange().closedOccupancyIds());
            value.put("rootNodeIds", result.loadChange().rootNodeIds());
        }
        if (result.allocationEffect() != null) {
            value.put("allocationLockIds", result.allocationEffect().allocationLockIds());
            value.put("allocationEffectResult", result.allocationEffect().targetEffectResult());
            value.put("allocationGenerations", result.allocationEffect().generations());
            value.put("consumptionTraceId", result.trace().history().traceId());
        }
        if (fixture.fault() != null) {
            value.put("fixtureFault", fixture.fault());
        }
        return jsonMapper.writeValueAsString(value);
    }

    private WmsLoadChangeCommand bindLoadChange(
        WmsMaterialChangeCommand command,
        WmsLoadChangeCommand loadChange,
        ActionContext context
    ) {
        if (loadChange == null) {
            return null;
        }
        return new WmsLoadChangeCommand(
            command.effectKey(),
            command.inputHash(),
            loadChange.mutations(),
            command.occurredAt(),
            command.reason(),
            command.evidenceRef(),
            command.sourceSystem(),
            command.requestId(),
            command.businessType(),
            command.businessName(),
            command.businessId(),
            command.businessNo(),
            command.businessLineId(),
            command.taskKey(),
            command.planInstanceId(),
            command.definitionVersion(),
            context.eventId(),
            context.attemptId(),
            command.actorId(),
            command.departmentId()
        );
    }

    private long positiveLong(String value, String field) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new ServiceException(field + "必须为正整数");
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new ServiceException(field + "必须为数字: " + value);
        }
    }

    private record Fixture(long inventoryId, int expectedVersion, long targetStorageId,
                           LocalDateTime occurredAt, String fault,
                           WmsLoadChangeCommand loadChange,
                           WmsMaterialAllocationConsumption allocationConsumption) {
    }
}
