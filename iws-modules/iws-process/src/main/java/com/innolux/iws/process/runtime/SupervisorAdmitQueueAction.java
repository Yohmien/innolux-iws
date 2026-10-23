/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 每个监督 LOCAL 事件只准入一个候选，提交时重验 epoch/名额；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.process.api.SupervisorSlot;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import org.springframework.stereotype.Component;

/**
 * 一个本地事件只准入一个候选；失效候选返回成功但无业务效果，不消耗公平窗口。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class SupervisorAdmitQueueAction implements LocalAction {

    private final ProcessEventMapper eventMapper;

    private final SupervisorRuntimeService supervisorRuntimeService;

    /**
     * 构造动作。
     *
     * @param eventMapper 事件 Mapper
     * @param supervisorRuntimeService 监督运行服务
     */
    public SupervisorAdmitQueueAction(ProcessEventMapper eventMapper,
                                      SupervisorRuntimeService supervisorRuntimeService) {
        this.eventMapper = eventMapper;
        this.supervisorRuntimeService = supervisorRuntimeService;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LocalActionDescriptor describe() {
        return new LocalActionDescriptor(SupervisorRules.ACTION_ADMIT, "1", "监督逐候选准入",
            "SAME_TX", true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ActionResult execute(ActionContext context) {
        ProcessEvent event = eventMapper.selectById(context.eventId());
        if (event == null) {
            return new ActionResult(EffectResult.FAILED, null, null, null, null, null, null, "监督事件不存在");
        }
        long childId;
        try {
            childId = childId(event.getEventKey());
        } catch (RuntimeException ex) {
            return new ActionResult(EffectResult.FAILED, "INVALID_EVENT_KEY", null, null, null, null, null,
                ex.getMessage());
        }
        SupervisorSlot slot = supervisorRuntimeService.admitFromEvent(context.instanceId(), childId,
            event.getRoundNo(), event.getId(), context.attemptId());
        String checkpoint = "{\"childInstanceId\":" + childId + ",\"code\":\"" + slot.code()
            + "\",\"heldBefore\":" + slot.heldBefore() + ",\"heldAfter\":" + slot.heldAfter() + "}";
        if (slot.granted() || expectedNoEffect(slot.code())) {
            return new ActionResult(EffectResult.SUCCEEDED, "PLAN_SLOT:" + childId + ":" + slot.code(),
                null, null, null, null, checkpoint,
                slot.granted() ? "准入成功" : "候选未准入: " + slot.code());
        }
        return new ActionResult(EffectResult.FAILED, "PLAN_SLOT:" + childId + ":" + slot.code(),
            null, null, null, null, checkpoint, "监督准入不一致: " + slot.code());
    }

    private static boolean expectedNoEffect(String code) {
        return "SLOT_FULL".equals(code) || "STALE_CANDIDATE".equals(code)
            || "SUPERVISOR_PAUSED".equals(code) || "MODE_MISMATCH".equals(code)
            || "SLOT_RELEASED".equals(code);
    }

    private static long childId(String eventKey) {
        if (eventKey == null) {
            throw new IllegalArgumentException("事件键为空");
        }
        int marker = eventKey.lastIndexOf(":C");
        if (marker < 0 || marker + 2 >= eventKey.length()) {
            throw new IllegalArgumentException("逐候选事件键缺少 childId: " + eventKey);
        }
        return Long.parseLong(eventKey.substring(marker + 2));
    }
}
