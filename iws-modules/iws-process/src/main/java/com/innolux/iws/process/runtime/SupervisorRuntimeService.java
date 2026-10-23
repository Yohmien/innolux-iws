/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 唯一监督绑定、有限轮次围栏、计划名额准入与平台控制；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.api.ControlIntentSnapshot;
import com.innolux.iws.process.api.JobSpec;
import com.innolux.iws.process.api.PlatformResultAck;
import com.innolux.iws.process.api.PlatformResultCommand;
import com.innolux.iws.process.api.ProcessControlIntentPort;
import com.innolux.iws.process.api.SupervisorRoundLease;
import com.innolux.iws.process.api.SupervisorSlot;
import com.innolux.iws.process.domain.BusinessSchedule;
import com.innolux.iws.process.domain.BusinessSchedulePlanType;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessActionAudit;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.enums.ActionSource;
import com.innolux.iws.process.enums.ActionType;
import com.innolux.iws.process.enums.AuditResult;
import com.innolux.iws.process.enums.ControlState;
import com.innolux.iws.process.enums.DefinitionStatus;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.PlanStatus;
import com.innolux.iws.process.enums.RoundStatus;
import com.innolux.iws.process.enums.ScheduleControlScope;
import com.innolux.iws.process.enums.SlotState;
import com.innolux.iws.process.mapper.BusinessScheduleMapper;
import com.innolux.iws.process.mapper.BusinessSchedulePlanTypeMapper;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.service.ProcessAuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 监督身份、有限轮次、计划名额与平台控制持久事实。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class SupervisorRuntimeService {

    private final PlanDefinitionMapper definitionMapper;

    private final ProcessInstanceMapper instanceMapper;

    private final ProcessScheduleControlMapper controlMapper;

    private final BusinessScheduleMapper scheduleMapper;

    private final BusinessSchedulePlanTypeMapper planTypeMapper;

    private final ProcessEventMapper eventMapper;

    private final ProcessAuditService auditService;

    private final ProcessControlIntentPort controlIntentService;

    /**
     * 构造服务。
     *
     * @param definitionMapper 定义 Mapper
     * @param instanceMapper 实例 Mapper
     * @param controlMapper 控制 Mapper
     * @param scheduleMapper 业务调度 Mapper
     * @param planTypeMapper 计划类型 Mapper
     * @param eventMapper 事件 Mapper
     * @param auditService 审计服务
     * @param controlIntentService 平台意图服务
     */
    public SupervisorRuntimeService(PlanDefinitionMapper definitionMapper, ProcessInstanceMapper instanceMapper,
                                    ProcessScheduleControlMapper controlMapper, BusinessScheduleMapper scheduleMapper,
                                    BusinessSchedulePlanTypeMapper planTypeMapper, ProcessEventMapper eventMapper,
                                    ProcessAuditService auditService,
                                    ProcessControlIntentPort controlIntentService) {
        this.definitionMapper = definitionMapper;
        this.instanceMapper = instanceMapper;
        this.controlMapper = controlMapper;
        this.scheduleMapper = scheduleMapper;
        this.planTypeMapper = planTypeMapper;
        this.eventMapper = eventMapper;
        this.auditService = auditService;
        this.controlIntentService = controlIntentService;
    }

    /**
     * 取得或创建唯一监督实例。
     *
     * @param scheduleId 业务调度 ID
     * @param planType 计划类型
     * @param scope 调度范围
     * @return 监督实例
     */
    @Transactional(rollbackFor = Exception.class)
    public ProcessInstance ensureSupervisor(long scheduleId, String planType, String scope) {
        String supervisorKey = String.join(":", "SUPERVISOR", String.valueOf(scheduleId), planType, scope);
        ProcessInstance existing = instanceMapper.selectOne(Wrappers.<ProcessInstance>lambdaQuery()
            .eq(ProcessInstance::getSupervisorKey, supervisorKey));
        if (existing != null) {
            BusinessSchedule schedule = scheduleMapper.selectById(scheduleId);
            if (schedule == null) {
                throw new ServiceException("业务调度不存在: " + scheduleId);
            }
            controlIntentService.ensureWorkerIntent(scheduleId, existing.getDefinitionVersionId());
            return existing;
        }
        BusinessSchedule schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null) {
            throw new ServiceException("业务调度不存在: " + scheduleId);
        }
        var definitions = definitionMapper.selectList(Wrappers.<PlanDefinition>lambdaQuery()
            .eq(PlanDefinition::getBusinessScheduleCode, schedule.getBusinessCode())
            .eq(PlanDefinition::getPlanType, planType)
            .eq(PlanDefinition::getPlanRole, PlanRole.SUPERVISOR)
            .eq(PlanDefinition::getStatus, DefinitionStatus.ACTIVE)
            .isNotNull(PlanDefinition::getMode));
        if (definitions.size() != 1) {
            throw new ServiceException("已发布监督计划必须唯一，当前=" + definitions.size() + ": " + planType);
        }
        PlanDefinition definition = definitions.getFirst();
        ProcessInstance supervisor = new ProcessInstance();
        supervisor.setPlanRole(PlanRole.SUPERVISOR);
        supervisor.setDefinitionVersionId(definition.getId());
        supervisor.setDefinitionRevision(definition.getRevision());
        supervisor.setScheduleId(scheduleId);
        supervisor.setPlanType(planType);
        supervisor.setBusinessScheduleCode(schedule.getBusinessCode());
        supervisor.setScheduleScope(scope);
        supervisor.setSupervisorKey(supervisorKey);
        supervisor.setModeSnapshot(definition.getMode());
        supervisor.setHoldStart(Boolean.FALSE);
        supervisor.setPlanStatus(PlanStatus.RUNNING);
        supervisor.setInstancePriority(0);
        supervisor.setRoundNo(0L);
        supervisor.setStopRequested(Boolean.FALSE);
        supervisor.setCancelRequested(Boolean.FALSE);
        supervisor.setInstanceVersion(1L);
        instanceMapper.insert(supervisor);

        ProcessScheduleControl control = new ProcessScheduleControl();
        control.setScopeType(ScheduleControlScope.SUPERVISOR);
        control.setScopeKey(supervisorKey);
        control.setDuty(SupervisorRules.DUTY_DISPATCH);
        control.setControlState(ControlState.AUTO_RUNNING);
        control.setDesiredState("RUNNING");
        control.setObservedState("ABSENT");
        control.setRevision(1L);
        control.setEpoch(0L);
        control.setWakePending(Boolean.TRUE);
        control.setRoundNo(0L);
        control.setSupervisorInstanceId(supervisor.getId());
        control.setDefinitionVersionId(definition.getId());
        control.setFairnessCount(0);
        control.setLastActivityAt(LocalDateTime.now());
        controlMapper.insert(control);
        controlIntentService.ensureWorkerIntent(scheduleId, definition.getId());
        audit(supervisor.getId(), 0L, 0L, 1L, "SUPERVISOR_CREATED");
        return supervisor;
    }

    /**
     * 旧挂载直通准入入口。
     *
     * <p>计划名额只能由监督有限轮次的独立 LOCAL 事件取得；这里保留签名供既有挂载调用，
     * 始终返回 false，让子计划以 CREATED 等待下一轮监督准入。</p>
     *
     * @param child 子计划
     * @param supervisor 监督实例
     * @param supervisorDefinition 监督冻结定义
     * @return 固定 false
     */
    public boolean admit(ProcessInstance child, ProcessInstance supervisor, PlanDefinition supervisorDefinition) {
        return false;
    }

    /**
     * 开启或续租一个有限轮次。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @param commandKey 命令键
     * @param maxDurationMs 租约毫秒
     * @return 租约；暂停或他人持有返回 null
     */
    @Transactional(rollbackFor = Exception.class)
    public SupervisorRoundLease beginRound(long supervisorInstanceId, String commandKey, long maxDurationMs) {
        ProcessInstance supervisor = requireSupervisor(supervisorInstanceId);
        ProcessScheduleControl control = lockControl(supervisorInstanceId);
        if (control == null) {
            throw new ServiceException("监督控制行不存在: " + supervisorInstanceId);
        }
        if (supervisor.getPlanStatus() == PlanStatus.CANCELLED
            || control.getControlState() == ControlState.MANUAL_PAUSED) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean open = control.getRoundStatus() == RoundStatus.OPEN;
        if (open && control.getLeaseUntil() != null && control.getLeaseUntil().isAfter(now)) {
            return null;
        }
        long roundNo = open ? value(control.getRoundNo()) : value(control.getRoundNo()) + 1;
        long epoch = value(control.getEpoch()) + 1;
        String owner = "sup:" + supervisorInstanceId + ":r" + roundNo + ":e" + epoch + ":" + UUID.randomUUID();
        long leaseMs = Math.max(1L, maxDurationMs);
        LocalDateTime leaseUntil = now.plusNanos(leaseMs * 1_000_000L);
        long revision = value(control.getRevision()) + 1;
        int rows = controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
            .eq(ProcessScheduleControl::getId, control.getId())
            .eq(ProcessScheduleControl::getRevision, control.getRevision())
            .set(ProcessScheduleControl::getRoundNo, roundNo)
            .set(ProcessScheduleControl::getRoundStatus, RoundStatus.OPEN)
            .set(ProcessScheduleControl::getEpoch, epoch)
            .set(ProcessScheduleControl::getOwner, owner)
            .set(ProcessScheduleControl::getLeaseUntil, leaseUntil)
            .set(ProcessScheduleControl::getControlState, ControlState.AUTO_RUNNING)
            .set(ProcessScheduleControl::getDesiredState, "RUNNING")
            .set(ProcessScheduleControl::getRevision, revision)
            .set(ProcessScheduleControl::getErrorMessage, null)
            .set(ProcessScheduleControl::getLastActivityAt, now));
        if (rows != 1) {
            return null;
        }
        restoreRunning(supervisorInstanceId, roundNo, true);
        audit(supervisorInstanceId, roundNo, epoch, revision,
            "ROUND_OPEN commandKey=" + (commandKey == null ? "" : commandKey));
        return new SupervisorRoundLease(supervisorInstanceId, roundNo, epoch, owner, revision, leaseUntil);
    }

    /**
     * 让出轮次但保持 OPEN，下一 tick 可立即续原轮。
     *
     * @param lease 租约
     * @return 是否让出
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean yieldRound(SupervisorRoundLease lease) {
        ProcessScheduleControl control = lockControl(lease.supervisorInstanceId());
        if (!matches(control, lease) || control.getRoundStatus() != RoundStatus.OPEN) {
            return false;
        }
        long revision = value(control.getRevision()) + 1;
        int rows = controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
            .eq(ProcessScheduleControl::getId, control.getId())
            .eq(ProcessScheduleControl::getRevision, control.getRevision())
            .eq(ProcessScheduleControl::getEpoch, lease.epoch())
            .eq(ProcessScheduleControl::getOwner, lease.owner())
            .eq(ProcessScheduleControl::getRoundStatus, RoundStatus.OPEN)
            .set(ProcessScheduleControl::getOwner, null)
            .set(ProcessScheduleControl::getLeaseUntil, null)
            .set(ProcessScheduleControl::getEpoch, lease.epoch() + 1)
            .set(ProcessScheduleControl::getControlState, ControlState.AUTO_RUNNING)
            .set(ProcessScheduleControl::getRevision, revision)
            .set(ProcessScheduleControl::getLastActivityAt, LocalDateTime.now()));
        if (rows == 1) {
            audit(lease.supervisorInstanceId(), lease.roundNo(), lease.epoch(), revision, "ROUND_YIELD");
        }
        return rows == 1;
    }

    /**
     * 按当前 epoch/owner 收口轮次。
     *
     * @param lease 租约
     * @param status 目标状态
     * @param reason 原因
     * @return 是否由本代次关闭
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean closeRound(SupervisorRoundLease lease, RoundStatus status, String reason) {
        ProcessScheduleControl control = lockControl(lease.supervisorInstanceId());
        if (!matches(control, lease) || control.getRoundStatus() != RoundStatus.OPEN) {
            return false;
        }
        long revision = value(control.getRevision()) + 1;
        ControlState controlState = control.getControlState() == ControlState.MANUAL_PAUSED
            ? ControlState.MANUAL_PAUSED : ControlState.AUTO_IDLE;
        int rows = controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
            .eq(ProcessScheduleControl::getId, control.getId())
            .eq(ProcessScheduleControl::getRevision, control.getRevision())
            .eq(ProcessScheduleControl::getEpoch, lease.epoch())
            .eq(ProcessScheduleControl::getOwner, lease.owner())
            .eq(ProcessScheduleControl::getRoundStatus, RoundStatus.OPEN)
            .set(ProcessScheduleControl::getRoundStatus, status)
            .set(ProcessScheduleControl::getOwner, null)
            .set(ProcessScheduleControl::getLeaseUntil, null)
            .set(ProcessScheduleControl::getControlState, controlState)
            .set(ProcessScheduleControl::getRevision, revision)
            .set(ProcessScheduleControl::getErrorMessage, status == RoundStatus.FAILED ? reason : null)
            .set(ProcessScheduleControl::getLastActivityAt, LocalDateTime.now()));
        if (rows != 1) {
            return false;
        }
        restoreRunning(lease.supervisorInstanceId(), lease.roundNo(), false);
        audit(lease.supervisorInstanceId(), lease.roundNo(), lease.epoch(), revision,
            "ROUND_" + status + (reason == null ? "" : " reason=" + reason));
        return true;
    }

    /**
     * 只读校验收敛租约仍然有效。
     *
     * @param lease 租约
     * @return 是否有效
     */
    public boolean verifyLease(SupervisorRoundLease lease) {
        ProcessScheduleControl control = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getSupervisorInstanceId, lease.supervisorInstanceId())
            .eq(ProcessScheduleControl::getDuty, SupervisorRules.DUTY_DISPATCH));
        return matches(control, lease) && control.getRoundStatus() == RoundStatus.OPEN
            && control.getControlState() != ControlState.MANUAL_PAUSED
            && control.getLeaseUntil() != null && control.getLeaseUntil().isAfter(LocalDateTime.now());
    }

    /**
     * 由监督 LOCAL 事件准入一个子计划。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @param childInstanceId 子计划 ID
     * @param roundNo 轮次号
     * @param eventId 事件 ID
     * @param attemptId 尝试 ID
     * @return 准入结果
     */
    @Transactional(rollbackFor = Exception.class)
    public SupervisorSlot admitFromEvent(long supervisorInstanceId, long childInstanceId, long roundNo,
                                         Long eventId, Long attemptId) {
        ProcessInstance supervisor = requireSupervisor(supervisorInstanceId);
        ProcessScheduleControl control = lockControl(supervisorInstanceId);
        return admitLocked(supervisor, control, childInstanceId, roundNo, null, true, eventId, attemptId);
    }

    /**
     * 通用监督准入端口，供 IMMEDIATE 在独占轮次内调用。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @param lease 轮次租约
     * @param childInstanceId 子计划 ID
     * @return 准入结果
     */
    @Transactional(rollbackFor = Exception.class)
    public SupervisorSlot admitWithLease(long supervisorInstanceId, SupervisorRoundLease lease,
                                         long childInstanceId) {
        ProcessInstance supervisor = requireSupervisor(supervisorInstanceId);
        ProcessScheduleControl control = lockControl(supervisorInstanceId);
        return admitLocked(supervisor, control, childInstanceId, lease.roundNo(), lease, false, null, null);
    }

    /**
     * 安全释放一个 PLAN_SLOT；仅子计划已安全终结且无 UNKNOWN 时允许。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @param childInstanceId 子计划 ID
     * @param reason 原因
     * @return 是否已释放或原本已释放
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean releaseSlot(long supervisorInstanceId, long childInstanceId, String reason) {
        ProcessScheduleControl slot = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.PLAN_SLOT)
            .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisorInstanceId)
            .eq(ProcessScheduleControl::getChildInstanceId, childInstanceId)
            .eq(ProcessScheduleControl::getDuty, SupervisorRules.DUTY_SLOT));
        if (slot == null) {
            return false;
        }
        if (slot.getSlotState() == SlotState.RELEASED) {
            return true;
        }
        ProcessInstance child = instanceMapper.selectById(childInstanceId);
        if (child == null || child.getPlanStatus() == PlanStatus.RUNNING
            || child.getPlanStatus() == PlanStatus.CREATED || child.getPlanStatus() == PlanStatus.PAUSED
            || child.getPlanStatus() == PlanStatus.MANUAL_PENDING || child.getPlanStatus() == PlanStatus.EXCEPTION_HOLD) {
            return false;
        }
        long unknown = eventMapper.selectCount(Wrappers.<ProcessEvent>lambdaQuery()
            .eq(ProcessEvent::getInstanceId, childInstanceId)
            .eq(ProcessEvent::getEffectResult, EffectResult.UNKNOWN));
        if (unknown > 0) {
            return false;
        }
        long revision = value(slot.getRevision()) + 1;
        int rows = controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
            .eq(ProcessScheduleControl::getId, slot.getId())
            .eq(ProcessScheduleControl::getRevision, slot.getRevision())
            .eq(ProcessScheduleControl::getSlotState, SlotState.HELD)
            .set(ProcessScheduleControl::getSlotState, SlotState.RELEASED)
            .set(ProcessScheduleControl::getRevision, revision)
            .set(ProcessScheduleControl::getLastActivityAt, LocalDateTime.now()));
        if (rows == 1) {
            audit(supervisorInstanceId, 0L, value(slot.getEpoch()), revision,
                "PLAN_SLOT_RELEASED child=" + childInstanceId + " reason=" + reason);
        }
        return rows == 1;
    }

    /**
     * 构建控制平台快照。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @return 控制快照
     */
    public ControlIntentSnapshot controlSnapshot(long supervisorInstanceId) {
        ProcessInstance supervisor = requireSupervisor(supervisorInstanceId);
        ProcessScheduleControl control = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisorInstanceId)
            .eq(ProcessScheduleControl::getDuty, SupervisorRules.DUTY_DISPATCH));
        if (control == null) {
            throw new ServiceException("监督控制行不存在: " + supervisorInstanceId);
        }
        PlanDefinition definition = definitionMapper.selectById(supervisor.getDefinitionVersionId());
        if (definition == null) {
            throw new ServiceException("监督冻结定义不存在: " + supervisor.getDefinitionVersionId());
        }
        BusinessSchedule schedule = scheduleMapper.selectById(supervisor.getScheduleId());
        BusinessSchedulePlanType planType = planTypeMapper.selectOne(
            Wrappers.<BusinessSchedulePlanType>lambdaQuery()
                .eq(BusinessSchedulePlanType::getScheduleId, supervisor.getScheduleId())
                .eq(BusinessSchedulePlanType::getPlanType, supervisor.getPlanType()));
        JobSpec spec = SupervisorJobSpecFactory.supervisor(supervisor, definition, schedule, planType);
        boolean wake = Boolean.TRUE.equals(control.getWakePending())
            || !Objects.equals(control.getDesiredState(), control.getObservedState());
        return new ControlIntentSnapshot(supervisorInstanceId, control.getDesiredState(),
            control.getObservedState(), value(control.getRevision()), wake, null, spec,
            control.getErrorMessage());
    }

    /**
     * CAS 回写监督平台结果；不清除更新修订产生的新 wake。
     *
     * @param command 结果命令
     * @return 回写确认
     */
    @Transactional(rollbackFor = Exception.class)
    public PlatformResultAck recordPlatformResult(PlatformResultCommand command) {
        ProcessScheduleControl control = lockControl(command.supervisorInstanceId());
        if (control == null) {
            throw new ServiceException("监督控制行不存在: " + command.supervisorInstanceId());
        }
        long revision = value(control.getRevision());
        if (revision != command.expectedControlRevision()) {
            return new PlatformResultAck(false, true, revision, control.getObservedState());
        }
        String observed = command.applied() && !isActionAck(command.observedState())
            ? command.observedState() : control.getObservedState();
        boolean wakeAfter = !command.applied();
        int rows = controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
            .eq(ProcessScheduleControl::getId, control.getId())
            .eq(ProcessScheduleControl::getRevision, command.expectedControlRevision())
            .set(ProcessScheduleControl::getObservedState, observed)
            .set(ProcessScheduleControl::getErrorMessage, command.applied() ? null : command.message())
            .set(ProcessScheduleControl::getWakePending, wakeAfter)
            .set(ProcessScheduleControl::getRevision, revision + 1)
            .set(ProcessScheduleControl::getLastActivityAt, LocalDateTime.now()));
        if (rows != 1) {
            ProcessScheduleControl latest = controlMapper.selectById(control.getId());
            return new PlatformResultAck(false, true, value(latest.getRevision()), latest.getObservedState());
        }
        ProcessActionAudit audit = auditService.newAudit(ActionType.SCHEDULE_CONTROL,
            ActionSource.SYSTEM, command.applied() ? AuditResult.SUCCESS : AuditResult.FAILED);
        audit.setSupervisorInstanceId(command.supervisorInstanceId());
        audit.setPlatformJobId(command.platformJobId());
        audit.setControlRevision(revision + 1);
        audit.setReason("PLATFORM_" + (command.applied() ? "APPLIED" : "FAILED")
            + " observed=" + command.observedState() + " persistedObserved=" + observed);
        auditService.append(audit);
        return new PlatformResultAck(true, false, revision + 1, observed);
    }

    /**
     * 恢复监督运行态，不覆盖人工暂停、取消或未知停留。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @param roundNo 最近轮次
     * @param clearStop 是否清除停止请求
     */
    public void restoreRunning(long supervisorInstanceId, long roundNo, boolean clearStop) {
        LocalDateTime now = LocalDateTime.now();
        var restore = Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, supervisorInstanceId)
            .eq(ProcessInstance::getPlanRole, PlanRole.SUPERVISOR)
            .in(ProcessInstance::getPlanStatus, PlanStatus.COMPLETED, PlanStatus.FAILED_FINAL,
                PlanStatus.MANUAL_PENDING)
            .set(ProcessInstance::getPlanStatus, PlanStatus.RUNNING)
            .set(ProcessInstance::getEndedAt, null)
            .setSql("instance_version = instance_version + 1")
            .set(ProcessInstance::getUpdateTime, now);
        if (clearStop) {
            restore.set(ProcessInstance::getStopRequested, Boolean.FALSE);
        }
        instanceMapper.update(null, restore);
        if (clearStop) {
            instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
                .eq(ProcessInstance::getId, supervisorInstanceId)
                .eq(ProcessInstance::getStopRequested, Boolean.TRUE)
                .set(ProcessInstance::getStopRequested, Boolean.FALSE)
                .set(ProcessInstance::getUpdateTime, now));
        }
        instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, supervisorInstanceId)
            .eq(ProcessInstance::getPlanRole, PlanRole.SUPERVISOR)
            .ne(ProcessInstance::getRoundNo, roundNo)
            .set(ProcessInstance::getRoundNo, roundNo)
            .set(ProcessInstance::getUpdateTime, now));
    }

    private SupervisorSlot admitLocked(ProcessInstance supervisor, ProcessScheduleControl control,
                                       long childInstanceId, long roundNo, SupervisorRoundLease lease,
                                       boolean requireQueue, Long eventId, Long attemptId) {
        if (control == null) {
            return new SupervisorSlot(false, "CONTROL_MISSING", 0, 0, 0);
        }
        if (control.getControlState() == ControlState.MANUAL_PAUSED) {
            return new SupervisorSlot(false, "SUPERVISOR_PAUSED", 0, 0, 0);
        }
        LocalDateTime now = LocalDateTime.now();
        if (control.getRoundStatus() != RoundStatus.OPEN || value(control.getRoundNo()) != roundNo
            || control.getLeaseUntil() == null || !control.getLeaseUntil().isAfter(now)) {
            return new SupervisorSlot(false, "STALE_CANDIDATE", 0, 0, 0);
        }
        if (lease != null && (!Objects.equals(control.getEpoch(), lease.epoch())
            || !Objects.equals(control.getOwner(), lease.owner()))) {
            return new SupervisorSlot(false, "STALE_CANDIDATE", 0, 0, 0);
        }
        PlanDefinition definition = definitionMapper.selectById(
            control.getDefinitionVersionId() == null ? supervisor.getDefinitionVersionId()
                : control.getDefinitionVersionId());
        SupervisorRules.requireSupervisorDefinition(definition);
        if (requireQueue && definition.getMode() != PlanMode.QUEUE) {
            return new SupervisorSlot(false, "MODE_MISMATCH", 0, 0, 0);
        }
        ProcessInstance child = instanceMapper.selectById(childInstanceId);
        if (child == null || !Objects.equals(child.getSupervisorInstanceId(), supervisor.getId())
            || child.getPlanRole() != PlanRole.BUSINESS) {
            return new SupervisorSlot(false, "INVALID_CHILD", 0, 0, 0);
        }
        if (child.getModeSnapshot() != null && child.getModeSnapshot() != definition.getMode()) {
            return new SupervisorSlot(false, "MODE_MISMATCH", 0, 0, 0);
        }
        ProcessScheduleControl existing = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.PLAN_SLOT)
            .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisor.getId())
            .eq(ProcessScheduleControl::getChildInstanceId, childInstanceId)
            .eq(ProcessScheduleControl::getDuty, SupervisorRules.DUTY_SLOT));
        if (existing != null) {
            if (existing.getSlotState() == SlotState.HELD) {
                return new SupervisorSlot(true, "ALREADY_ADMITTED", 0, 0,
                    value(child.getInstanceVersion()));
            }
            return new SupervisorSlot(false, "SLOT_RELEASED", 0, 0, 0);
        }
        if (child.getPlanStatus() != PlanStatus.CREATED || Boolean.TRUE.equals(child.getHoldStart())) {
            return new SupervisorSlot(false, "STALE_CANDIDATE", 0, 0, 0);
        }
        List<ProcessScheduleControl> heldSlots = controlMapper.selectList(
            Wrappers.<ProcessScheduleControl>lambdaQuery()
                .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.PLAN_SLOT)
                .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisor.getId())
                .eq(ProcessScheduleControl::getDuty, SupervisorRules.DUTY_SLOT)
                .eq(ProcessScheduleControl::getSlotState, SlotState.HELD)
                .last("FOR UPDATE"));
        int held = heldSlots.size();
        int limit = SupervisorRules.activePlanLimit(definition);
        if (held >= limit) {
            return new SupervisorSlot(false, "SLOT_FULL", held, held, 0);
        }
        ProcessScheduleControl slot = new ProcessScheduleControl();
        slot.setScopeType(ScheduleControlScope.PLAN_SLOT);
        slot.setScopeKey(String.valueOf(childInstanceId));
        slot.setDuty(SupervisorRules.DUTY_SLOT);
        slot.setControlState(ControlState.AUTO_RUNNING);
        slot.setRevision(1L);
        slot.setEpoch(control.getEpoch());
        slot.setOwner(control.getOwner());
        slot.setSlotState(SlotState.HELD);
        slot.setWakePending(Boolean.FALSE);
        slot.setFairnessCount(0);
        slot.setSupervisorInstanceId(supervisor.getId());
        slot.setChildInstanceId(childInstanceId);
        slot.setDefinitionVersionId(definition.getId());
        slot.setLastAdmittedAt(now);
        slot.setLastActivityAt(now);
        controlMapper.insert(slot);

        long childVersion = value(child.getInstanceVersion());
        int childRows = instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, childInstanceId)
            .eq(ProcessInstance::getInstanceVersion, child.getInstanceVersion())
            .eq(ProcessInstance::getPlanStatus, PlanStatus.CREATED)
            .set(ProcessInstance::getPlanStatus, PlanStatus.RUNNING)
            .set(ProcessInstance::getFirstAdmittedAt, now)
            .set(ProcessInstance::getInstanceVersion, childVersion + 1)
            .set(ProcessInstance::getUpdateTime, now));
        if (childRows != 1) {
            throw new ServiceException("计划准入竞争失败: " + childInstanceId);
        }
        BusinessSchedule schedule = scheduleMapper.selectById(supervisor.getScheduleId());
        int window = SupervisorRules.fairnessWindow(definition, schedule);
        int current = control.getFairnessCount() == null ? 0 : control.getFairnessCount();
        int next = SupervisorRules.nextWindowCount(current, window);
        long revision = value(control.getRevision()) + 1;
        int controlRows = controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
            .eq(ProcessScheduleControl::getId, control.getId())
            .eq(ProcessScheduleControl::getRevision, control.getRevision())
            .set(ProcessScheduleControl::getFairnessCount, next)
            .set(ProcessScheduleControl::getRevision, revision)
            .set(ProcessScheduleControl::getLastAdmittedAt, now)
            .set(ProcessScheduleControl::getLastActivityAt, now));
        if (controlRows != 1) {
            throw new ServiceException("监督控制修订竞争失败: " + supervisor.getId());
        }
        ProcessActionAudit audit = auditService.newAudit(ActionType.SCHEDULE_CONTROL,
            ActionSource.SCHEDULER, AuditResult.SUCCESS);
        audit.setSupervisorInstanceId(supervisor.getId());
        audit.setChildInstanceId(childInstanceId);
        audit.setInstanceId(childInstanceId);
        audit.setEventId(eventId);
        audit.setAttemptId(attemptId);
        audit.setRoundNo(roundNo);
        audit.setSlotBefore(held);
        audit.setSlotAfter(held + 1);
        audit.setEpoch(control.getEpoch());
        audit.setControlRevision(revision);
        audit.setReason("PLAN_SLOT_ADMITTED window=" + window + " fairnessCount=" + next);
        auditService.append(audit);
        controlIntentService.wakeWorker(supervisor.getScheduleId());
        return new SupervisorSlot(true, "ADMITTED", held, held + 1, childVersion + 1);
    }

    private ProcessInstance requireSupervisor(long supervisorInstanceId) {
        ProcessInstance supervisor = instanceMapper.selectById(supervisorInstanceId);
        if (supervisor == null || supervisor.getPlanRole() != PlanRole.SUPERVISOR) {
            throw new ServiceException("监督实例不存在: " + supervisorInstanceId);
        }
        return supervisor;
    }

    private ProcessScheduleControl lockControl(long supervisorInstanceId) {
        return controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisorInstanceId)
            .eq(ProcessScheduleControl::getDuty, SupervisorRules.DUTY_DISPATCH)
            .last("FOR UPDATE"));
    }

    private static boolean matches(ProcessScheduleControl control, SupervisorRoundLease lease) {
        return control != null && control.getRoundStatus() == RoundStatus.OPEN
            && value(control.getRoundNo()) == lease.roundNo()
            && Objects.equals(control.getEpoch(), lease.epoch())
            && Objects.equals(control.getOwner(), lease.owner());
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static boolean isActionAck(String observedState) {
        return observedState == null || observedState.isBlank()
            || "TRIGGERED".equalsIgnoreCase(observedState);
    }

    private void audit(long supervisorId, long roundNo, long epoch, long revision, String reason) {
        ProcessActionAudit audit = auditService.newAudit(ActionType.SCHEDULE_CONTROL, ActionSource.SYSTEM,
            AuditResult.SUCCESS);
        audit.setSupervisorInstanceId(supervisorId);
        audit.setRoundNo(roundNo);
        audit.setEpoch(epoch);
        audit.setControlRevision(revision);
        audit.setReason(reason);
        auditService.append(audit);
    }
}
