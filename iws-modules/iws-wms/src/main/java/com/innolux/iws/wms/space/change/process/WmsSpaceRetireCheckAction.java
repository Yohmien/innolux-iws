/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P2 空间生命周期
 * @Version      : V1.0
 * @Description  : 只读检查空间退役子项、库存、占用和活动锁阻碍；
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
import com.innolux.iws.wms.space.change.domain.WmsSpaceChangeValidation;
import com.innolux.iws.wms.space.change.service.WmsSpaceChangeService;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** CHECK_RETIREMENT 计划动作。 */
public class WmsSpaceRetireCheckAction implements LocalAction, LocalActionInputProvider {

    private final WmsSpaceChangeService changeService;

    private final ProcessInstanceMapper instanceMapper;

    private final JdbcTemplate jdbcTemplate;

    private final JsonMapper jsonMapper;

    private final WmsFrozenActionInputReader inputReader;

    public WmsSpaceRetireCheckAction(
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
            WmsSpacePlanContract.CHECK_RETIRE_EVENT,
            "1",
            "检查空间退役阻碍",
            "SAME_TX",
            true
        );
    }

    @Override
    public LocalActionInput provide(ActionContext context) {
        return inputReader.read(context, WmsSpacePlanContract.CHECK_RETIRE_EVENT);
    }

    @Override
    public ActionResult execute(ActionContext context) {
        long orderId = WmsSpaceActionSupport.requireOrder(
            context,
            "检查空间退役阻碍",
            WmsSpacePlanContract.CHECK_RETIRE_EFFECT,
            instanceMapper,
            jdbcTemplate
        );
        WmsSpaceChangeValidation value =
            changeService.checkRetirementWithinTransaction(orderId);
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("status", "CHECKED");
        checkpoint.put("changeOrderId", orderId);
        checkpoint.put("objectType", value.objectType());
        checkpoint.put("objectId", value.objectId());
        checkpoint.put("configDigest", value.configDigest());
        return new ActionResult(
            EffectResult.SUCCEEDED,
            context.evidenceRef(),
            null,
            null,
            null,
            null,
            jsonMapper.writeValueAsString(checkpoint),
            "空间退役检查通过"
        );
    }
}
