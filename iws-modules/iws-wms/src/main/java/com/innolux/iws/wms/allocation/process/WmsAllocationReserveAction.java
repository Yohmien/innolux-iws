/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配计划动作
 * @Version      : V1.0
 * @Description  : 锁内复核并提交通道/储位锁的 SAME_TX 本地动作；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.process;

import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.process.runtime.ActionResult;
import com.innolux.iws.process.runtime.LocalAction;
import com.innolux.iws.process.runtime.LocalActionDescriptor;
import com.innolux.iws.process.runtime.LocalActionInput;
import com.innolux.iws.process.runtime.LocalActionInputProvider;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationReservationResult;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationReserveCommand;
import com.innolux.iws.wms.allocation.reservation.service.WmsAllocationReservationService;
import com.innolux.iws.wms.process.WmsFrozenActionInputReader;
import tools.jackson.databind.json.JsonMapper;

/** RESERVE_STORAGE 生产能力。 */
public class WmsAllocationReserveAction implements LocalAction, LocalActionInputProvider {

    public static final String ACTION_KEY = "RESERVE_STORAGE";

    public static final String LOCAL_EFFECT_KEY = "WMS:RESERVE_STORAGE";

    private final WmsAllocationReservationService reservationService;

    private final ProcessInstanceMapper instanceMapper;

    private final JsonMapper jsonMapper;

    private final WmsFrozenActionInputReader inputReader;

    public WmsAllocationReserveAction(
        WmsAllocationReservationService reservationService,
        ProcessInstanceMapper instanceMapper,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        this.reservationService = reservationService;
        this.instanceMapper = instanceMapper;
        this.jsonMapper = jsonMapper;
        this.inputReader = inputReader;
    }

    @Override
    public LocalActionDescriptor describe() {
        return new LocalActionDescriptor(ACTION_KEY, "1", "分配储位锁位", "SAME_TX", true);
    }

    @Override
    public LocalActionInput provide(ActionContext context) {
        return inputReader.read(context, ACTION_KEY);
    }

    @Override
    public ActionResult execute(ActionContext context) {
        WmsAllocationReserveCommand command = WmsAllocationActionSupport.readCommand(
            context, "分配储位锁位", LOCAL_EFFECT_KEY, jsonMapper, instanceMapper);
        WmsAllocationReservationResult result = reservationService.reserve(command);
        boolean locked = "LOCKED".equals(result.result());
        return new ActionResult(
            locked ? EffectResult.SUCCEEDED : EffectResult.FAILED,
            context.evidenceRef(),
            null,
            null,
            null,
            null,
            WmsAllocationActionSupport.checkpoint(jsonMapper, result),
            locked ? "分配储位锁位已提交" : "无可用分配候选"
        );
    }
}
