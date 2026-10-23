/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : QUERY 恢复命令与读模型共用的只读准入；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.enums.CallPurpose;
import com.innolux.iws.process.enums.CheckpointStatus;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.PermitState;
import com.innolux.iws.process.enums.ScheduleControlScope;
import com.innolux.iws.process.enums.SlotState;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.support.DefinitionGraph;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 只读判断 UNKNOWN 原事件是否具备显式 QUERY 恢复条件。
 *
 * <p>命令和查询读模型必须使用同一判断。本服务只读取已发布定义、QUERY 占位事件及调度控制行，
 * 不激活事件、不释放 CALL，也不改变原业务执行状态。</p>
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled",
    havingValue = "true")
public class QueryRecoveryAdmission {

    private static final String CALL_PERMIT_DUTY = "CALL_PERMIT";

    private final RuntimeDao runtimeDao;

    private final ProcessAttemptMapper attemptMapper;

    private final ProcessScheduleControlMapper controlMapper;

    /**
     * 构造 QUERY 恢复准入服务。
     *
     * @param runtimeDao 运行时只读 DAO
     * @param attemptMapper 尝试 Mapper
     * @param controlMapper 调度控制 Mapper
     */
    public QueryRecoveryAdmission(RuntimeDao runtimeDao, ProcessAttemptMapper attemptMapper,
                                  ProcessScheduleControlMapper controlMapper) {
        this.runtimeDao = runtimeDao;
        this.attemptMapper = attemptMapper;
        this.controlMapper = controlMapper;
    }

    /**
     * 按事件 ID 判断是否可进入显式 QUERY 恢复。
     *
     * @param eventId 原事件 ID
     * @return 是否满足全部只读准入条件
     */
    public boolean canRecover(long eventId) {
        ProcessEvent event = runtimeDao.event(eventId);
        ProcessAttempt attempt = event.getLastAttemptId() == null ? null
            : attemptMapper.selectById(event.getLastAttemptId());
        return canRecover(event, attempt);
    }

    /**
     * 判断已加载的事件与最近尝试是否可进入显式 QUERY 恢复。
     *
     * @param event 原事件
     * @param attempt 最近尝试，可为空
     * @return 是否满足全部只读准入条件
     */
    public boolean canRecover(ProcessEvent event, ProcessAttempt attempt) {
        return evaluate(event, attempt).allowed();
    }

    /**
     * 计算显式 QUERY 恢复准入。
     *
     * @param event 原事件
     * @param attempt 最近尝试，可为空
     * @return 准入结果；允许时包含 QUERY 占位事件和正数毫秒预算
     */
    public Admission evaluate(ProcessEvent event, ProcessAttempt attempt) {
        if (!isUnknown(event, attempt)) {
            return Admission.denied("事件结果不是 UNKNOWN，禁止 QUERY 恢复");
        }
        LocalDateTime now = LocalDateTime.now();
        if (event.getEventStatus() == EventStatus.PROCESSING
            || (event.getLeaseUntil() != null && event.getLeaseUntil().isAfter(now))) {
            return Admission.denied("原事件仍有活动租约，拒绝恢复");
        }
        DefinitionGraph graph = runtimeDao.graph(event.getDefinitionVersionId());
        List<ProcessEventDefinition> queries = graph.eventCodes().stream()
            .map(graph::event)
            .filter(item -> item != null && event.getEventCode().equals(item.getRecoveryForEventCode()))
            .filter(item -> item.getEventDirection() == EventDirection.OUTBOUND)
            .filter(item -> item.getPurpose() == CallPurpose.QUERY)
            .toList();
        if (queries.size() != 1) {
            return Admission.denied("缺少唯一已发布对账 QUERY 事件，拒绝恢复");
        }
        ProcessEvent query = runtimeDao.findEvent(event.getInstanceId(), queries.getFirst().getEventCode(),
            event.getRoundNo());
        if (query == null) {
            return Admission.denied("对账 QUERY 事件未物化，拒绝恢复");
        }
        if (query.getEventStatus() != EventStatus.NOT_SELECTED
            && query.getEventStatus() != EventStatus.WAIT_PREDECESSOR) {
            return Admission.denied("QUERY 事件不是未激活状态，拒绝恢复");
        }
        if (controlMapper.selectCount(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.PLAN_SLOT)
            .eq(ProcessScheduleControl::getScopeKey, String.valueOf(event.getInstanceId()))
            .eq(ProcessScheduleControl::getSlotState, SlotState.HELD)) != 1) {
            return Admission.denied("原实例未持有 PLAN_SLOT，拒绝恢复");
        }
        ProcessScheduleControl originalCall = controlMapper.selectOne(
            Wrappers.<ProcessScheduleControl>lambdaQuery()
                .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.CALL)
                .eq(ProcessScheduleControl::getScopeKey, String.valueOf(event.getId()))
                .eq(ProcessScheduleControl::getDuty, CALL_PERMIT_DUTY));
        if (originalCall == null || originalCall.getPermitState() == PermitState.RELEASED) {
            return Admission.denied("原 CALL 不存在或已释放，拒绝 QUERY 恢复");
        }
        PlanDefinition plan = runtimeDao.definition(event.getDefinitionVersionId());
        Long budget = plan.getMaxExecutionMs();
        if (budget == null || budget <= 0) {
            return Admission.denied("恢复合同缺少 maxExecutionMs，拒绝激活 QUERY");
        }
        return new Admission(true, query, budget, null);
    }

    private boolean isUnknown(ProcessEvent event, ProcessAttempt attempt) {
        if (event == null) {
            return false;
        }
        if (attempt != null && attempt.getEffectResult() == EffectResult.UNKNOWN) {
            return true;
        }
        return Boolean.TRUE.equals(event.getSent()) && (attempt == null
            || attempt.getCheckpointStatus() == CheckpointStatus.SEND_INTENT
            || attempt.getCheckpointStatus() == CheckpointStatus.UNKNOWN);
    }

    /**
     * 显式 QUERY 恢复只读准入结果。
     *
     * @param allowed 是否允许
     * @param queryEvent 已物化的 QUERY 占位事件，拒绝时为空
     * @param budgetMs 正数恢复预算毫秒，拒绝时为空
     * @param reason 拒绝原因，允许时为空
     */
    public record Admission(boolean allowed, ProcessEvent queryEvent, Long budgetMs, String reason) {

        private static Admission denied(String reason) {
            return new Admission(false, null, null, reason);
        }
    }
}
