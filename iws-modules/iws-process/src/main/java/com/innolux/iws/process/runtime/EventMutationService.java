/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 事件认领、发送意图、检查点与终态 CAS；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessActionAudit;
import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.domain.ProcessSystemContext;
import com.innolux.iws.process.domain.ProcessToken;
import com.innolux.iws.process.enums.ActionSource;
import com.innolux.iws.process.enums.ActionType;
import com.innolux.iws.process.enums.AuditResult;
import com.innolux.iws.process.enums.CheckpointStatus;
import com.innolux.iws.process.enums.ControlState;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.ExecutionPurpose;
import com.innolux.iws.process.enums.PermitState;
import com.innolux.iws.process.enums.PlanStatus;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.ScheduleControlScope;
import com.innolux.iws.process.enums.SlotState;
import com.innolux.iws.process.enums.TokenStatus;
import com.innolux.iws.process.enums.WaitReason;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import com.innolux.iws.process.service.ProcessAuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事件写入唯一入口。所有更新都带事件版本、租约代次和 owner 条件。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class EventMutationService {

    private final RuntimeDao runtimeDao;

    private final ProcessEventMapper eventMapper;

    private final ProcessAttemptMapper attemptMapper;

    private final ProcessTokenMapper tokenMapper;

    private final ProcessInstanceMapper instanceMapper;

    private final ProcessScheduleControlMapper controlMapper;

    private final ProcessAuditService auditService;

    private final JsonMapper objectMapper;

    private final PlanActivationService activationService;

    private final CallCapacityService callCapacityService;

    private final CapabilityRegistry capabilityRegistry;
    private final RecoveryEvidenceService recoveryEvidenceService;
    private final com.innolux.iws.process.mapper.ProcessRequestDetailMapper requestDetailMapper;

    /**
     * 构造写入服务。
     *
     * @param runtimeDao 运行时 DAO
     * @param eventMapper 事件 Mapper
     * @param attemptMapper 尝试 Mapper
     * @param tokenMapper token Mapper
     * @param instanceMapper 实例 Mapper
     * @param controlMapper 控制 Mapper
     * @param auditService 审计服务
     * @param objectMapper JSON Mapper
     * @param activationService 后继激活服务
     * @param callCapacityService CALL 容量服务
     * @param capabilityRegistry 能力注册表
     */
    public EventMutationService(RuntimeDao runtimeDao, ProcessEventMapper eventMapper,
                                ProcessAttemptMapper attemptMapper, ProcessTokenMapper tokenMapper,
                                ProcessInstanceMapper instanceMapper, ProcessScheduleControlMapper controlMapper,
                                ProcessAuditService auditService, JsonMapper objectMapper,
                                PlanActivationService activationService, CallCapacityService callCapacityService,
                                CapabilityRegistry capabilityRegistry, RecoveryEvidenceService recoveryEvidenceService,
                                com.innolux.iws.process.mapper.ProcessRequestDetailMapper requestDetailMapper) {
        this.runtimeDao = runtimeDao;
        this.eventMapper = eventMapper;
        this.attemptMapper = attemptMapper;
        this.tokenMapper = tokenMapper;
        this.instanceMapper = instanceMapper;
        this.controlMapper = controlMapper;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.activationService = activationService;
        this.callCapacityService = callCapacityService;
        this.capabilityRegistry = capabilityRegistry;
        this.recoveryEvidenceService = recoveryEvidenceService;
        this.requestDetailMapper = requestDetailMapper;
    }

    /**
     * CAS 认领事件并创建新尝试。
     *
     * @param eventId 事件 ID
     * @param owner 认领者
     * @param leaseMs 租约毫秒
     * @return 认领上下文，竞争失败返回 null
     */
    @Transactional(rollbackFor = Exception.class)
    public ClaimContext claim(long eventId, String owner, long leaseMs) {
        ProcessEvent event = runtimeDao.event(eventId);
        if (event.getEventStatus() != EventStatus.PENDING && event.getEventStatus() != EventStatus.RETRY_WAIT) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        if (event.getEventStatus() == EventStatus.RETRY_WAIT
            && event.getNextRetryTime() != null && event.getNextRetryTime().isAfter(now)) {
            return null;
        }
        ProcessInstance instance = instanceMapper.selectOne(Wrappers.<ProcessInstance>lambdaQuery()
            .eq(ProcessInstance::getId, event.getInstanceId())
            .last("FOR UPDATE"));
        boolean recoveryOnly = event.getExecutionPurpose() == ExecutionPurpose.RECOVERY_ONLY
            && Boolean.TRUE.equals(event.getRecoveryRequested());
        boolean forbiddenStatus = recoveryOnly
            ? (instance.getPlanStatus() == PlanStatus.PAUSED || instance.getPlanStatus() == PlanStatus.CANCELLED
            || instance.getPlanStatus() == PlanStatus.COMPLETED || instance.getPlanStatus() == PlanStatus.FAILED_FINAL)
            : instance.getPlanStatus() != PlanStatus.RUNNING;
        if (forbiddenStatus || (!recoveryOnly && (Boolean.TRUE.equals(instance.getStopRequested())
            || Boolean.TRUE.equals(instance.getCancelRequested())))) {
            return null;
        }
        if (recoveryOnly && event.getRecoveryDeadline() != null
            && !event.getRecoveryDeadline().isAfter(LocalDateTime.now())) {
            return null;
        }
        if (instance.getPlanRole() == PlanRole.BUSINESS) {
            if (!recoveryOnly && Boolean.TRUE.equals(instance.getHoldStart())) {
                return null;
            }
            long slots = controlMapper.selectCount(Wrappers.<ProcessScheduleControl>lambdaQuery()
                .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.PLAN_SLOT)
                .eq(ProcessScheduleControl::getScopeKey, String.valueOf(instance.getId()))
                .eq(ProcessScheduleControl::getSlotState, SlotState.HELD));
            if (slots != 1) {
                return null;
            }
            if (instance.getSupervisorInstanceId() != null) {
                ProcessScheduleControl supervisor = controlMapper.selectOne(
                    Wrappers.<ProcessScheduleControl>lambdaQuery()
                        .eq(ProcessScheduleControl::getSupervisorInstanceId, instance.getSupervisorInstanceId())
                        .eq(ProcessScheduleControl::getDuty, "DISPATCH"));
                if (supervisor != null && supervisor.getControlState() == ControlState.MANUAL_PAUSED) {
                    return null;
                }
            }
        }
        long version = event.getEventVersion() == null ? 1L : event.getEventVersion();
        long epoch = (event.getLeaseEpoch() == null ? 0L : event.getLeaseEpoch()) + 1;
        ProcessAttempt last = event.getLastAttemptId() == null ? null : attemptMapper.selectById(event.getLastAttemptId());
        long attemptNo = last == null ? 1L : attemptMapper.selectCount(Wrappers.<ProcessAttempt>lambdaQuery()
            .eq(ProcessAttempt::getEventId, eventId)) + 1;
        ProcessAttempt attempt = new ProcessAttempt();
        attempt.setId(IdWorker.getId());
        attempt.setEventId(eventId);
        attempt.setAttemptNo((int) attemptNo);
        attempt.setResumeFromAttemptId(last == null ? null : last.getId());
        attempt.setCheckpointStatus(CheckpointStatus.CLAIMED);
        attempt.setSent(Boolean.FALSE);
        attempt.setInputHash(event.getInputHash());
        attempt.setIdempotencyKey(event.getIdempotencyKey());
        attempt.setOperationVersion(event.getCallContractVersion());
        attempt.setLocalActionVersion(event.getLocalActionVersion());
        attempt.setLocalEffectKey(event.getLocalEffectKey());
        attempt.setSourceStateCode(event.getSourceStateCode());
        attempt.setTargetStateCode(event.getTargetStateCode());
        attempt.setOwner(owner);
        attempt.setLeaseEpoch(epoch);
        attempt.setExecutionPurpose(event.getExecutionPurpose());
        attempt.setStartedAt(now);
        attempt.setRecordedAt(now);

        // 认领与成功证据继承同事务提交，认领后崩溃也不能遮蔽已有检查点。
        if (RecoveryPolicy.decide(last, Boolean.TRUE.equals(event.getSent())) == RecoveryPolicy.Decision.COMPLETE) {
            attempt.setCheckpointStatus(last.getCheckpointStatus());
            attempt.setEffectResult(last.getEffectResult());
            attempt.setEvidenceRef(last.getEvidenceRef());
            attempt.setCheckpointJson(last.getCheckpointJson());
            attempt.setRequestId(last.getRequestId());
        }

        int rows = eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, eventId)
            .eq(ProcessEvent::getEventVersion, version)
            .in(ProcessEvent::getEventStatus, EventStatus.PENDING, EventStatus.RETRY_WAIT)
            .and(wrapper -> wrapper.isNull(ProcessEvent::getLeaseUntil)
                .or().lt(ProcessEvent::getLeaseUntil, now))
            .set(ProcessEvent::getEventStatus, EventStatus.PROCESSING)
            .set(ProcessEvent::getOwner, owner)
            .set(ProcessEvent::getLeaseEpoch, epoch)
            .set(ProcessEvent::getLeaseUntil, now.plusNanos(leaseMs * 1_000_000L))
            .set(ProcessEvent::getStartedAt, now)
            .set(ProcessEvent::getEventVersion, version + 1)
            .set(ProcessEvent::getUpdateTime, now));
        if (rows != 1) {
            throw new LeaseLostException();
        }
        attemptMapper.insert(attempt);
        int attemptRows = eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, eventId)
            .eq(ProcessEvent::getEventVersion, version + 1)
            .eq(ProcessEvent::getLeaseEpoch, epoch)
            .eq(ProcessEvent::getOwner, owner)
            .set(ProcessEvent::getLastAttemptId, attempt.getId())
            .set(ProcessEvent::getUpdateTime, now));
        if (attemptRows != 1) {
            throw new LeaseLostException();
        }
        event.setEventStatus(EventStatus.PROCESSING);
        event.setOwner(owner);
        event.setLeaseEpoch(epoch);
        event.setLeaseUntil(now.plusNanos(leaseMs * 1_000_000L));
        event.setEventVersion(version + 1);
        event.setLastAttemptId(attempt.getId());
        event.setLeaseUntil(now.plusNanos(leaseMs * 1_000_000L));
        event.setEventVersion(version + 1);
        audit(ActionType.EVENT_EXECUTE, event, attempt, AuditResult.SUCCESS, "CLAIM");
        return new ClaimContext(event, attempt);
    }

    /**
     * OUTBOUND T2：创建 CALL 控制并写发送意图。
     *
     * @param claim 认领上下文
     * @param requestId 实际请求 ID
     * @param targetSummary 目标摘要
     * @param resourceKey 资源键
     * @return 更新后的尝试
     */
    @Transactional(rollbackFor = Exception.class)
    public ProcessAttempt beginSend(ClaimContext claim, String requestId, String targetSummary, String resourceKey) {
        return beginSend(claim, requestId, new SendTarget(null, null, null, null, null, null, null, null,
            resourceKey, claim.event().getIdempotencyKey(), targetSummary));
    }

    /**
     * OUTBOUND T2：冻结完整目标身份并写发送意图。
     *
     * @param claim 认领上下文
     * @param requestId 实际请求 ID
     * @param target 完整目标身份
     * @return 更新后的尝试
     */
    @Transactional(rollbackFor = Exception.class)
    public ProcessAttempt beginSend(ClaimContext claim, String requestId, SendTarget target) {
        ProcessEvent event = requireOwned(claim);
        ProcessInstance instance = instanceMapper.selectOne(Wrappers.<ProcessInstance>lambdaQuery()
            .eq(ProcessInstance::getId, event.getInstanceId())
            .last("FOR UPDATE"));
        boolean recoveryQuery = target.recoveryQuery()
            && event.getExecutionPurpose() == ExecutionPurpose.RECOVERY_ONLY
            && Boolean.TRUE.equals(event.getRecoveryRequested())
            && event.getRecoveryForEventCode() != null;
        if (target.recoveryQuery() != recoveryQuery) {
            throw new ServiceException("未授权的 QUERY 恢复发送");
        }
        if (!recoveryQuery && (Boolean.TRUE.equals(instance.getCancelRequested()) || Boolean.TRUE.equals(instance.getStopRequested()))) {
            throw new SendBlockedException("CANCEL_REQUESTED", "计划已请求取消，禁止新发送意图");
        }
        if (!recoveryQuery && instance.getExecutionDeadline() != null
            && !instance.getExecutionDeadline().isAfter(LocalDateTime.now())) {
            throw new SendBlockedException("RESPONSE_TIMEOUT", "执行期限已到，禁止新发送意图");
        }
        if (recoveryQuery && (event.getRecoveryDeadline() == null
            || !event.getRecoveryDeadline().isAfter(LocalDateTime.now()))) {
            throw new SendBlockedException("RECOVERY_TIMEOUT", "恢复查询期限已到，禁止发送意图");
        }
        if (!recoveryQuery && !callCapacityService.tryAcquire(target.resourceKey(), event.getId())) {
            throw new CapacityUnavailableException();
        }
        String controlKey = target.callScopeEventId() == null ? String.valueOf(event.getId())
            : String.valueOf(target.callScopeEventId());
        ProcessScheduleControl control = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.CALL)
            .eq(ProcessScheduleControl::getScopeKey, controlKey)
            .eq(ProcessScheduleControl::getDuty, "CALL_PERMIT"));
        if (recoveryQuery) {
            if (control == null || control.getPermitState() == PermitState.RELEASED) {
                throw new ServiceException("QUERY_CALL_MISSING: 原 CALL 不存在或已释放");
            }
            controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
                .eq(ProcessScheduleControl::getId, control.getId())
                .eq(ProcessScheduleControl::getRevision, control.getRevision())
                .set(ProcessScheduleControl::getRevision, control.getRevision() + 1)
                .set(ProcessScheduleControl::getLastActivityAt, LocalDateTime.now()));
            control.setRevision(control.getRevision() + 1);
        }
        if (control == null) {
            control = new ProcessScheduleControl();
            control.setScopeType(ScheduleControlScope.CALL);
            control.setScopeKey(String.valueOf(event.getId()));
            control.setDuty("CALL_PERMIT");
            control.setRevision(1L);
            control.setEpoch(event.getLeaseEpoch());
            control.setOwner(event.getOwner());
            control.setControlState(ControlState.AUTO_RUNNING);
            control.setPermitState(PermitState.INTENT);
            control.setResourceKey(target.resourceKey());
            control.setWakePending(Boolean.FALSE);
            control.setLastActivityAt(LocalDateTime.now());
            controlMapper.insert(control);
        } else if (control.getPermitState() == PermitState.RELEASED) {
            int rows = controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
                .eq(ProcessScheduleControl::getId, control.getId())
                .eq(ProcessScheduleControl::getRevision, control.getRevision())
                .eq(ProcessScheduleControl::getPermitState, PermitState.RELEASED)
                .set(ProcessScheduleControl::getPermitState, PermitState.INTENT)
                .set(ProcessScheduleControl::getRevision, control.getRevision() + 1)
                .set(ProcessScheduleControl::getEpoch, event.getLeaseEpoch())
                .set(ProcessScheduleControl::getOwner, event.getOwner())
                .set(ProcessScheduleControl::getLastActivityAt, LocalDateTime.now()));
            if (rows != 1) {
                throw new ServiceException("CALL 许可竞争失败，停止发送");
            }
        }
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("status", "SEND_INTENT");
        checkpoint.put("requestId", requestId);
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("target", target.baseUrl());
        intent.put("addressId", target.addressId());
        intent.put("addressRevision", target.addressRevision());
        intent.put("catalogRevision", target.catalogRevision());
        intent.put("environmentCode", target.environmentCode());
        intent.put("targetSystemCode", target.targetSystemCode());
        intent.put("contractKey", target.contractKey());
        intent.put("contractVersion", target.contractVersion());
        intent.put("httpMethod", target.httpMethod());
        intent.put("apiPath", target.apiPath());
        intent.put("resourceKey", target.resourceKey());
        intent.put("logicalCallKey", target.logicalCallKey());
        intent.put("callScopeEventId", target.callScopeEventId());
        intent.put("recoveryQuery", target.recoveryQuery());
        intent.put("sentAt", LocalDateTime.now().toString());
        checkpoint.put("sendIntent", intent);
        // 容量与实例锁可能等待至租约过期；等锁后仍须有执行权。
        requireOwned(claim);
        ProcessAttempt attempt = claim.attempt();
        attempt.setCheckpointStatus(CheckpointStatus.SEND_INTENT);
        attempt.setSent(Boolean.TRUE);
        attempt.setRequestId(requestId);
        attempt.setCheckpointJson(toJson(checkpoint));
        attempt.setRecordedAt(LocalDateTime.now());
        attemptMapper.updateById(attempt);
        int rows = eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, event.getId())
            .eq(ProcessEvent::getEventVersion, event.getEventVersion())
            .eq(ProcessEvent::getLeaseEpoch, event.getLeaseEpoch())
            .eq(ProcessEvent::getOwner, event.getOwner())
            .eq(ProcessEvent::getEventStatus, EventStatus.PROCESSING)
            .gt(ProcessEvent::getLeaseUntil, LocalDateTime.now())
            .set(ProcessEvent::getSent, Boolean.TRUE)
            .set(ProcessEvent::getEventVersion, event.getEventVersion() + 1)
            .set(ProcessEvent::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new LeaseLostException();
        }
        event.setSent(Boolean.TRUE);
        event.setEventVersion(event.getEventVersion() + 1);
        claim.advance(event.getEventVersion());
        audit(ActionType.EVENT_EXECUTE, event, attempt, AuditResult.SUCCESS, "SEND_INTENT");
        return attempt;
    }

    /**
     * 保存单动作结果与检查点。
     *
     * @param claim 认领上下文
     * @param result 单动作结果
     * @return 更新后的尝试
     */
    @Transactional(rollbackFor = Exception.class)
    public ProcessAttempt recordResult(ClaimContext claim, ActionResult result) {
        ProcessEvent event = requireOwned(claim);
        ProcessAttempt attempt = claim.attempt();
        if (event.getEventDirection() == com.innolux.iws.process.enums.EventDirection.OUTBOUND
            && Boolean.TRUE.equals(attempt.getSent()) && attempt.getRequestId() != null
            && requestDetailMapper.selectCount(Wrappers.<com.innolux.iws.process.domain.ProcessRequestDetail>lambdaQuery()
                .eq(com.innolux.iws.process.domain.ProcessRequestDetail::getRequestId, attempt.getRequestId())) == 0) {
            requestDetailMapper.insert(com.innolux.iws.process.support.ProcessRequestRecords.outbound(event,
                attempt.getId(), result, attempt.getRequestId(), attempt.getStartedAt(),
                runtimeDao.graph(event.getDefinitionVersionId()).event(event.getEventCode()).getPurpose()));
        }
        attempt.setEffectResult(result.effectResult());
        attempt.setEvidenceRef(result.evidenceRef());
        attempt.setRequestId(result.requestId() == null ? attempt.getRequestId() : result.requestId());
        attempt.setCheckpointStatus(checkpointStatus(result.effectResult()));
        attempt.setCheckpointJson(mergeCheckpoint(attempt.getCheckpointJson(), result.checkpointJson()));
        attempt.setErrorMessage(result.message());
        attempt.setRecordedAt(LocalDateTime.now());
        if (result.effectResult() != EffectResult.UNKNOWN) {
            attempt.setEndedAt(LocalDateTime.now());
        }
        attemptMapper.updateById(attempt);
        int rows = eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, event.getId())
            .eq(ProcessEvent::getEventVersion, event.getEventVersion())
            .eq(ProcessEvent::getLeaseEpoch, event.getLeaseEpoch())
            .eq(ProcessEvent::getOwner, event.getOwner())
            .eq(ProcessEvent::getEventStatus, EventStatus.PROCESSING)
            .gt(ProcessEvent::getLeaseUntil, LocalDateTime.now())
            .set(ProcessEvent::getEffectResult, result.effectResult())
            .set(ProcessEvent::getOutcome, outcome(result.effectResult()))
            .set(ProcessEvent::getEventVersion, event.getEventVersion() + 1)
            .set(ProcessEvent::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new LeaseLostException();
        }
        event.setEffectResult(result.effectResult());
        event.setOutcome(outcome(result.effectResult()));
        event.setEventVersion(event.getEventVersion() + 1);
        claim.advance(event.getEventVersion());
        audit(ActionType.EVENT_EXECUTE, event, attempt, result.effectResult() == EffectResult.UNKNOWN
            ? AuditResult.UNKNOWN : AuditResult.SUCCESS, result.message());
        return attempt;
    }

    /**
     * 提交成功终态并推进 token。
     *
     * @param claim 认领上下文
     * @param effectResult 效果结果
     * @return 是否提交成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean finishSuccess(ClaimContext claim, EffectResult effectResult) {
        ProcessEvent event = requireOwned(claim);
        validateTarget(event, effectResult);
        LocalDateTime now = LocalDateTime.now();
        EventStatus terminalStatus = effectResult == EffectResult.SKIPPED
            ? EventStatus.SKIPPED : EventStatus.SUCCESS;
        ProcessToken token = runtimeDao.token(event.getTokenId());
        int tokenRows = tokenMapper.update(null, Wrappers.<ProcessToken>lambdaUpdate()
            .eq(ProcessToken::getId, token.getId())
            .eq(ProcessToken::getTokenVersion, token.getTokenVersion())
            .set(ProcessToken::getStateCode, event.getTargetStateCode())
            .set(ProcessToken::getTokenStatus, TokenStatus.ACTIVE)
            .set(ProcessToken::getBlockedEventId, null)
            .set(ProcessToken::getLastAttemptId, claim.attempt().getId())
            .set(ProcessToken::getTokenVersion, token.getTokenVersion() + 1)
            .set(ProcessToken::getUpdateTime, now));
        if (tokenRows != 1) {
            throw new LeaseLostException();
        }
        int rows = eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, event.getId())
            .eq(ProcessEvent::getEventVersion, event.getEventVersion())
            .eq(ProcessEvent::getLeaseEpoch, event.getLeaseEpoch())
            .eq(ProcessEvent::getOwner, event.getOwner())
            .eq(ProcessEvent::getEventStatus, EventStatus.PROCESSING)
            .gt(ProcessEvent::getLeaseUntil, LocalDateTime.now())
            .set(ProcessEvent::getEventStatus, terminalStatus)
            .set(ProcessEvent::getEffectResult, effectResult)
            .set(ProcessEvent::getOutcome, outcome(effectResult))
            .set(ProcessEvent::getWaitReason, null)
            .set(ProcessEvent::getEndedAt, now)
            .set(ProcessEvent::getLeaseUntil, now)
            .set(ProcessEvent::getEventVersion, event.getEventVersion() + 1)
            .set(ProcessEvent::getUpdateTime, now));
        if (rows != 1) {
            throw new LeaseLostException();
        }
        ProcessAttempt attempt = claim.attempt();
        attempt.setCheckpointStatus(checkpointStatus(effectResult));
        attempt.setEffectResult(effectResult);
        attempt.setEndedAt(now);
        attempt.setRecordedAt(now);
        attemptMapper.updateById(attempt);
        boolean recoveryQuery = event.getExecutionPurpose() == ExecutionPurpose.RECOVERY_ONLY
            && event.getRecoveryForEventCode() != null;
        if (!recoveryQuery) {
            releaseOrHandoffCall(event, effectResult);
        }
        if (event.getEventDirection() == com.innolux.iws.process.enums.EventDirection.INBOUND
            && event.getOriginOutboundEventId() != null) {
            releaseCall(event.getOriginOutboundEventId());
        }
        event.setEventStatus(terminalStatus);
        event.setEffectResult(effectResult);
        event.setOutcome(outcome(effectResult));
        event.setEventVersion(event.getEventVersion() + 1);
        claim.advance(event.getEventVersion());
        audit(ActionType.EVENT_EXECUTE, event, attempt, AuditResult.SUCCESS, "FINISH");
        if (recoveryQuery) {
            recoveryEvidenceService.consumeQueryEvidence(claim, effectResult);
        } else {
            activationService.activateSuccessors(event);
        }
        return true;
    }

    private void validateTarget(ProcessEvent event, EffectResult effectResult) {
        var state = runtimeDao.graph(event.getDefinitionVersionId()).state(event.getTargetStateCode());
        if (state == null || state.getValidatorKey() == null || state.getValidatorKey().isBlank()) {
            throw new TargetValidationException("目标状态缺少校验器: " + event.getTargetStateCode());
        }
        ProcessSystemContext context = runtimeDao.systemContext(event.getInstanceId(), event.getSystemCode());
        StateContext stateContext = new StateContext(event.getInstanceId(), event.getTokenId(), event.getId(),
            event.getSourceStateCode(), event.getTargetStateCode(),
            context == null ? com.innolux.iws.process.enums.PostingMode.AUTOMATIC : context.getPostingMode(),
            event.getInputHash(), effectResult.name(), java.util.Map.of());
        StateValidator validator = capabilityRegistry.validator(state.getValidatorKey(), state.getValidatorVersion());
        ValidationResult result = (effectResult == EffectResult.SKIPPED || effectResult == EffectResult.SKIPPED_MODE)
            ? validator.validateStructural(stateContext) : validator.validateBusiness(stateContext);
        if (result == null || !result.passed()) {
            throw new TargetValidationException(result == null ? "目标校验器返回空" : result.message());
        }
    }

    /**
     * 在同一数据库事务执行唯一本地动作、效果检查点、事件终态和后继激活。
     *
     * <p>LocalAction 必须使用 {@code SAME_TX} 事务模式；动作内不得发送 HTTP。任一写入失败时
     * 业务效果、检查点和 token 一起回滚，不能出现本地效果已提交而检查点缺失。</p>
     *
     * @param claim 认领上下文
     * @param action 本地动作
     * @param context 只读上下文
     * @return 尝试记录
     */
    @Transactional(rollbackFor = Exception.class)
    public ProcessAttempt executeLocalAndFinish(ClaimContext claim, LocalAction action, ActionContext context) {
        requireOwned(claim);
        ActionResult result = action.execute(context);
        ProcessAttempt attempt = recordResult(claim, result);
        if (result.effectResult() == EffectResult.SUCCEEDED) {
            finishSuccess(claim, EffectResult.SUCCEEDED);
        } else if (result.effectResult() == EffectResult.UNKNOWN) {
            fail(claim, EffectResult.UNKNOWN, "LOCAL_EFFECT_UNKNOWN", result.message());
        } else {
            fail(claim, EffectResult.FAILED, "LOCAL_ACTION_FAILED", result.message());
        }
        return attempt;
    }

    /**
     * 记录失败并设置人工/退避门禁。
     *
     * @param claim 认领上下文
     * @param effectResult 效果结果
     * @param code 错误码
     * @param message 错误说明
     * @return 更新后的事件
     */
    @Transactional(rollbackFor = Exception.class)
    public ProcessEvent fail(ClaimContext claim, EffectResult effectResult, String code, String message) {
        ProcessEvent event = requireOwned(claim);
        LocalDateTime now = LocalDateTime.now();
        ProcessAttempt attempt = attemptMapper.selectById(claim.attempt().getId());
        boolean completed = RecoveryPolicy.decide(attempt, Boolean.TRUE.equals(event.getSent()))
            == RecoveryPolicy.Decision.COMPLETE;
        boolean uncertain = !completed && (effectResult == EffectResult.UNKNOWN
            || (Boolean.TRUE.equals(event.getSent()) && (attempt.getEffectResult() == null
            || attempt.getCheckpointStatus() == CheckpointStatus.SEND_INTENT
            || attempt.getEffectResult() == EffectResult.UNKNOWN)));
        EffectResult persistedEffect = completed ? attempt.getEffectResult()
            : (uncertain ? EffectResult.UNKNOWN : effectResult);
        if (!completed) {
            attempt.setCheckpointStatus(uncertain ? CheckpointStatus.UNKNOWN : CheckpointStatus.FAILED);
            attempt.setEffectResult(persistedEffect);
        }
        attempt.setErrorCode(code);
        attempt.setErrorMessage(message);
        attempt.setEndedAt(now);
        attempt.setRecordedAt(now);
        attemptMapper.updateById(attempt);

        ProcessInstance instance = runtimeDao.instance(event.getInstanceId());
        PlanDefinition definition = runtimeDao.definition(event.getDefinitionVersionId());
        boolean unknown = uncertain;
        boolean automatic = runtimeDao.systemContext(event.getInstanceId(), event.getSystemCode()) != null
            && runtimeDao.systemContext(event.getInstanceId(), event.getSystemCode()).getPostingMode()
            == com.innolux.iws.process.enums.PostingMode.AUTOMATIC;
        int retryMax = definition.getRetryMax() == null ? 0 : definition.getRetryMax();
        int retryCount = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
        EventStatus nextStatus;
        WaitReason nextReason;
        LocalDateTime nextRetry = null;
        if (!completed && !unknown && instance.getModeSnapshot() != com.innolux.iws.process.enums.PlanMode.IMMEDIATE
            && automatic && retryCount <= retryMax && !Boolean.TRUE.equals(event.getSent())) {
            nextStatus = EventStatus.RETRY_WAIT;
            nextReason = null;
            long backoff = definition.getRetryBackoffMs() == null ? 0 : definition.getRetryBackoffMs();
            nextRetry = now.plusNanos(backoff * 1_000_000L);
        } else {
            nextStatus = EventStatus.MANUAL_PENDING;
            nextReason = WaitReason.EXECUTION_FAILED;
        }
        int rows = eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, event.getId())
            .eq(ProcessEvent::getEventVersion, event.getEventVersion())
            .eq(ProcessEvent::getLeaseEpoch, event.getLeaseEpoch())
            .eq(ProcessEvent::getOwner, event.getOwner())
            .eq(ProcessEvent::getEventStatus, EventStatus.PROCESSING)
            .gt(ProcessEvent::getLeaseUntil, LocalDateTime.now())
            .set(ProcessEvent::getEventStatus, nextStatus)
            .set(ProcessEvent::getWaitReason, nextReason)
            .set(ProcessEvent::getEffectResult, persistedEffect)
            .set(ProcessEvent::getRetryCount, retryCount)
            .set(ProcessEvent::getNextRetryTime, nextRetry)
            .set(ProcessEvent::getErrorCode, code)
            .set(ProcessEvent::getErrorMessage, message)
            .set(ProcessEvent::getLeaseUntil, now)
            .set(ProcessEvent::getEventVersion, event.getEventVersion() + 1)
            .set(ProcessEvent::getUpdateTime, now));
        if (rows != 1) {
            throw new LeaseLostException();
        }
        ProcessToken token = runtimeDao.token(event.getTokenId());
        tokenMapper.update(null, Wrappers.<ProcessToken>lambdaUpdate()
            .eq(ProcessToken::getId, token.getId())
            .eq(ProcessToken::getTokenVersion, token.getTokenVersion())
            .set(ProcessToken::getTokenStatus, TokenStatus.BLOCKED)
            .set(ProcessToken::getBlockedEventId, event.getId())
            .set(ProcessToken::getLastAttemptId, attempt.getId())
            .set(ProcessToken::getTokenVersion, token.getTokenVersion() + 1)
            .set(ProcessToken::getUpdateTime, now));
        PlanStatus planStatus = unknown ? PlanStatus.EXCEPTION_HOLD : PlanStatus.MANUAL_PENDING;
        instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, instance.getId())
            .eq(ProcessInstance::getInstanceVersion, instance.getInstanceVersion())
            .set(ProcessInstance::getPlanStatus, planStatus)
            .set(ProcessInstance::getExceptionSummary, message)
            .set(ProcessInstance::getInstanceVersion, instance.getInstanceVersion() + 1)
            .set(ProcessInstance::getUpdateTime, now));
        if (unknown || completed) {
            holdCall(event);
        } else {
            releaseCall(event);
        }
        event.setEventStatus(nextStatus);
        event.setEffectResult(persistedEffect);
        event.setRetryCount(retryCount);
        event.setEventVersion(event.getEventVersion() + 1);
        claim.advance(event.getEventVersion());
        audit(ActionType.EVENT_EXECUTE, event, attempt, unknown ? AuditResult.UNKNOWN : AuditResult.FAILED, message);
        return event;
    }

    /**
     * 无容量时零发送退回等待，不增加失败重试次数。
     *
     * @param claim 认领上下文
     */
    @Transactional(rollbackFor = Exception.class)
    public void requeueForCapacity(ClaimContext claim) {
        ProcessEvent event = requireOwned(claim);
        ProcessAttempt attempt = claim.attempt();
        attempt.setCheckpointStatus(CheckpointStatus.NO_EFFECT);
        attempt.setEffectResult(EffectResult.NO_EFFECT);
        attempt.setSent(Boolean.FALSE);
        attempt.setErrorMessage("CALL 容量不足，零发送");
        attempt.setRecordedAt(LocalDateTime.now());
        attemptMapper.updateById(attempt);
        int rows = eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, event.getId())
            .eq(ProcessEvent::getEventVersion, event.getEventVersion())
            .eq(ProcessEvent::getLeaseEpoch, event.getLeaseEpoch())
            .eq(ProcessEvent::getOwner, event.getOwner())
            .eq(ProcessEvent::getEventStatus, EventStatus.PROCESSING)
            .set(ProcessEvent::getEventStatus, EventStatus.PENDING)
            .set(ProcessEvent::getWaitReason, WaitReason.RESOURCE)
            .set(ProcessEvent::getOwner, null)
            .set(ProcessEvent::getLeaseUntil, LocalDateTime.now())
            .set(ProcessEvent::getEventVersion, event.getEventVersion() + 1)
            .set(ProcessEvent::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new LeaseLostException();
        }
        claim.advance(event.getEventVersion() + 1);
        audit(ActionType.EVENT_EXECUTE, event, attempt, AuditResult.REJECTED, "CAPACITY_WAIT");
    }

    /**
     * 验证认领上下文仍是当前有效代次。
     *
     * @param claim 认领上下文
     * @return 当前事件
     */
    public ProcessEvent requireOwned(ClaimContext claim) {
        ProcessEvent event = eventMapper.selectById(claim.event().getId());
        if (event == null || event.getEventStatus() != EventStatus.PROCESSING
            || !claim.attempt().getLeaseEpoch().equals(event.getLeaseEpoch())
            || !claim.attempt().getOwner().equals(event.getOwner())
            || event.getLeaseUntil() == null || !event.getLeaseUntil().isAfter(LocalDateTime.now())
            || event.getEventVersion() == null || event.getEventVersion() != claim.eventVersion()) {
            throw new LeaseLostException();
        }
        ProcessAttempt attempt = attemptMapper.selectById(claim.attempt().getId());
        if (attempt == null || !attempt.getLeaseEpoch().equals(event.getLeaseEpoch())
            || !attempt.getOwner().equals(event.getOwner())) {
            throw new LeaseLostException();
        }
        return event;
    }

    /**
     * 回收过期 PROCESSING 租约，按检查点分类安全重排队或进入未知停留。
     */
    @Transactional(rollbackFor = Exception.class)
    public void recoverExpiredLeases() {
        LocalDateTime now = LocalDateTime.now();
        java.util.List<ProcessEvent> expired = eventMapper.selectList(Wrappers.<ProcessEvent>lambdaQuery()
            .eq(ProcessEvent::getEventStatus, EventStatus.PROCESSING)
            .lt(ProcessEvent::getLeaseUntil, now));
        for (ProcessEvent event : expired) {
            ProcessAttempt attempt = event.getLastAttemptId() == null ? null
                : attemptMapper.selectById(event.getLastAttemptId());
            boolean sent = Boolean.TRUE.equals(event.getSent())
                || (attempt != null && attempt.getCheckpointStatus() == CheckpointStatus.SEND_INTENT);
            RecoveryPolicy.Decision decision = RecoveryPolicy.decide(attempt, sent);
            ProcessInstance instance = runtimeDao.instance(event.getInstanceId());
            PlanDefinition definition = runtimeDao.definition(event.getDefinitionVersionId());
            ProcessSystemContext modeContext = runtimeDao.systemContext(event.getInstanceId(), event.getSystemCode());
            com.innolux.iws.process.enums.PostingMode mode = modeContext == null
                ? com.innolux.iws.process.enums.PostingMode.AUTOMATIC : modeContext.getPostingMode();
            if (decision == RecoveryPolicy.Decision.UNKNOWN) {
                recoverUnknown(event, attempt, now);
            } else if (instance.getModeSnapshot() == com.innolux.iws.process.enums.PlanMode.IMMEDIATE
                || mode == com.innolux.iws.process.enums.PostingMode.MANUAL_CONFIRM) {
                recoverManual(event, attempt, now, "租约过期，按人工模式停留");
            } else if (decision == RecoveryPolicy.Decision.COMPLETE) {
                recoverComplete(event, attempt, now, definition);
            } else {
                recoverSafe(event, attempt, now);
            }
        }
    }

    private void recoverUnknown(ProcessEvent event, ProcessAttempt attempt, LocalDateTime now) {
        int rows = eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, event.getId())
            .eq(ProcessEvent::getEventVersion, event.getEventVersion())
            .eq(ProcessEvent::getLeaseEpoch, event.getLeaseEpoch())
            .eq(ProcessEvent::getEventStatus, EventStatus.PROCESSING)
            .set(ProcessEvent::getEventStatus, EventStatus.MANUAL_PENDING)
            .set(ProcessEvent::getWaitReason, WaitReason.RECOVERY_PENDING)
            .set(ProcessEvent::getEffectResult, EffectResult.UNKNOWN)
            .set(ProcessEvent::getOwner, null)
            .set(ProcessEvent::getLeaseUntil, now)
            .set(ProcessEvent::getLeaseEpoch, (event.getLeaseEpoch() == null ? 0 : event.getLeaseEpoch()) + 1)
            .set(ProcessEvent::getEventVersion, event.getEventVersion() + 1)
            .set(ProcessEvent::getUpdateTime, now));
        if (rows != 1) {
            return;
        }
        ProcessToken token = runtimeDao.token(event.getTokenId());
        tokenMapper.update(null, Wrappers.<ProcessToken>lambdaUpdate()
            .eq(ProcessToken::getId, token.getId())
            .eq(ProcessToken::getTokenVersion, token.getTokenVersion())
            .set(ProcessToken::getTokenStatus, TokenStatus.BLOCKED)
            .set(ProcessToken::getBlockedEventId, event.getId())
            .set(ProcessToken::getTokenVersion, token.getTokenVersion() + 1)
            .set(ProcessToken::getUpdateTime, now));
        instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, event.getInstanceId())
            .set(ProcessInstance::getPlanStatus, PlanStatus.EXCEPTION_HOLD)
            .set(ProcessInstance::getExceptionSummary, "租约过期且结果未知，待对账")
            .set(ProcessInstance::getInstanceVersion,
                runtimeDao.instance(event.getInstanceId()).getInstanceVersion() + 1)
            .set(ProcessInstance::getUpdateTime, now));
        audit(ActionType.EVENT_EXECUTE, event, attempt, AuditResult.UNKNOWN, "LEASE_EXPIRED_UNKNOWN");
    }

    private void recoverSafe(ProcessEvent event, ProcessAttempt attempt, LocalDateTime now) {
        int rows = eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, event.getId())
            .eq(ProcessEvent::getEventVersion, event.getEventVersion())
            .eq(ProcessEvent::getLeaseEpoch, event.getLeaseEpoch())
            .eq(ProcessEvent::getEventStatus, EventStatus.PROCESSING)
            .set(ProcessEvent::getEventStatus, EventStatus.PENDING)
            .set(ProcessEvent::getWaitReason, null)
            .set(ProcessEvent::getOwner, null)
            .set(ProcessEvent::getLeaseUntil, now)
            .set(ProcessEvent::getLeaseEpoch, (event.getLeaseEpoch() == null ? 0 : event.getLeaseEpoch()) + 1)
            .set(ProcessEvent::getEventVersion, event.getEventVersion() + 1)
            .set(ProcessEvent::getUpdateTime, now));
        if (rows != 1) {
            return;
        }
        ProcessToken token = runtimeDao.token(event.getTokenId());
        tokenMapper.update(null, Wrappers.<ProcessToken>lambdaUpdate()
            .eq(ProcessToken::getId, token.getId())
            .eq(ProcessToken::getTokenVersion, token.getTokenVersion())
            .set(ProcessToken::getTokenStatus, TokenStatus.ACTIVE)
            .set(ProcessToken::getBlockedEventId, null)
            .set(ProcessToken::getTokenVersion, token.getTokenVersion() + 1)
            .set(ProcessToken::getUpdateTime, now));
        audit(ActionType.EVENT_EXECUTE, event, attempt, AuditResult.SUCCESS, "LEASE_EXPIRED_REQUEUE");
    }

    private void recoverComplete(ProcessEvent event, ProcessAttempt attempt, LocalDateTime now,
                                 PlanDefinition definition) {
        Long budget = definition.getMaxExecutionMs();
        if (budget == null || budget <= 0) {
            recoverManual(event, attempt, now, "成功检查点存在但缺少恢复预算");
            return;
        }
        int rows = eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, event.getId())
            .eq(ProcessEvent::getEventVersion, event.getEventVersion())
            .eq(ProcessEvent::getLeaseEpoch, event.getLeaseEpoch())
            .eq(ProcessEvent::getEventStatus, EventStatus.PROCESSING)
            .set(ProcessEvent::getEventStatus, EventStatus.PENDING)
            .set(ProcessEvent::getExecutionPurpose, ExecutionPurpose.RECOVERY_ONLY)
            .set(ProcessEvent::getRecoveryRequested, Boolean.TRUE)
            .set(ProcessEvent::getRecoveryDeadline, now.plusNanos(budget * 1_000_000L))
            .set(ProcessEvent::getEffectResult, attempt == null ? null : attempt.getEffectResult())
            .set(ProcessEvent::getOwner, null)
            .set(ProcessEvent::getLeaseUntil, now)
            .set(ProcessEvent::getLeaseEpoch, (event.getLeaseEpoch() == null ? 0 : event.getLeaseEpoch()) + 1)
            .set(ProcessEvent::getEventVersion, event.getEventVersion() + 1)
            .set(ProcessEvent::getUpdateTime, now));
        if (rows != 1) {
            return;
        }
        ProcessToken token = runtimeDao.token(event.getTokenId());
        tokenMapper.update(null, Wrappers.<ProcessToken>lambdaUpdate()
            .eq(ProcessToken::getId, token.getId())
            .eq(ProcessToken::getTokenVersion, token.getTokenVersion())
            .set(ProcessToken::getTokenStatus, TokenStatus.ACTIVE)
            .set(ProcessToken::getBlockedEventId, null)
            .set(ProcessToken::getTokenVersion, token.getTokenVersion() + 1)
            .set(ProcessToken::getUpdateTime, now));
        audit(ActionType.EVENT_EXECUTE, event, attempt, AuditResult.SUCCESS, "LEASE_EXPIRED_COMPLETE_RECOVERY");
    }

    private void recoverManual(ProcessEvent event, ProcessAttempt attempt, LocalDateTime now, String reason) {
        int rows = eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, event.getId())
            .eq(ProcessEvent::getEventVersion, event.getEventVersion())
            .eq(ProcessEvent::getLeaseEpoch, event.getLeaseEpoch())
            .eq(ProcessEvent::getEventStatus, EventStatus.PROCESSING)
            .set(ProcessEvent::getEventStatus, EventStatus.MANUAL_PENDING)
            .set(ProcessEvent::getWaitReason, WaitReason.EXECUTION_FAILED)
            .set(ProcessEvent::getEffectResult, attempt == null ? null : attempt.getEffectResult())
            .set(ProcessEvent::getOwner, null)
            .set(ProcessEvent::getLeaseUntil, now)
            .set(ProcessEvent::getLeaseEpoch, (event.getLeaseEpoch() == null ? 0 : event.getLeaseEpoch()) + 1)
            .set(ProcessEvent::getEventVersion, event.getEventVersion() + 1)
            .set(ProcessEvent::getUpdateTime, now));
        if (rows != 1) {
            return;
        }
        ProcessToken token = runtimeDao.token(event.getTokenId());
        tokenMapper.update(null, Wrappers.<ProcessToken>lambdaUpdate()
            .eq(ProcessToken::getId, token.getId())
            .eq(ProcessToken::getTokenVersion, token.getTokenVersion())
            .set(ProcessToken::getTokenStatus, TokenStatus.BLOCKED)
            .set(ProcessToken::getBlockedEventId, event.getId())
            .set(ProcessToken::getTokenVersion, token.getTokenVersion() + 1)
            .set(ProcessToken::getUpdateTime, now));
        instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, event.getInstanceId())
            .set(ProcessInstance::getPlanStatus, PlanStatus.MANUAL_PENDING)
            .set(ProcessInstance::getExceptionSummary, reason)
            .set(ProcessInstance::getInstanceVersion,
                runtimeDao.instance(event.getInstanceId()).getInstanceVersion() + 1)
            .set(ProcessInstance::getUpdateTime, now));
        audit(ActionType.EVENT_EXECUTE, event, attempt, AuditResult.FAILED, reason);
    }

    private void releaseOrHandoffCall(ProcessEvent event, EffectResult effectResult) {
        ProcessScheduleControl control = findCall(event.getId());
        if (control == null || control.getPermitState() == PermitState.RELEASED) {
            return;
        }
        if (effectResult == EffectResult.ACCEPTED) {
            controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
                .eq(ProcessScheduleControl::getId, control.getId())
                .eq(ProcessScheduleControl::getRevision, control.getRevision())
                .set(ProcessScheduleControl::getPermitState, PermitState.WAIT_RESULT)
                .set(ProcessScheduleControl::getRevision, control.getRevision() + 1)
                .set(ProcessScheduleControl::getLastActivityAt, LocalDateTime.now()));
        } else {
            releaseCall(event);
        }
    }

    private void releaseCall(ProcessEvent event) {
        ProcessScheduleControl control = findCall(event.getId());
        if (control == null || control.getPermitState() == PermitState.RELEASED) {
            return;
        }
        controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
            .eq(ProcessScheduleControl::getId, control.getId())
            .eq(ProcessScheduleControl::getRevision, control.getRevision())
            .ne(ProcessScheduleControl::getPermitState, PermitState.RELEASED)
            .set(ProcessScheduleControl::getPermitState, PermitState.RELEASED)
            .set(ProcessScheduleControl::getRevision, control.getRevision() + 1)
            .set(ProcessScheduleControl::getLastActivityAt, LocalDateTime.now()));
    }

    private void releaseCall(long eventId) {
        ProcessScheduleControl control = findCall(eventId);
        if (control == null || control.getPermitState() == PermitState.RELEASED) {
            return;
        }
        controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
            .eq(ProcessScheduleControl::getId, control.getId())
            .eq(ProcessScheduleControl::getRevision, control.getRevision())
            .ne(ProcessScheduleControl::getPermitState, PermitState.RELEASED)
            .set(ProcessScheduleControl::getPermitState, PermitState.RELEASED)
            .set(ProcessScheduleControl::getRevision, control.getRevision() + 1)
            .set(ProcessScheduleControl::getLastActivityAt, LocalDateTime.now()));
    }

    private void holdCall(ProcessEvent event) {
        ProcessScheduleControl control = findCall(event.getId());
        if (control == null || control.getPermitState() == PermitState.RELEASED) {
            return;
        }
        controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
            .eq(ProcessScheduleControl::getId, control.getId())
            .eq(ProcessScheduleControl::getRevision, control.getRevision())
            .set(ProcessScheduleControl::getPermitState, PermitState.WAIT_RESULT)
            .set(ProcessScheduleControl::getRevision, control.getRevision() + 1)
            .set(ProcessScheduleControl::getLastActivityAt, LocalDateTime.now()));
    }

    private ProcessScheduleControl findCall(long eventId) {
        return controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.CALL)
            .eq(ProcessScheduleControl::getScopeKey, String.valueOf(eventId))
            .eq(ProcessScheduleControl::getDuty, "CALL_PERMIT"));
    }

    private CheckpointStatus checkpointStatus(EffectResult result) {
        return switch (result) {
            case SUCCEEDED -> CheckpointStatus.SUCCEEDED;
            case ACCEPTED -> CheckpointStatus.ACCEPTED;
            case SKIPPED -> CheckpointStatus.SKIPPED;
            case SKIPPED_MODE -> CheckpointStatus.SKIPPED_MODE;
            case NO_EFFECT -> CheckpointStatus.NO_EFFECT;
            case UNKNOWN -> CheckpointStatus.UNKNOWN;
            case FAILED -> CheckpointStatus.FAILED;
        };
    }

    private String outcome(EffectResult result) {
        return switch (result) {
            case SUCCEEDED -> "SUCCEEDED";
            case ACCEPTED -> "ACCEPTED";
            case SKIPPED -> "SKIPPED";
            case SKIPPED_MODE -> "SKIPPED_MODE";
            case NO_EFFECT -> "NO_EFFECT";
            case UNKNOWN -> "UNKNOWN";
            case FAILED -> "FAILED";
        };
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            return value.toString();
        }
    }

    private String mergeCheckpoint(String existing, String next) {
        if (next == null || next.isBlank()) {
            return existing;
        }
        if (existing == null || existing.isBlank()) {
            return next;
        }
        try {
            var merged = (tools.jackson.databind.node.ObjectNode) objectMapper.readTree(existing);
            var original = (tools.jackson.databind.node.ObjectNode) objectMapper.readTree(existing);
            merged.setAll((tools.jackson.databind.node.ObjectNode) objectMapper.readTree(next));
            if (original.has("sendIntent")) {
                merged.set("sendIntent", original.get("sendIntent"));
            }
            return objectMapper.writeValueAsString(merged);
        } catch (JacksonException | ClassCastException ex) {
            return next;
        }
    }

    private void audit(ActionType action, ProcessEvent event, ProcessAttempt attempt, AuditResult result,
                       String reason) {
        ProcessActionAudit audit = auditService.newAudit(action, ActionSource.SCHEDULER, result);
        audit.setInstanceId(event.getInstanceId());
        audit.setTokenId(event.getTokenId());
        audit.setEventId(event.getId());
        audit.setAttemptId(attempt == null ? null : attempt.getId());
        audit.setDefinitionVersionId(event.getDefinitionVersionId());
        audit.setAfterStatus(event.getEventStatus() == null ? null : event.getEventStatus().name());
        audit.setAfterVersion(event.getEventVersion());
        audit.setEpoch(event.getLeaseEpoch());
        audit.setReason(reason);
        auditService.append(audit);
    }

    /**
     * 认领上下文。
     *
     * @param event 认领时事件快照
     * @param attempt 新尝试
     */
    public static final class ClaimContext {

        private final ProcessEvent event;

        private final ProcessAttempt attempt;

        private long eventVersion;

        private final LocalDateTime leaseUntil;

        /**
         * 构造认领上下文。
         *
         * @param event 事件快照
         * @param attempt 尝试
         */
        public ClaimContext(ProcessEvent event, ProcessAttempt attempt) {
            this.event = event;
            this.attempt = attempt;
            this.eventVersion = event.getEventVersion();
            this.leaseUntil = event.getLeaseUntil();
        }

        /**
         * 事件快照。
         *
         * @return 事件
         */
        public ProcessEvent event() {
            return event;
        }

        /**
         * 本次尝试。
         *
         * @return 尝试
         */
        public ProcessAttempt attempt() {
            return attempt;
        }

        /**
         * 有效事件版本。
         *
         * @return 版本
         */
        public long eventVersion() {
            return eventVersion;
        }

        /**
         * 认领租约截止。
         *
         * @return 截止时间
         */
        public LocalDateTime leaseUntil() {
            return leaseUntil;
        }

        /**
         * 推进上下文版本。
         *
         * @param newVersion 新版本
         */
        public void advance(long newVersion) {
            this.eventVersion = newVersion;
            event.setEventVersion(newVersion);
        }
    }

    /**
     * 租约或版本竞争失败。
     */
    public static class LeaseLostException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * 构造异常。
         */
        public LeaseLostException() {
            super("LEASE_LOST");
        }
    }

    /**
     * 发送准备被取消或期限门禁阻断。
     */
    public static class SendBlockedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String code;

        /**
         * 构造异常。
         *
         * @param code 错误码
         * @param message 说明
         */
        public SendBlockedException(String code, String message) {
            super(message);
            this.code = code;
        }

        /**
         * 错误码。
         *
         * @return 错误码
         */
        public String code() {
            return code;
        }
    }

    /**
     * CALL 容量不足，T2 必须零发送。
     */
    public static class CapacityUnavailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * 构造异常。
         */
        public CapacityUnavailableException() {
            super("CALL_CAPACITY_UNAVAILABLE");
        }
    }

    /**
     * 目标状态校验失败，动作结果不得提交为成功。
     */
    public static class TargetValidationException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * 构造异常。
         *
         * @param message 校验说明
         */
        public TargetValidationException(String message) {
            super(message);
        }
    }
}
