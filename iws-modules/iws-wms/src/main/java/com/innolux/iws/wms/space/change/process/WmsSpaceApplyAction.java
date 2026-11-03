/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P2 空间生命周期
 * @Version      : V1.0
 * @Description  : 在同一事务提交空间实体、版本、历史与生效指针；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.space.change.process;

import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.process.runtime.ActionResult;
import com.innolux.iws.process.runtime.LocalAction;
import com.innolux.iws.process.runtime.LocalActionDescriptor;
import com.innolux.iws.process.runtime.LocalActionInput;
import com.innolux.iws.process.runtime.LocalActionInputProvider;
import com.innolux.iws.wms.process.WmsFrozenActionInputReader;
import com.innolux.iws.wms.space.change.domain.WmsSpaceChangeResult;
import com.innolux.iws.wms.space.change.service.WmsSpaceChangeService;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** APPLY_CONFIGURATION 计划动作。 */
public class WmsSpaceApplyAction implements LocalAction, LocalActionInputProvider {

    private final WmsSpaceChangeService changeService;

    private final ProcessInstanceMapper instanceMapper;

    private final JdbcTemplate jdbcTemplate;

    private final JsonMapper jsonMapper;

    private final WmsFrozenActionInputReader inputReader;

    public WmsSpaceApplyAction(
        WmsSpaceChangeService changeService,
        ProcessInstanceMapper instanceMapper,
        JdbcTemplate jdbcTemplate,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        this.changeService = changeService;
        this.instanceMapper = instanceMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
        this.inputReader = inputReader;
    }

    @Override
    public LocalActionDescriptor describe() {
        return new LocalActionDescriptor(
            WmsSpacePlanContract.APPLY_EVENT,
            "1",
            "应用空间配置",
            "SAME_TX",
            true
        );
    }

    @Override
    public LocalActionInput provide(ActionContext context) {
        return inputReader.read(context, WmsSpacePlanContract.APPLY_EVENT);
    }

    @Override
    public ActionResult execute(ActionContext context) {
        long orderId = WmsSpaceActionSupport.requireOrder(
            context,
            "应用空间配置",
            WmsSpacePlanContract.APPLY_EFFECT,
            instanceMapper,
            jdbcTemplate
        );
        WmsSpaceChangeResult value = changeService.applyWithinTransaction(orderId);
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("status", "APPLIED");
        checkpoint.put("changeOrderId", orderId);
        checkpoint.put("objectType", value.objectType());
        checkpoint.put("objectId", value.objectId());
        checkpoint.put("objectRevision", value.objectRevision());
        checkpoint.put("locationVersionId", value.locationVersionId());
        checkpoint.put("locationEffectiveRevision", value.locationEffectiveRevision());
        checkpoint.put("configDigest", value.configDigest());
        return new ActionResult(
            EffectResult.SUCCEEDED,
            context.evidenceRef(),
            null,
            null,
            null,
            null,
            jsonMapper.writeValueAsString(checkpoint),
            "空间配置已应用"
        );
    }
}
