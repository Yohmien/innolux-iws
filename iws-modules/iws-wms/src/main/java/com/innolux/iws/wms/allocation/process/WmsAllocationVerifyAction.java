/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配计划动作
 * @Version      : V1.0
 * @Description  : 回读分配决策和锁终态的 SAME_TX 本地动作；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.process;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventDirection;
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

/** VERIFY_ALLOCATION 生产能力。 */
public class WmsAllocationVerifyAction implements LocalAction, LocalActionInputProvider {

    public static final String ACTION_KEY = "VERIFY_ALLOCATION";

    public static final String LOCAL_EFFECT_KEY = "WMS:VERIFY_ALLOCATION";

    private final WmsAllocationReservationService reservationService;

    private final JsonMapper jsonMapper;

    private final WmsFrozenActionInputReader inputReader;

    public WmsAllocationVerifyAction(
        WmsAllocationReservationService reservationService,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        this.reservationService = reservationService;
        this.jsonMapper = jsonMapper;
        this.inputReader = inputReader;
    }

    @Override
    public LocalActionDescriptor describe() {
        return new LocalActionDescriptor(ACTION_KEY, "1", "分配锁位核验", "SAME_TX", true);
    }

    @Override
    public LocalActionInput provide(ActionContext context) {
        LocalActionInput input = inputReader.read(context, ACTION_KEY);
        WmsAllocationReserveCommand command = jsonMapper.readValue(
            input.payloadJson(), WmsAllocationReserveCommand.class);
        if (command == null || command.effectKey() == null) {
            throw new ServiceException("分配锁位核验冻结输入缺少效果键");
        }
        return new LocalActionInput(
            jsonMapper.writeValueAsString(new VerifyPayload(command.effectKey())),
            input.evidenceRef(),
            input.facts()
        );
    }

    @Override
    public ActionResult execute(ActionContext context) {
        if (context == null || context.direction() != EventDirection.INTERNAL) {
            throw new ServiceException("分配锁位核验只允许INTERNAL事件");
        }
        if (!LOCAL_EFFECT_KEY.equals(context.localEffectKey())) {
            throw new ServiceException("分配锁位核验本地效果键不匹配");
        }
        if (context.payloadJson() == null || context.payloadJson().isBlank()) {
            throw new ServiceException("分配锁位核验缺少冻结输入");
        }
        VerifyPayload payload = jsonMapper.readValue(context.payloadJson(), VerifyPayload.class);
        WmsAllocationReservationResult result = reservationService.verify(payload.effectKey());
        if (result.decisionId() <= 0) {
            throw new ServiceException("分配锁位核验未返回决策");
        }
        return new ActionResult(
            EffectResult.SUCCEEDED,
            context.evidenceRef(),
            null,
            null,
            null,
            null,
            WmsAllocationActionSupport.checkpoint(jsonMapper, result),
            "分配锁位核验通过"
        );
    }

    /** 核验输入只引用已持久效果键。 */
    public record VerifyPayload(String effectKey) {
    }
}
