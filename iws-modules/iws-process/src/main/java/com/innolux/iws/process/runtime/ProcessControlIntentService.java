/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 持久平台控制意图发现、worker 唤醒与通用结果 CAS；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.process.api.ControlIntent;
import com.innolux.iws.process.api.ControlIntentAck;
import com.innolux.iws.process.api.JobSpec;
import com.innolux.iws.process.api.PlatformScopeResultCommand;
import com.innolux.iws.process.api.ProcessControlIntentPort;
import com.innolux.iws.process.domain.BusinessSchedule;
import com.innolux.iws.process.domain.BusinessSchedulePlanType;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessActionAudit;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.enums.ActionSource;
import com.innolux.iws.process.enums.ActionType;
import com.innolux.iws.process.enums.AuditResult;
import com.innolux.iws.process.enums.ControlState;
import com.innolux.iws.process.enums.ScheduleControlScope;
import com.innolux.iws.process.mapper.BusinessScheduleMapper;
import com.innolux.iws.process.mapper.BusinessSchedulePlanTypeMapper;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.service.ProcessAuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 以数据库控制行为唯一事实源，向 job 暴露待收敛平台意图。
 *
 * <p>worker 与监督共用同一控制表；worker 行固定 scopeType=BUSINESS、
 * scopeKey=schedule:&lt;id&gt;、duty=WORKER。意图只靠行状态重放，不依赖内存通知。</p>
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class ProcessControlIntentService implements ProcessControlIntentPort {

    private static final String DUTY_WORKER = "WORKER";

    private static final String SCOPE_PREFIX = "schedule:";

    private static final String STATE_RUNNING = "RUNNING";

    private final ProcessScheduleControlMapper controlMapper;

    private final ProcessInstanceMapper instanceMapper;

    private final PlanDefinitionMapper definitionMapper;

    private final BusinessScheduleMapper scheduleMapper;

    private final BusinessSchedulePlanTypeMapper planTypeMapper;

    private final ProcessAuditService auditService;

    /**
     * 构造服务。
     *
     * @param controlMapper 控制 Mapper
     * @param instanceMapper 实例 Mapper
     * @param definitionMapper 定义 Mapper
     * @param scheduleMapper 业务调度 Mapper
     * @param planTypeMapper 计划类型 Mapper
     * @param auditService 审计服务
     */
    public ProcessControlIntentService(ProcessScheduleControlMapper controlMapper,
                                       ProcessInstanceMapper instanceMapper,
                                       PlanDefinitionMapper definitionMapper,
                                       BusinessScheduleMapper scheduleMapper,
                                       BusinessSchedulePlanTypeMapper planTypeMapper,
                                       ProcessAuditService auditService) {
        this.controlMapper = controlMapper;
        this.instanceMapper = instanceMapper;
        this.definitionMapper = definitionMapper;
        this.scheduleMapper = scheduleMapper;
        this.planTypeMapper = planTypeMapper;
        this.auditService = auditService;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ControlIntent> listPendingIntents(int limit) {
        int rowsLimit = Math.max(1, limit);
        QueryWrapper<ProcessScheduleControl> query = new QueryWrapper<>();
        query.in("duty", SupervisorRules.DUTY_DISPATCH, DUTY_WORKER)
            .and(wrapper -> wrapper.eq("wake_pending", true)
                .or().apply("(desired_state IS NOT NULL AND observed_state IS NOT NULL"
                    + " AND desired_state <> observed_state)"))
            .orderByAsc("last_activity_at")
            .orderByAsc("id")
            .last("LIMIT " + rowsLimit);
        List<ControlIntent> intents = new ArrayList<>();
        for (ProcessScheduleControl control : controlMapper.selectList(query)) {
            ControlIntent intent = toIntent(control);
            if (intent != null && intent.action() != null) {
                intents.add(intent);
            }
        }
        return intents;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ControlIntent find(String scopeType, String scopeKey, String duty) {
        ProcessScheduleControl control = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, parseScope(scopeType))
            .eq(ProcessScheduleControl::getScopeKey, scopeKey)
            .eq(ProcessScheduleControl::getDuty, duty));
        return control == null ? null : toIntent(control);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ControlIntentAck recordResult(PlatformScopeResultCommand command) {
        ProcessScheduleControl control = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, parseScope(command.scopeType()))
            .eq(ProcessScheduleControl::getScopeKey, command.scopeKey())
            .eq(ProcessScheduleControl::getDuty, command.duty()));
        if (control == null) {
            return new ControlIntentAck(false, false, 0L, null, false);
        }
        long revision = control.getRevision() == null ? 0L : control.getRevision();
        if (revision != command.expectedRevision()) {
            return new ControlIntentAck(false, true, revision, control.getObservedState(),
                Boolean.TRUE.equals(control.getWakePending()));
        }
        String expectedAction = action(control);
        if (expectedAction == null || !expectedAction.equals(command.action())) {
            return new ControlIntentAck(false, true, revision, control.getObservedState(),
                Boolean.TRUE.equals(control.getWakePending()));
        }
        boolean actionAck = isActionAck(command.observedState());
        String observed = command.applied() && !actionAck
            ? command.observedState() : control.getObservedState();
        boolean wakeAfter = !command.applied();
        int rows = controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
            .eq(ProcessScheduleControl::getId, control.getId())
            .eq(ProcessScheduleControl::getRevision, command.expectedRevision())
            .set(ProcessScheduleControl::getObservedState, observed)
            .set(ProcessScheduleControl::getErrorMessage, command.applied() ? null : command.message())
            .set(ProcessScheduleControl::getWakePending, wakeAfter)
            .set(ProcessScheduleControl::getRevision, revision + 1)
            .set(ProcessScheduleControl::getLastActivityAt, LocalDateTime.now()));
        if (rows != 1) {
            ProcessScheduleControl latest = controlMapper.selectById(control.getId());
            return new ControlIntentAck(false, true, latest.getRevision(), latest.getObservedState(),
                Boolean.TRUE.equals(latest.getWakePending()));
        }
        audit(control, command.applied() ? AuditResult.SUCCESS : AuditResult.FAILED,
            "PLATFORM_" + (command.applied() ? "APPLIED" : "FAILED")
                + " action=" + command.action()
                + " scope=" + command.scopeType() + ":" + command.scopeKey() + "/" + command.duty()
                + " observed=" + command.observedState()
                + " persistedObserved=" + observed,
            command.platformJobId(), revision + 1);
        return new ControlIntentAck(true, false, revision + 1, observed, wakeAfter);
    }

    /**
     * 幂等建立/唤醒一个业务调度的 QUEUE worker 控制行。
     *
     * @param scheduleId 业务调度 ID
     * @param definitionVersionId 提供 worker 参数的冻结定义
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void ensureWorkerIntent(long scheduleId, long definitionVersionId) {
        String scopeKey = SCOPE_PREFIX + scheduleId;
        ProcessScheduleControl control = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.BUSINESS)
            .eq(ProcessScheduleControl::getScopeKey, scopeKey)
            .eq(ProcessScheduleControl::getDuty, DUTY_WORKER));
        LocalDateTime now = LocalDateTime.now();
        if (control == null) {
            control = new ProcessScheduleControl();
            control.setScopeType(ScheduleControlScope.BUSINESS);
            control.setScopeKey(scopeKey);
            control.setDuty(DUTY_WORKER);
            control.setControlState(ControlState.AUTO_RUNNING);
            control.setDesiredState(STATE_RUNNING);
            control.setObservedState("ABSENT");
            control.setRevision(1L);
            control.setEpoch(0L);
            control.setWakePending(Boolean.TRUE);
            control.setDefinitionVersionId(definitionVersionId);
            control.setLastActivityAt(now);
            controlMapper.insert(control);
            audit(control, AuditResult.SUCCESS, "WORKER_INTENT_CREATED scheduleId=" + scheduleId, null, 1L);
            return;
        }
        boolean definitionChanged = control.getDefinitionVersionId() == null
            || control.getDefinitionVersionId() != definitionVersionId;
        if (definitionChanged || !Boolean.TRUE.equals(control.getWakePending())) {
            long revision = control.getRevision() == null ? 0L : control.getRevision();
            int rows = controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
                .eq(ProcessScheduleControl::getId, control.getId())
                .eq(ProcessScheduleControl::getRevision, revision)
                .set(ProcessScheduleControl::getDefinitionVersionId, definitionVersionId)
                .set(ProcessScheduleControl::getWakePending, Boolean.TRUE)
                .set(ProcessScheduleControl::getRevision, revision + 1)
                .set(ProcessScheduleControl::getLastActivityAt, now));
            if (rows == 1) {
                audit(control, AuditResult.SUCCESS, "WORKER_WAKE_REQUESTED scheduleId=" + scheduleId,
                    null, revision + 1);
            }
        }
    }

    /**
     * 持久唤醒一个已存在的 worker 控制行。
     *
     * @param scheduleId 业务调度 ID
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void wakeWorker(long scheduleId) {
        String scopeKey = SCOPE_PREFIX + scheduleId;
        ProcessScheduleControl control = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.BUSINESS)
            .eq(ProcessScheduleControl::getScopeKey, scopeKey)
            .eq(ProcessScheduleControl::getDuty, DUTY_WORKER));
        if (control == null || Boolean.TRUE.equals(control.getWakePending())) {
            return;
        }
        long revision = control.getRevision() == null ? 0L : control.getRevision();
        int rows = controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
            .eq(ProcessScheduleControl::getId, control.getId())
            .eq(ProcessScheduleControl::getRevision, revision)
            .set(ProcessScheduleControl::getWakePending, Boolean.TRUE)
            .set(ProcessScheduleControl::getRevision, revision + 1)
            .set(ProcessScheduleControl::getLastActivityAt, LocalDateTime.now()));
        if (rows == 1) {
            audit(control, AuditResult.SUCCESS, "WORKER_WAKE_REQUESTED scheduleId=" + scheduleId, null,
                revision + 1);
        }
    }

    private ControlIntent toIntent(ProcessScheduleControl control) {
        String scopeType = control.getScopeType() == null ? null : control.getScopeType().name();
        String supervisorId = control.getSupervisorInstanceId() == null ? null
            : String.valueOf(control.getSupervisorInstanceId());
        String action = action(control);
        JobSpec spec = null;
        String error = control.getErrorMessage();
        try {
            if (control.getDuty() != null && SupervisorRules.DUTY_DISPATCH.equals(control.getDuty())) {
                spec = supervisorSpec(control);
            } else if (DUTY_WORKER.equals(control.getDuty())) {
                spec = workerSpec(control);
            }
        } catch (RuntimeException ex) {
            error = ex.getMessage();
        }
        return new ControlIntent(scopeType, control.getScopeKey(), control.getDuty(), supervisorId, action,
            control.getDesiredState(), control.getObservedState(),
            control.getRevision() == null ? 0L : control.getRevision(),
            control.getEpoch() == null ? 0L : control.getEpoch(),
            Boolean.TRUE.equals(control.getWakePending()), spec, error, control.getLastActivityAt());
    }

    private JobSpec supervisorSpec(ProcessScheduleControl control) {
        ProcessInstance supervisor = instanceMapper.selectById(control.getSupervisorInstanceId());
        if (supervisor == null) {
            throw new IllegalStateException("监督实例不存在: " + control.getSupervisorInstanceId());
        }
        PlanDefinition definition = definitionMapper.selectById(
            control.getDefinitionVersionId() == null ? supervisor.getDefinitionVersionId()
                : control.getDefinitionVersionId());
        BusinessSchedule schedule = scheduleMapper.selectById(supervisor.getScheduleId());
        BusinessSchedulePlanType planType = planTypeMapper.selectOne(
            Wrappers.<BusinessSchedulePlanType>lambdaQuery()
                .eq(BusinessSchedulePlanType::getScheduleId, supervisor.getScheduleId())
                .eq(BusinessSchedulePlanType::getPlanType, supervisor.getPlanType()));
        if (definition == null || schedule == null) {
            throw new IllegalStateException("监督任务规格缺少定义或业务调度");
        }
        return SupervisorJobSpecFactory.supervisor(supervisor, definition, schedule, planType);
    }

    private JobSpec workerSpec(ProcessScheduleControl control) {
        long scheduleId = scheduleId(control.getScopeKey());
        BusinessSchedule schedule = scheduleMapper.selectById(scheduleId);
        PlanDefinition definition = definitionMapper.selectById(control.getDefinitionVersionId());
        if (schedule == null || definition == null) {
            throw new IllegalStateException("worker 任务规格缺少业务调度或定义");
        }
        return SupervisorJobSpecFactory.worker(schedule, definition);
    }

    private static long scheduleId(String scopeKey) {
        if (scopeKey == null || !scopeKey.startsWith(SCOPE_PREFIX)) {
            throw new IllegalArgumentException("worker scopeKey 非法: " + scopeKey);
        }
        return Long.parseLong(scopeKey.substring(SCOPE_PREFIX.length()));
    }

    private static String action(ProcessScheduleControl control) {
        String desired = control.getDesiredState();
        if ("STOPPED".equals(desired)) {
            return "ENSURE_STOPPED";
        }
        if (!STATE_RUNNING.equals(desired)) {
            return null;
        }
        if (Boolean.TRUE.equals(control.getWakePending())) {
            return "TRIGGER_ONCE";
        }
        return STATE_RUNNING.equals(control.getObservedState()) ? null : "ENSURE_RUNNING";
    }

    private static boolean isActionAck(String observedState) {
        return observedState == null || observedState.isBlank()
            || "TRIGGERED".equalsIgnoreCase(observedState);
    }

    private static ScheduleControlScope parseScope(String scopeType) {
        if (scopeType == null || scopeType.isBlank()) {
            throw new IllegalArgumentException("scopeType 不能为空");
        }
        return ScheduleControlScope.valueOf(scopeType);
    }

    private void audit(ProcessScheduleControl control, AuditResult result, String reason,
                       String platformJobId, long controlRevision) {
        ProcessActionAudit audit = auditService.newAudit(ActionType.SCHEDULE_CONTROL, ActionSource.SYSTEM, result);
        audit.setSupervisorInstanceId(control.getSupervisorInstanceId());
        audit.setChildInstanceId(control.getChildInstanceId());
        audit.setPlatformJobId(platformJobId);
        audit.setControlRevision(controlRevision);
        audit.setEpoch(control.getEpoch());
        audit.setReason(reason);
        auditService.append(audit);
    }
}
