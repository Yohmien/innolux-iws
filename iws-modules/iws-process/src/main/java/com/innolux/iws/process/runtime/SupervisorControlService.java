/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督暂停/恢复/重启与版本切换；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.api.SupervisorControlResult;
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
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.PlanStatus;
import com.innolux.iws.process.enums.RoundStatus;
import com.innolux.iws.process.enums.ScheduleControlScope;
import com.innolux.iws.process.enums.SlotState;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.service.ProcessAuditService;
import com.innolux.iws.process.service.CommandReceiptService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 监督人工控制。命令只改变控制意图和轮次门禁，不覆盖子计划业务暂停或未知事实。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class SupervisorControlService {

    private static final String ACTION_PAUSE = "PAUSE";
    private static final String ACTION_RESUME = "RESUME";
    private static final String ACTION_RESTART = "RESTART_WORKER";
    private static final String ACTION_ACTIVATE = "ACTIVATE_VERSION";

    private final ProcessInstanceMapper instanceMapper;

    private final ProcessScheduleControlMapper controlMapper;

    private final PlanDefinitionMapper definitionMapper;

    private final ProcessEventMapper eventMapper;

    private final ProcessAuditService auditService;

    private final ProcessObjectAuthorizer objectAuthorizer;

    private final CommandReceiptService commandReceiptService;

    private final ObjectMapper objectMapper;

    /**
     * 构造服务。
     *
     * @param instanceMapper 实例 Mapper
     * @param controlMapper 控制 Mapper
     * @param definitionMapper 定义 Mapper
     * @param eventMapper 事件 Mapper
     * @param auditService 审计服务
     * @param objectAuthorizer 对象范围授权
     * @param commandReceiptService 命令回执
     * @param objectMapper JSON Mapper
     */
    public SupervisorControlService(ProcessInstanceMapper instanceMapper,
                                    ProcessScheduleControlMapper controlMapper,
                                    PlanDefinitionMapper definitionMapper,
                                    ProcessEventMapper eventMapper,
                                    ProcessAuditService auditService,
                                    ProcessObjectAuthorizer objectAuthorizer,
                                    CommandReceiptService commandReceiptService,
                                    ObjectMapper objectMapper) {
        this.instanceMapper = instanceMapper;
        this.controlMapper = controlMapper;
        this.definitionMapper = definitionMapper;
        this.eventMapper = eventMapper;
        this.auditService = auditService;
        this.objectAuthorizer = objectAuthorizer;
        this.commandReceiptService = commandReceiptService;
        this.objectMapper = objectMapper;
    }

    /**
     * 暂停监督；开放轮次中的未执行事件标为暂停拒绝并安全关轮。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @param expectedVersion 控制修订号
     * @param commandKey 命令键
     * @param reason 原因
     * @return 控制结果
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public SupervisorControlResult pause(long supervisorInstanceId, long expectedVersion, String commandKey,
                                         String reason) {
        objectAuthorizer.authorizeInstance(supervisorInstanceId, ACTION_PAUSE);
        String target = target(supervisorInstanceId, ACTION_PAUSE);
        String payload = payload(ACTION_PAUSE, supervisorInstanceId, expectedVersion, null, reason);
        ProcessScheduleControl control = lockControl(supervisorInstanceId);
        SupervisorControlResult replay = replay(supervisorInstanceId, target, commandKey, payload, null);
        if (replay != null) {
            return replay;
        }
        ProcessInstance supervisor = requireSupervisor(supervisorInstanceId);
        String beforeStatus = control.getControlState() == null ? null : control.getControlState().name();
        requireRevision(control, expectedVersion);
        LocalDateTime now = LocalDateTime.now();
        boolean open = control.getRoundStatus() == RoundStatus.OPEN;
        if (control.getControlState() != ControlState.MANUAL_PAUSED) {
            if (open) {
                eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
                    .eq(ProcessEvent::getInstanceId, supervisorInstanceId)
                    .eq(ProcessEvent::getRoundNo, control.getRoundNo())
                    .in(ProcessEvent::getEventStatus, EventStatus.PENDING, EventStatus.RETRY_WAIT,
                        EventStatus.WAIT_PREDECESSOR, EventStatus.WAIT_EXTERNAL_TRIGGER,
                        EventStatus.MANUAL_PENDING)
                    .set(ProcessEvent::getEventStatus, EventStatus.NOT_SELECTED)
                    .set(ProcessEvent::getWaitReason, null)
                    .set(ProcessEvent::getOutcome, "PAUSED_REJECTED")
                    .set(ProcessEvent::getErrorCode, "PAUSED_REJECTED")
                    .set(ProcessEvent::getErrorMessage, reason)
                    .set(ProcessEvent::getEndedAt, now)
                    .setSql("event_version = event_version + 1")
                    .set(ProcessEvent::getUpdateTime, now));
            }
            var update = Wrappers.<ProcessScheduleControl>lambdaUpdate()
                .eq(ProcessScheduleControl::getId, control.getId())
                .eq(ProcessScheduleControl::getRevision, expectedVersion)
                .set(ProcessScheduleControl::getControlState, ControlState.MANUAL_PAUSED)
                .set(ProcessScheduleControl::getDesiredState, "STOPPED")
                .set(ProcessScheduleControl::getWakePending, Boolean.TRUE)
                .set(ProcessScheduleControl::getRevision, expectedVersion + 1)
                .set(ProcessScheduleControl::getErrorMessage, null)
                .set(ProcessScheduleControl::getLastActivityAt, now);
            if (open) {
                update.set(ProcessScheduleControl::getRoundStatus, RoundStatus.COMPLETED)
                    .set(ProcessScheduleControl::getOwner, null)
                    .set(ProcessScheduleControl::getLeaseUntil, null)
                    .set(ProcessScheduleControl::getEpoch, value(control.getEpoch()) + 1);
            }
            if (controlMapper.update(null, update) != 1) {
                throw new ServiceException("VERSION_CONFLICT: 控制修订已变化");
            }
            instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
                .eq(ProcessInstance::getId, supervisorInstanceId)
                .ne(ProcessInstance::getPlanStatus, PlanStatus.CANCELLED)
                .set(ProcessInstance::getPlanStatus, PlanStatus.PAUSED)
                .set(ProcessInstance::getStopRequested, Boolean.TRUE)
                .set(ProcessInstance::getInstanceVersion, value(supervisor.getInstanceVersion()) + 1)
                .set(ProcessInstance::getUpdateTime, now));
        }
        SupervisorControlResult result = result(supervisorInstanceId);
        appendReceipt(supervisorInstanceId, target, commandKey, payload, beforeStatus, expectedVersion,
            result.controlState(), result.revision(), result, "SUPERVISOR_PAUSE reason=" + reason);
        return result;
    }

    /**
     * 恢复监督；不自动重放暂停拒绝的旧事件，下一轮重新计算。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @param expectedVersion 控制修订号
     * @param commandKey 命令键
     * @param reason 原因
     * @return 控制结果
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public SupervisorControlResult resume(long supervisorInstanceId, long expectedVersion, String commandKey,
                                          String reason) {
        objectAuthorizer.authorizeInstance(supervisorInstanceId, ACTION_RESUME);
        String target = target(supervisorInstanceId, ACTION_RESUME);
        String payload = payload(ACTION_RESUME, supervisorInstanceId, expectedVersion, null, reason);
        ProcessScheduleControl control = lockControl(supervisorInstanceId);
        SupervisorControlResult replay = replay(supervisorInstanceId, target, commandKey, payload, null);
        if (replay != null) {
            return replay;
        }
        ProcessInstance supervisor = requireSupervisor(supervisorInstanceId);
        String beforeStatus = control.getControlState() == null ? null : control.getControlState().name();
        requireRevision(control, expectedVersion);
        if (control.getControlState() != ControlState.AUTO_RUNNING) {
            if (controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
                .eq(ProcessScheduleControl::getId, control.getId())
                .eq(ProcessScheduleControl::getRevision, expectedVersion)
                .set(ProcessScheduleControl::getControlState, ControlState.AUTO_RUNNING)
                .set(ProcessScheduleControl::getDesiredState, "RUNNING")
                .set(ProcessScheduleControl::getWakePending, Boolean.TRUE)
                .set(ProcessScheduleControl::getRevision, expectedVersion + 1)
                .set(ProcessScheduleControl::getErrorMessage, null)
                .set(ProcessScheduleControl::getLastActivityAt, LocalDateTime.now())) != 1) {
                throw new ServiceException("VERSION_CONFLICT: 控制修订已变化");
            }
            instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
                .eq(ProcessInstance::getId, supervisorInstanceId)
                .ne(ProcessInstance::getPlanStatus, PlanStatus.CANCELLED)
                .in(ProcessInstance::getPlanStatus, PlanStatus.PAUSED, PlanStatus.MANUAL_PENDING,
                    PlanStatus.EXCEPTION_HOLD)
                .set(ProcessInstance::getPlanStatus, PlanStatus.RUNNING)
                .set(ProcessInstance::getStopRequested, Boolean.FALSE)
                .set(ProcessInstance::getEndedAt, null)
                .set(ProcessInstance::getInstanceVersion, value(supervisor.getInstanceVersion()) + 1)
                .set(ProcessInstance::getUpdateTime, LocalDateTime.now()));
        }
        SupervisorControlResult result = result(supervisorInstanceId);
        appendReceipt(supervisorInstanceId, target, commandKey, payload, beforeStatus, expectedVersion,
            result.controlState(), result.revision(), result, "SUPERVISOR_RESUME reason=" + reason);
        return result;
    }

    /**
     * 仅提交“确保 worker 运行”的控制意图；暂停时拒绝。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @param expectedVersion 控制修订号
     * @param commandKey 命令键
     * @param reason 原因
     * @return 控制结果
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public SupervisorControlResult restartWorker(long supervisorInstanceId, long expectedVersion,
                                                 String commandKey, String reason) {
        objectAuthorizer.authorizeInstance(supervisorInstanceId, ACTION_RESTART);
        String target = target(supervisorInstanceId, ACTION_RESTART);
        String payload = payload(ACTION_RESTART, supervisorInstanceId, expectedVersion, null, reason);
        ProcessInstance supervisor = requireSupervisor(supervisorInstanceId);
        ProcessScheduleControl control = lockControl(supervisorInstanceId);
        if (control.getControlState() == ControlState.MANUAL_PAUSED) {
            throw new ServiceException("SUPERVISOR_PAUSED: 暂停状态不能重启 worker");
        }
        SupervisorControlResult replay = replay(supervisorInstanceId, target, commandKey, payload, control);
        if (replay != null) {
            return replay;
        }
        String beforeStatus = control.getControlState() == null ? null : control.getControlState().name();
        requireRevision(control, expectedVersion);
        if (controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
            .eq(ProcessScheduleControl::getId, control.getId())
            .eq(ProcessScheduleControl::getRevision, expectedVersion)
            .set(ProcessScheduleControl::getDesiredState, "RUNNING")
            .set(ProcessScheduleControl::getWakePending, Boolean.TRUE)
            .set(ProcessScheduleControl::getRevision, expectedVersion + 1)
            .set(ProcessScheduleControl::getErrorMessage, null)
            .set(ProcessScheduleControl::getLastActivityAt, LocalDateTime.now())) != 1) {
            throw new ServiceException("VERSION_CONFLICT: 控制修订已变化");
        }
        instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, supervisorInstanceId)
            .in(ProcessInstance::getPlanStatus, PlanStatus.COMPLETED, PlanStatus.FAILED_FINAL)
            .set(ProcessInstance::getPlanStatus, PlanStatus.RUNNING)
            .set(ProcessInstance::getEndedAt, null)
            .set(ProcessInstance::getInstanceVersion, value(supervisor.getInstanceVersion()) + 1)
            .set(ProcessInstance::getUpdateTime, LocalDateTime.now()));
        SupervisorControlResult result = result(supervisorInstanceId);
        appendReceipt(supervisorInstanceId, target, commandKey, payload, beforeStatus, expectedVersion,
            result.controlState(), result.revision(), result, "SUPERVISOR_RESTART_WORKER reason=" + reason);
        return result;
    }

    /**
     * 暂停状态下切换同一监督身份的冻结版本。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @param expectedVersion 控制修订号
     * @param targetDefinitionVersionId 目标定义版本
     * @param commandKey 命令键
     * @param reason 原因
     * @return 控制结果
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public SupervisorControlResult activateVersion(long supervisorInstanceId, long expectedVersion,
                                                   long targetDefinitionVersionId, String commandKey,
                                                   String reason) {
        objectAuthorizer.authorizeInstance(supervisorInstanceId, ACTION_ACTIVATE);
        String targetKey = target(supervisorInstanceId, ACTION_ACTIVATE);
        String payload = payload(ACTION_ACTIVATE, supervisorInstanceId, expectedVersion,
            targetDefinitionVersionId, reason);
        ProcessScheduleControl control = lockControl(supervisorInstanceId);
        SupervisorControlResult replay = replay(supervisorInstanceId, targetKey, commandKey, payload, null);
        if (replay != null) {
            return replay;
        }
        ProcessInstance supervisor = requireSupervisor(supervisorInstanceId);
        String beforeStatus = control.getControlState() == null ? null : control.getControlState().name();
        requireRevision(control, expectedVersion);
        if (control.getControlState() != ControlState.MANUAL_PAUSED) {
            throw new ServiceException("VERSION_CONFLICT: 版本切换要求监督已暂停");
        }
        if (control.getRoundStatus() == RoundStatus.OPEN) {
            throw new ServiceException("VERSION_CONFLICT: 存在开放轮次");
        }
        if (heldSlots(supervisorInstanceId) > 0) {
            throw new ServiceException("VERSION_CONFLICT: 存在占用 PLAN_SLOT");
        }
        if (Boolean.TRUE.equals(control.getWakePending())) {
            throw new ServiceException("VERSION_CONFLICT: 存在未收敛平台控制");
        }
        List<Long> childIds = childInstanceIds(supervisorInstanceId);
        if (!childIds.isEmpty()) {
            long inFlight = eventMapper.selectCount(Wrappers.<ProcessEvent>lambdaQuery()
                .in(ProcessEvent::getInstanceId, childIds)
                .and(wrapper -> wrapper.eq(ProcessEvent::getSent, Boolean.TRUE)
                    .or().eq(ProcessEvent::getEffectResult, EffectResult.UNKNOWN))
                .notIn(ProcessEvent::getEventStatus, EventStatus.SUCCESS, EventStatus.SKIPPED,
                    EventStatus.NOT_SELECTED, EventStatus.FAILED_FINAL));
            if (inFlight > 0) {
                throw new ServiceException("VERSION_CONFLICT: 存在未收敛 CALL/在途效果");
            }
        }
        PlanDefinition target = definitionMapper.selectById(targetDefinitionVersionId);
        if (target == null || target.getPlanRole() != PlanRole.SUPERVISOR
            || target.getStatus() != DefinitionStatus.ACTIVE || target.getMode() == null) {
            throw new ServiceException("目标监督版本不存在或未发布");
        }
        if (!Objects.equals(target.getBusinessScheduleCode(), supervisor.getBusinessScheduleCode())
            || !Objects.equals(target.getPlanType(), supervisor.getPlanType())) {
            throw new ServiceException("VERSION_CONFLICT: 目标版本业务或类型不兼容");
        }
        SupervisorRules.requireSupervisorDefinition(target);
        List<ProcessInstance> children = instanceMapper.selectList(Wrappers.<ProcessInstance>lambdaQuery()
            .eq(ProcessInstance::getSupervisorInstanceId, supervisorInstanceId)
            .eq(ProcessInstance::getPlanRole, PlanRole.BUSINESS));
        boolean modeChanged = target.getMode() != supervisor.getModeSnapshot();
        for (ProcessInstance child : children) {
            boolean active = child.getPlanStatus() == PlanStatus.CREATED
                || child.getPlanStatus() == PlanStatus.RUNNING || child.getPlanStatus() == PlanStatus.PAUSED
                || child.getPlanStatus() == PlanStatus.MANUAL_PENDING
                || child.getPlanStatus() == PlanStatus.EXCEPTION_HOLD;
            if (modeChanged && active) {
                throw new ServiceException("MODE_SWITCH_BLOCKED: 存在未终结子计划");
            }
            if (active && child.getModeSnapshot() != null && child.getModeSnapshot() != target.getMode()) {
                throw new ServiceException("MODE_SWITCH_BLOCKED: 子计划模式快照不兼容");
            }
        }
        long instanceVersion = value(supervisor.getInstanceVersion());
        if (instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, supervisorInstanceId)
            .eq(ProcessInstance::getInstanceVersion, instanceVersion)
            .set(ProcessInstance::getDefinitionVersionId, target.getId())
            .set(ProcessInstance::getDefinitionRevision, target.getRevision())
            .set(ProcessInstance::getModeSnapshot, target.getMode())
            .set(ProcessInstance::getInstanceVersion, instanceVersion + 1)
            .set(ProcessInstance::getUpdateTime, LocalDateTime.now())) != 1) {
            throw new ServiceException("VERSION_CONFLICT: 监督实例版本已变化");
        }
        if (controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
            .eq(ProcessScheduleControl::getId, control.getId())
            .eq(ProcessScheduleControl::getRevision, expectedVersion)
            .set(ProcessScheduleControl::getDefinitionVersionId, target.getId())
            .set(ProcessScheduleControl::getRevision, expectedVersion + 1)
            .set(ProcessScheduleControl::getErrorMessage, null)
            .set(ProcessScheduleControl::getLastActivityAt, LocalDateTime.now())) != 1) {
            throw new ServiceException("VERSION_CONFLICT: 控制修订已变化");
        }
        SupervisorControlResult result = result(supervisorInstanceId);
        appendReceipt(supervisorInstanceId, targetKey, commandKey, payload, beforeStatus, expectedVersion,
            result.controlState(), result.revision(), result,
            "SUPERVISOR_VERSION_ACTIVATED definition=" + target.getId() + " reason=" + reason);
        return result;
    }

    /**
     * 计算可用动作。
     *
     * @param supervisor 监督实例
     * @param control 控制行
     * @param heldSlots 占用名额
     * @return 动作
     */
    static List<String> allowedActions(ProcessInstance supervisor, ProcessScheduleControl control, long heldSlots) {
        List<String> actions = new ArrayList<>();
        if (supervisor == null || control == null || supervisor.getPlanStatus() == PlanStatus.CANCELLED) {
            return actions;
        }
        if (control.getControlState() == ControlState.MANUAL_PAUSED) {
            actions.add(ACTION_RESUME);
            if (control.getRoundStatus() != RoundStatus.OPEN && heldSlots == 0
                && !Boolean.TRUE.equals(control.getWakePending())) {
                actions.add(ACTION_ACTIVATE);
            }
        } else {
            actions.add(ACTION_PAUSE);
            actions.add(ACTION_RESTART);
        }
        return actions;
    }

    /**
     * 动作限制说明。
     *
     * @param control 控制行
     * @param heldSlots 占用名额
     * @return 说明
     */
    static String actionReason(ProcessScheduleControl control, long heldSlots) {
        if (control == null) {
            return "缺少监督控制行";
        }
        if (control.getRoundStatus() == RoundStatus.OPEN) {
            return "存在开放轮次";
        }
        if (heldSlots > 0) {
            return "存在占用 PLAN_SLOT";
        }
        if (Boolean.TRUE.equals(control.getWakePending())) {
            return "存在未收敛平台控制";
        }
        return null;
    }

    private SupervisorControlResult replay(long supervisorInstanceId, String target, String commandKey,
                                           String payload, ProcessScheduleControl control) {
        ProcessActionAudit receipt = commandReceiptService.find(ActionType.SCHEDULE_CONTROL, target, commandKey);
        if (receipt == null) {
            return null;
        }
        commandReceiptService.checkSamePayload(receipt, payload);
        checkActor(receipt);
        if (control != null && control.getControlState() == ControlState.MANUAL_PAUSED
            && target.endsWith(":" + ACTION_RESTART)) {
            throw new ServiceException("SUPERVISOR_PAUSED: 暂停状态不能重启 worker");
        }
        if (receipt.getDecisionSummary() != null && !receipt.getDecisionSummary().isBlank()) {
            try {
                SupervisorControlResult original = objectMapper.readValue(receipt.getDecisionSummary(),
                    SupervisorControlResult.class);
                return new SupervisorControlResult(original.supervisorInstanceId(), "SUCCEEDED",
                    "原命令已执行（幂等回放）", original.controlState(), original.desiredState(),
                    original.observedState(), original.revision(), original.wakePending(),
                    original.allowedActions());
            } catch (JsonProcessingException ignored) {
                // 旧回执没有结构化结果时退回当前快照。
            }
        }
        return result(supervisorInstanceId);
    }

    private void appendReceipt(long supervisorInstanceId, String target, String commandKey, String payload,
                        String beforeStatus, long beforeVersion, String afterStatus, long afterVersion,
                        SupervisorControlResult result, String reason) {
        ProcessActionAudit audit = auditService.newAudit(ActionType.SCHEDULE_CONTROL, ActionSource.USER,
            AuditResult.SUCCESS);
        audit.setSupervisorInstanceId(supervisorInstanceId);
        audit.setCommandKey(commandKey);
        audit.setReceiptKey(commandReceiptService.key(ActionType.SCHEDULE_CONTROL, target, commandKey));
        audit.setPayloadSummary(payload);
        audit.setBeforeStatus(beforeStatus);
        audit.setBeforeVersion(beforeVersion);
        audit.setAfterStatus(afterStatus);
        audit.setAfterVersion(afterVersion);
        audit.setDecisionSummary(toJson(result));
        audit.setReason(reason);
        auditService.append(audit);
    }

    private String toJson(SupervisorControlResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            throw new ServiceException("控制结果序列化失败: " + ex.getMessage());
        }
    }

    private static void checkActor(ProcessActionAudit receipt) {
        String current = currentActor();
        String original = receipt.getActorUserId() != null ? String.valueOf(receipt.getActorUserId())
            : receipt.getActorName();
        if (original != null && current != null && !original.equals(current)) {
            throw new ServiceException("同一 commandKey 只能由原操作者重放");
        }
    }

    private static String currentActor() {
        try {
            Object loginId = StpUtil.getLoginIdDefaultNull();
            return loginId == null ? null : loginId.toString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String target(long supervisorInstanceId, String action) {
        return "SUPERVISOR:" + supervisorInstanceId + ":" + action;
    }

    private String payload(String action, long supervisorInstanceId, long expectedVersion,
                           Long targetDefinitionVersionId, String reason) {
        return commandReceiptService.payloadHash(String.join("|", action, String.valueOf(supervisorInstanceId),
            String.valueOf(expectedVersion), targetDefinitionVersionId == null ? ""
                : String.valueOf(targetDefinitionVersionId), reason == null ? "" : reason));
    }

    private SupervisorControlResult result(long supervisorInstanceId) {
        ProcessInstance supervisor = requireSupervisor(supervisorInstanceId);
        ProcessScheduleControl control = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisorInstanceId)
            .eq(ProcessScheduleControl::getDuty, SupervisorRules.DUTY_DISPATCH));
        long held = heldSlots(supervisorInstanceId);
        return new SupervisorControlResult(String.valueOf(supervisorInstanceId), "SUCCEEDED",
            "控制意图已提交", control == null || control.getControlState() == null ? null
            : control.getControlState().name(), control == null ? null : control.getDesiredState(),
            control == null ? null : control.getObservedState(),
            control == null ? 0L : value(control.getRevision()),
            control != null && Boolean.TRUE.equals(control.getWakePending()),
            allowedActions(supervisor, control, held));
    }

    private long heldSlots(long supervisorInstanceId) {
        return controlMapper.selectCount(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.PLAN_SLOT)
            .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisorInstanceId)
            .eq(ProcessScheduleControl::getSlotState, SlotState.HELD));
    }

    private List<Long> childInstanceIds(long supervisorInstanceId) {
        return instanceMapper.selectList(Wrappers.<ProcessInstance>lambdaQuery()
            .eq(ProcessInstance::getSupervisorInstanceId, supervisorInstanceId)
            .eq(ProcessInstance::getPlanRole, PlanRole.BUSINESS)
            .select(ProcessInstance::getId)).stream().map(ProcessInstance::getId).toList();
    }

    private ProcessInstance requireSupervisor(long supervisorInstanceId) {
        ProcessInstance supervisor = instanceMapper.selectById(supervisorInstanceId);
        if (supervisor == null || supervisor.getPlanRole() != PlanRole.SUPERVISOR) {
            throw new ServiceException("监督实例不存在: " + supervisorInstanceId);
        }
        return supervisor;
    }

    private ProcessScheduleControl lockControl(long supervisorInstanceId) {
        ProcessScheduleControl control = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisorInstanceId)
            .eq(ProcessScheduleControl::getDuty, SupervisorRules.DUTY_DISPATCH)
            .last("FOR UPDATE"));
        if (control == null) {
            throw new ServiceException("监督控制行不存在: " + supervisorInstanceId);
        }
        return control;
    }

    private static void requireRevision(ProcessScheduleControl control, long expectedVersion) {
        if (value(control.getRevision()) != expectedVersion) {
            throw new ServiceException("VERSION_CONFLICT: 当前 revision=" + control.getRevision());
        }
    }

    private void audit(long supervisorId, Long roundNo, long epoch, long revision, String reason) {
        ProcessActionAudit audit = auditService.newAudit(ActionType.SCHEDULE_CONTROL, ActionSource.USER,
            AuditResult.SUCCESS);
        audit.setSupervisorInstanceId(supervisorId);
        audit.setRoundNo(roundNo);
        audit.setEpoch(epoch);
        audit.setControlRevision(revision);
        audit.setReason(reason);
        auditService.append(audit);
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }
}
