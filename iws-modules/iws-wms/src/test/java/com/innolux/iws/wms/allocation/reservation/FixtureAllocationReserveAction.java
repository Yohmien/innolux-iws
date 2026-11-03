/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 锁位短事务集成测试
 * @Version      : V1.0
 * @Description  : 仅测试使用的 RESERVE_STORAGE LocalAction 输入适配；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.process.runtime.ActionResult;
import com.innolux.iws.process.runtime.LocalAction;
import com.innolux.iws.process.runtime.LocalActionDescriptor;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationReservationResult;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationReserveCommand;
import com.innolux.iws.wms.allocation.reservation.service.WmsAllocationReservationService;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** 测试专用 RESERVE LocalAction。 */
final class FixtureAllocationReserveAction implements LocalAction {

    static final String ACTION_KEY = "RESERVE_STORAGE";

    static final String LOCAL_EFFECT_KEY = "WMS:RESERVE_STORAGE";

    private final WmsAllocationReservationService reservationService;

    private final ProcessInstanceMapper instanceMapper;

    private final JsonMapper jsonMapper;

    private Fixture fixture;

    FixtureAllocationReserveAction(
        WmsAllocationReservationService reservationService,
        ProcessInstanceMapper instanceMapper,
        JsonMapper jsonMapper
    ) {
        this.reservationService = reservationService;
        this.instanceMapper = instanceMapper;
        this.jsonMapper = jsonMapper;
    }

    void prepare(WmsAllocationReserveCommand command, String fault) {
        fixture = new Fixture(command, fault);
    }

    @Override
    public LocalActionDescriptor describe() {
        return new LocalActionDescriptor(ACTION_KEY, "1", "分配储位锁位", "SAME_TX", true);
    }

    @Override
    public ActionResult execute(ActionContext context) {
        if (context == null || context.direction() != EventDirection.INTERNAL) {
            throw new ServiceException("分配锁位fixture只允许INTERNAL事件");
        }
        if (!LOCAL_EFFECT_KEY.equals(context.localEffectKey())) {
            throw new ServiceException("分配锁位fixture本地效果键不匹配");
        }
        if (fixture == null) {
            throw new IllegalStateException("未准备分配锁位fixture");
        }
        if (fixture.command().business().planInstanceId() != context.instanceId()) {
            throw new ServiceException("分配锁位fixture计划实例不匹配");
        }
        ProcessInstance instance = instanceMapper.selectById(context.instanceId());
        if (instance == null) {
            throw new ServiceException("计划实例不存在");
        }
        WmsAllocationReservationResult result = reservationService.reserve(fixture.command());
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("status", result.result());
        checkpoint.put("decisionId", result.decisionId());
        checkpoint.put("selectedStorageId", result.selectedStorageId());
        checkpoint.put("selectedCandidateId", result.selectedCandidateId());
        checkpoint.put("channelLockIds", result.channelLockIds());
        checkpoint.put("allocationLockIds", result.allocationLockIds());
        if (fixture.fault() != null) {
            checkpoint.put("fixtureFault", fixture.fault());
        }
        EffectResult effect = "LOCKED".equals(result.result())
            ? EffectResult.SUCCEEDED : EffectResult.FAILED;
        return new ActionResult(
            effect,
            context.evidenceRef(),
            null,
            null,
            null,
            null,
            jsonMapper.writeValueAsString(checkpoint),
            "LOCKED".equals(result.result()) ? "分配锁位已提交" : "无可用分配候选"
        );
    }

    private record Fixture(WmsAllocationReserveCommand command, String fault) {
    }
}
