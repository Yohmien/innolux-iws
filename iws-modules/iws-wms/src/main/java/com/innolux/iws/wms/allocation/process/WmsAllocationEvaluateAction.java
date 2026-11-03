/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配计划动作
 * @Version      : V1.0
 * @Description  : 固化分配候选、排序和解释的 SAME_TX 本地动作；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.process;

import com.innolux.iws.process.domain.ProcessInstance;
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

/** EVALUATE_ALLOCATION 生产能力。 */
public class WmsAllocationEvaluateAction implements LocalAction, LocalActionInputProvider {

    public static final String ACTION_KEY = "EVALUATE_ALLOCATION";

    public static final String LOCAL_EFFECT_KEY = "WMS:EVALUATE_ALLOCATION";

    private final WmsAllocationReservationService reservationService;

    private final ProcessInstanceMapper instanceMapper;

    private final JsonMapper jsonMapper;

    private final WmsFrozenActionInputReader inputReader;

    public WmsAllocationEvaluateAction(
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
        return new LocalActionDescriptor(ACTION_KEY, "1", "分配候选评估", "SAME_TX", true);
    }

    @Override
    public LocalActionInput provide(ActionContext context) {
        return inputReader.read(context, ACTION_KEY);
    }

    @Override
    public ActionResult execute(ActionContext context) {
        WmsAllocationReserveCommand command = WmsAllocationActionSupport.readCommand(
            context, "分配候选评估", LOCAL_EFFECT_KEY, jsonMapper, instanceMapper);
        WmsAllocationReservationResult result = reservationService.evaluate(command);
        return new ActionResult(
            EffectResult.SUCCEEDED,
            context.evidenceRef(),
            null,
            null,
            null,
            null,
            WmsAllocationActionSupport.checkpoint(jsonMapper, result),
            "分配候选评估已提交"
        );
    }
}
