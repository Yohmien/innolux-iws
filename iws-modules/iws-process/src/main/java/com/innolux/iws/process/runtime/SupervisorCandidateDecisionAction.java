/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督候选/公平窗口决策，只持久化选中集合与原因；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.process.domain.BusinessSchedule;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.enums.ControlState;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventRole;
import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.RoundStatus;
import com.innolux.iws.process.mapper.BusinessScheduleMapper;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 只计算并持久化候选决策，不直接准入。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class SupervisorCandidateDecisionAction implements LocalAction {

    private final ProcessInstanceMapper instanceMapper;

    private final ProcessScheduleControlMapper controlMapper;

    private final PlanDefinitionMapper definitionMapper;

    private final BusinessScheduleMapper scheduleMapper;

    private final ProcessEventMapper eventMapper;

    private final SupervisorCandidateSelector selector;

    /**
     * 构造动作。
     *
     * @param instanceMapper 实例 Mapper
     * @param controlMapper 控制 Mapper
     * @param definitionMapper 定义 Mapper
     * @param scheduleMapper 业务调度 Mapper
     * @param eventMapper 事件 Mapper
     * @param selector 候选选择器
     */
    public SupervisorCandidateDecisionAction(ProcessInstanceMapper instanceMapper,
                                             ProcessScheduleControlMapper controlMapper,
                                             PlanDefinitionMapper definitionMapper,
                                             BusinessScheduleMapper scheduleMapper,
                                             ProcessEventMapper eventMapper,
                                             SupervisorCandidateSelector selector) {
        this.instanceMapper = instanceMapper;
        this.controlMapper = controlMapper;
        this.definitionMapper = definitionMapper;
        this.scheduleMapper = scheduleMapper;
        this.eventMapper = eventMapper;
        this.selector = selector;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LocalActionDescriptor describe() {
        return new LocalActionDescriptor(SupervisorRules.ACTION_DECISION, "1", "监督候选决策", "SAME_TX", true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ActionResult execute(ActionContext context) {
        ProcessEvent event = eventMapper.selectById(context.eventId());
        ProcessInstance supervisor = instanceMapper.selectById(context.instanceId());
        if (event == null || supervisor == null || supervisor.getPlanRole() != PlanRole.SUPERVISOR) {
            return new ActionResult(EffectResult.FAILED, null, null, null, null, null, null,
                "监督决策上下文无效");
        }
        if (event.getEventRole() != EventRole.DECISION) {
            return new ActionResult(EffectResult.FAILED, null, null, null, null, null, null,
                "决策动作只能绑定 DECISION 事件");
        }
        ProcessScheduleControl control = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisor.getId())
            .eq(ProcessScheduleControl::getDuty, SupervisorRules.DUTY_DISPATCH));
        PlanDefinition definition = definitionMapper.selectById(supervisor.getDefinitionVersionId());
        SupervisorRules.requireSupervisorDefinition(definition);
        int limit = SupervisorRules.activePlanLimit(definition);
        BusinessSchedule schedule = scheduleMapper.selectById(supervisor.getScheduleId());
        int window = definition.getMode() == PlanMode.QUEUE
            ? SupervisorRules.fairnessWindow(definition, schedule) : 1;

        if (control == null || control.getControlState() == ControlState.MANUAL_PAUSED) {
            return stalled(event, definition, control, limit, window, "SUPERVISOR_PAUSED");
        }
        if (control.getRoundStatus() != RoundStatus.OPEN
            || value(control.getRoundNo()) != event.getRoundNo()
            || control.getLeaseUntil() == null || !control.getLeaseUntil().isAfter(LocalDateTime.now())) {
            return stalled(event, definition, control, limit, window, "STALE_CANDIDATE");
        }

        int batchLimit = definition.getRoundBatchLimit() == null ? 0 : definition.getRoundBatchLimit();
        SupervisorCandidateSelector.CandidateSnapshot snapshot = selector.snapshotCandidates(
            supervisor, definition, control, batchLimit);
        List<Long> selected = snapshot.candidates().stream()
            .filter(SupervisorCandidateSelector.Candidate::selected)
            .map(SupervisorCandidateSelector.Candidate::instanceId).toList();
        List<SupervisorDecisionStore.Skip> skipped = snapshot.candidates().stream()
            .filter(item -> !item.selected())
            .map(item -> new SupervisorDecisionStore.Skip(item.instanceId(), "CAPACITY")).toList();
        SupervisorDecisionStore.Decision decision = new SupervisorDecisionStore.Decision(event.getRoundNo(),
            event.getEventCode(), snapshot.candidateCount(), snapshot.eligibleCount(), snapshot.held(),
            snapshot.limit(), snapshot.free(), snapshot.fairnessWindow(), snapshot.fairnessCount(),
            snapshot.fairnessTurn(), selected, skipped, snapshot.reason());
        String json = SupervisorDecisionStore.toJson(decision);
        return new ActionResult(EffectResult.SUCCEEDED, "DECISION:" + snapshot.reason(), null, null, null,
            null, json, "监督决策完成: " + snapshot.reason() + ", 候选=" + snapshot.candidateCount()
                + ", 选中=" + selected.size());
    }

    private ActionResult stalled(ProcessEvent event, PlanDefinition definition, ProcessScheduleControl control,
                                 int limit, int window, String reason) {
        int current = control == null || control.getFairnessCount() == null ? 0 : control.getFairnessCount();
        SupervisorDecisionStore.Decision decision = new SupervisorDecisionStore.Decision(event.getRoundNo(),
            event.getEventCode(), 0, 0, 0, limit, 0, window, current, false, List.of(), List.of(), reason);
        return new ActionResult(EffectResult.SUCCEEDED, "DECISION:" + reason, null, null, null, null,
            SupervisorDecisionStore.toJson(decision), "监督决策未执行: " + reason);
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }
}
