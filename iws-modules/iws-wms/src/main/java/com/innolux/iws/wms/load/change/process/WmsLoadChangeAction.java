/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 装拆计划动作
 * @Version      : V1.0
 * @Description  : 在计划 SAME_TX 内提交装拆关系、根位置、容量占用和历史；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.change.process;

import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.process.runtime.ActionResult;
import com.innolux.iws.process.runtime.LocalAction;
import com.innolux.iws.process.runtime.LocalActionDescriptor;
import com.innolux.iws.process.runtime.LocalActionInput;
import com.innolux.iws.process.runtime.LocalActionInputProvider;
import com.innolux.iws.wms.load.change.domain.WmsLoadChangeCommand;
import com.innolux.iws.wms.load.change.domain.WmsLoadChangeResult;
import com.innolux.iws.wms.load.change.service.WmsLoadChangeService;
import com.innolux.iws.wms.process.WmsFrozenActionInputReader;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** APPLY_LOAD_CHANGE 生产能力。 */
public class WmsLoadChangeAction implements LocalAction, LocalActionInputProvider {

    public static final String ACTION_KEY = "APPLY_LOAD_CHANGE";

    public static final String LOCAL_EFFECT_KEY = "WMS:APPLY_LOAD_CHANGE";

    private final WmsLoadChangeService loadChangeService;

    private final ProcessInstanceMapper instanceMapper;

    private final JsonMapper jsonMapper;

    private final WmsFrozenActionInputReader inputReader;

    public WmsLoadChangeAction(
        WmsLoadChangeService loadChangeService,
        ProcessInstanceMapper instanceMapper,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        this.loadChangeService = loadChangeService;
        this.instanceMapper = instanceMapper;
        this.jsonMapper = jsonMapper;
        this.inputReader = inputReader;
    }

    @Override
    public LocalActionDescriptor describe() {
        return new LocalActionDescriptor(ACTION_KEY, "1", "装拆容量事实", "SAME_TX", true);
    }

    @Override
    public LocalActionInput provide(ActionContext context) {
        return inputReader.read(context, ACTION_KEY);
    }

    @Override
    public ActionResult execute(ActionContext context) {
        WmsLoadChangeCommand command = WmsLoadChangeActionSupport.readCommand(
            context, "装拆容量事实", LOCAL_EFFECT_KEY, jsonMapper, instanceMapper);
        WmsLoadChangeResult result = loadChangeService.apply(command);
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("status", "APPLIED");
        checkpoint.put("occupancyEffectId", result.occupancyEffectId());
        checkpoint.put("effectKey", result.effectKey());
        checkpoint.put("relationHistoryIds", result.relationHistoryIds());
        checkpoint.put("activeOccupancyIds", result.activeOccupancyIds());
        checkpoint.put("closedOccupancyIds", result.closedOccupancyIds());
        checkpoint.put("rootNodeIds", result.rootNodeIds());
        checkpoint.put("storageRootCounts", result.storageRootCounts());
        return new ActionResult(
            EffectResult.SUCCEEDED,
            context.evidenceRef(),
            null,
            null,
            null,
            null,
            jsonMapper.writeValueAsString(checkpoint),
            "装拆容量事实已提交"
        );
    }
}
