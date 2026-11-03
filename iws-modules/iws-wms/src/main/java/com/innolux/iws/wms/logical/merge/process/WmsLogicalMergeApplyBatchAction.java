/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 原子迁移一个固定批次的逻辑归属并追加历史；
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

/** APPLY_LOGICAL_MERGE 计划动作。 */
public class WmsLogicalMergeApplyBatchAction
    implements LocalAction, LocalActionInputProvider {

    private final WmsLogicalMergeOrchestrationService orchestrationService;
    private final ProcessInstanceMapper instanceMapper;
    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;
    private final WmsFrozenActionInputReader inputReader;

    public WmsLogicalMergeApplyBatchAction(
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
            WmsLogicalMergePlanContract.APPLY_BATCH,
            "1", "应用逻辑归属批次", "SAME_TX", true);
    }

    @Override
    public LocalActionInput provide(ActionContext context) {
        return inputReader.read(context, WmsLogicalMergePlanContract.APPLY_BATCH);
    }

    @Override
    public ActionResult execute(ActionContext context) {
        long batchId = WmsLogicalMergeActionSupport.requireBusinessId(
            context,
            "应用逻辑归属批次",
            WmsLogicalMergePlanContract.APPLY_BATCH_EFFECT,
            WmsLogicalMergePlanContract.BATCH_PLAN_TYPE,
            instanceMapper,
            jdbcTemplate
        );
        var value = orchestrationService.applyBatch(batchId);
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("status", "APPLIED");
        checkpoint.put("batchId", batchId);
        checkpoint.put("inputDigest", value.inputDigest());
        checkpoint.put("migratedCount", value.migratedCount());
        checkpoint.put("alreadyTargetCount", value.alreadyTargetCount());
        checkpoint.put("conflictRetainedCount", value.conflictRetainedCount());
        return new ActionResult(
            EffectResult.SUCCEEDED, context.evidenceRef(), null, null,
            null, null, jsonMapper.writeValueAsString(checkpoint),
            "逻辑归属批次已应用");
    }
}
