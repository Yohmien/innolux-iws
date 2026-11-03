/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 等待全部批次完成后收敛来源、退役并关闭父单；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.logical.merge.process;

import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.process.runtime.ActionResult;
import com.innolux.iws.process.runtime.LocalAction;
import com.innolux.iws.process.runtime.LocalActionDescriptor;
import com.innolux.iws.process.runtime.LocalActionInput;
import com.innolux.iws.process.runtime.LocalActionInputProvider;
import com.innolux.iws.wms.process.WmsFrozenActionInputReader;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** CLOSE_MERGE 父计划动作。 */
public class WmsLogicalMergeCloseParentAction
    implements LocalAction, LocalActionInputProvider {

    private final WmsLogicalMergeOrchestrationService orchestrationService;
    private final ProcessInstanceMapper instanceMapper;
    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;
    private final WmsFrozenActionInputReader inputReader;

    public WmsLogicalMergeCloseParentAction(
        WmsLogicalMergeOrchestrationService orchestrationService,
        ProcessInstanceMapper instanceMapper,
        JdbcTemplate jdbcTemplate,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        this.orchestrationService = orchestrationService;
        this.instanceMapper = instanceMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
        this.inputReader = inputReader;
    }

    @Override
    public LocalActionDescriptor describe() {
        return new LocalActionDescriptor(
            WmsLogicalMergePlanContract.CLOSE_PARENT,
            "1", "收敛并退役来源", "SAME_TX", true);
    }

    @Override
    public LocalActionInput provide(ActionContext context) {
        return inputReader.read(context, WmsLogicalMergePlanContract.CLOSE_PARENT);
    }

    @Override
    public ActionResult execute(ActionContext context) {
        long orderId = WmsLogicalMergeActionSupport.requireBusinessId(
            context,
            "收敛并退役来源",
            WmsLogicalMergePlanContract.CLOSE_PARENT_EFFECT,
            WmsLogicalMergePlanContract.CLOSE_PLAN_TYPE,
            instanceMapper,
            jdbcTemplate
        );
        var value = orchestrationService.closeParent(orderId);
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("status", "COMPLETED");
        checkpoint.put("mergeOrderId", orderId);
        checkpoint.put("barrierId", value.barrierId());
        checkpoint.put("barrierStatus", value.barrierStatus());
        return new ActionResult(
            EffectResult.SUCCEEDED, context.evidenceRef(), null, null,
            null, null, jsonMapper.writeValueAsString(checkpoint),
            "逻辑合并已收敛");
    }
}
