/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 启动固定批次并确认冻结身份；
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

/** CAPTURE_MERGE_BATCH 计划动作。 */
public class WmsLogicalMergeCaptureBatchAction
    implements LocalAction, LocalActionInputProvider {

    private final WmsLogicalMergeOrchestrationService orchestrationService;
    private final ProcessInstanceMapper instanceMapper;
    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;
    private final WmsFrozenActionInputReader inputReader;

    public WmsLogicalMergeCaptureBatchAction(
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
            WmsLogicalMergePlanContract.CAPTURE_BATCH,
            "1", "冻结合并批次", "SAME_TX", true);
    }

    @Override
    public LocalActionInput provide(ActionContext context) {
        return inputReader.read(context, WmsLogicalMergePlanContract.CAPTURE_BATCH);
    }

    @Override
    public ActionResult execute(ActionContext context) {
        long batchId = WmsLogicalMergeActionSupport.requireBusinessId(
            context,
            "冻结合并批次",
            WmsLogicalMergePlanContract.CAPTURE_BATCH_EFFECT,
            WmsLogicalMergePlanContract.BATCH_PLAN_TYPE,
            instanceMapper,
            jdbcTemplate
        );
        var value = orchestrationService.captureBatch(batchId);
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("status", "CAPTURED");
        checkpoint.put("batchId", batchId);
        checkpoint.put("mergeOrderId", value.mergeOrderId());
        checkpoint.put("batchNo", value.batchNo());
        checkpoint.put("inputDigest", value.inputDigest());
        return new ActionResult(
            EffectResult.SUCCEEDED, context.evidenceRef(), null, null,
            null, null, jsonMapper.writeValueAsString(checkpoint),
            "逻辑合并批次已冻结");
    }
}
