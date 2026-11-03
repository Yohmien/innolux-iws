/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 装拆计划动作
 * @Version      : V1.0
 * @Description  : 在 SAME_TX 内执行装拆结构预检并固化固定影响报告；
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
import com.innolux.iws.wms.load.change.domain.WmsLoadChangeCheckResult;
import com.innolux.iws.wms.load.change.domain.WmsLoadChangeCommand;
import com.innolux.iws.wms.load.change.service.WmsLoadChangeService;
import com.innolux.iws.wms.process.WmsFrozenActionInputReader;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** CHECK_LOAD_CHANGE 生产能力。 */
public class WmsLoadChangeCheckAction implements LocalAction, LocalActionInputProvider {

    public static final String ACTION_KEY = "CHECK_LOAD_CHANGE";

    public static final String LOCAL_EFFECT_KEY = "WMS:CHECK_LOAD_CHANGE";

    private final WmsLoadChangeService loadChangeService;

    private final ProcessInstanceMapper instanceMapper;

    private final JsonMapper jsonMapper;

    private final WmsFrozenActionInputReader inputReader;

    public WmsLoadChangeCheckAction(
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
        return new LocalActionDescriptor(ACTION_KEY, "1", "装拆结构预检", "SAME_TX", true);
    }

    @Override
    public LocalActionInput provide(ActionContext context) {
        return inputReader.read(context, ACTION_KEY);
    }

    @Override
    public ActionResult execute(ActionContext context) {
        WmsLoadChangeCommand command = WmsLoadChangeActionSupport.readCommand(
            context, "装拆结构预检", LOCAL_EFFECT_KEY, jsonMapper, instanceMapper);
        WmsLoadChangeCheckResult result = loadChangeService.check(command);
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("status", "CHECKED");
        checkpoint.put("effectKey", result.effectKey());
        checkpoint.put("inputHash", result.inputHash());
        checkpoint.put("changes", result.changes());
        checkpoint.put("beforeRootNodeIds", result.beforeRootNodeIds());
        checkpoint.put("afterRootNodeIds", result.afterRootNodeIds());
        checkpoint.put("impactedStorageIds", result.impactedStorageIds());
        return new ActionResult(
            EffectResult.SUCCEEDED,
            context.evidenceRef(),
            null,
            null,
            null,
            null,
            jsonMapper.writeValueAsString(checkpoint),
            "装拆结构预检已提交"
        );
    }
}
