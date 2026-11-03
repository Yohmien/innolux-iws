/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 SAME_TX 集成测试
 * @Version      : V1.0
 * @Description  : 仅测试使用的 VERIFY_MATERIAL_CHANGE LocalAction 回读适配；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.process;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.process.runtime.ActionResult;
import com.innolux.iws.process.runtime.LocalAction;
import com.innolux.iws.process.runtime.LocalActionDescriptor;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceSnapshot;
import com.innolux.iws.wms.material.trace.service.WmsMaterialTraceService;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 测试专用 VERIFY LocalAction。
 *
 * @author YONGJIE.PENG
 */
final class FixtureMaterialChangeVerifyAction implements LocalAction {

    static final String ACTION_KEY = "VERIFY_MATERIAL_CHANGE";

    static final String LOCAL_EFFECT_KEY = "WMS:VERIFY_MATERIAL_CHANGE";

    private final WmsMaterialTraceService traceService;

    private final JdbcTemplate jdbcTemplate;

    private final JsonMapper jsonMapper;

    private Fixture fixture;

    FixtureMaterialChangeVerifyAction(WmsMaterialTraceService traceService,
                                      JdbcTemplate jdbcTemplate,
                                      JsonMapper jsonMapper) {
        this.traceService = traceService;
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    void prepare(String effectKey, long inventoryId, long targetStorageId) {
        this.fixture = new Fixture(effectKey, inventoryId, targetStorageId);
    }

    @Override
    public LocalActionDescriptor describe() {
        return new LocalActionDescriptor(ACTION_KEY, "1", "物料位置结果核验", "SAME_TX", true);
    }

    @Override
    public ActionResult execute(ActionContext context) {
        if (context == null || context.direction() != EventDirection.INTERNAL) {
            throw new ServiceException("物料位置核验fixture只允许INTERNAL事件");
        }
        if (!LOCAL_EFFECT_KEY.equals(context.localEffectKey())) {
            throw new ServiceException("物料位置核验fixture本地效果键不匹配");
        }
        if (fixture == null) {
            throw new IllegalStateException("未准备物料位置核验fixture");
        }
        WmsMaterialTraceSnapshot trace = traceService.findByEffectKey(fixture.effectKey())
            .orElseThrow(() -> new ServiceException("核验时未读取到物料trace"));
        Long inventoryStorageId = jdbcTemplate.queryForObject(
            "select storage_id from wms_inventory where inventory_id = ?",
            Long.class,
            fixture.inventoryId()
        );
        Long flowId = jdbcTemplate.queryForObject(
            "select flow_id from wms_inventory_flow where idempotency_key = ?",
            Long.class,
            fixture.effectKey()
        );
        if (!"APPLIED".equals(trace.history().effectResult())
            || trace.items().size() != 1
            || !Long.valueOf(fixture.targetStorageId()).equals(inventoryStorageId)
            || !Long.valueOf(fixture.targetStorageId())
                .equals(trace.items().getFirst().physicalToStorageId())) {
            throw new ServiceException("已提交物料事实与核验目标不一致");
        }
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("status", "VERIFIED");
        checkpoint.put("traceId", trace.history().traceId());
        checkpoint.put("flowId", flowId);
        checkpoint.put("inventoryId", fixture.inventoryId());
        checkpoint.put("toStorageId", fixture.targetStorageId());
        return new ActionResult(
            EffectResult.SUCCEEDED,
            context.evidenceRef(),
            null,
            null,
            null,
            null,
            jsonMapper.writeValueAsString(checkpoint),
            "物料位置事实核验通过"
        );
    }

    private record Fixture(String effectKey, long inventoryId, long targetStorageId) {
    }
}
