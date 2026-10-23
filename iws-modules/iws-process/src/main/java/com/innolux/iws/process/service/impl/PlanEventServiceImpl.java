/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.2
 * @Description  : 事件恢复与终止命令实现；
 * @ModifyRecord : V1.0 新建；
 *                 V1.1 增加尝试分页查询；
 *                 V1.2 QUERY 恢复改用共享只读准入；
 */

package com.innolux.iws.process.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.api.AttemptVO;
import com.innolux.iws.process.api.CommandResult;
import com.innolux.iws.process.api.EventCommand;
import com.innolux.iws.process.api.RetryEventCommand;
import com.innolux.iws.process.domain.ProcessActionAudit;
import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessToken;
import com.innolux.iws.process.enums.ActionSource;
import com.innolux.iws.process.enums.ActionType;
import com.innolux.iws.process.enums.AuditResult;
import com.innolux.iws.process.enums.CheckpointStatus;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.ExecutionPurpose;
import com.innolux.iws.process.enums.TokenStatus;
import com.innolux.iws.process.enums.WaitReason;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import com.innolux.iws.process.runtime.EventEligibilityService;
import com.innolux.iws.process.runtime.PlanActivationService;
import com.innolux.iws.process.runtime.QueryRecoveryAdmission;
import com.innolux.iws.process.runtime.RecoveryPolicy;
import com.innolux.iws.process.runtime.RuntimeDao;
import com.innolux.iws.process.runtime.ProcessObjectAuthorizer;
import com.innolux.iws.process.service.CommandReceiptService;
import com.innolux.iws.process.service.PlanEventService;
import com.innolux.iws.process.service.ProcessAuditService;
import com.innolux.iws.process.support.ProcessText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 事件命令实现。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class PlanEventServiceImpl implements PlanEventService {

    private static final int DEFAULT_PAGE_NUM = 1;

    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final int MAX_PAGE_SIZE = 200;

    private static final Set<EventStatus> TERMINAL = Set.of(EventStatus.SUCCESS, EventStatus.SKIPPED,
        EventStatus.NOT_SELECTED, EventStatus.FAILED_FINAL);

    private static final Set<EffectResult> SUCCESS_EFFECTS = Set.of(EffectResult.SUCCEEDED,
        EffectResult.ACCEPTED, EffectResult.SKIPPED, EffectResult.SKIPPED_MODE);

    private final RuntimeDao runtimeDao;

    private final ProcessEventMapper eventMapper;

    private final ProcessAttemptMapper attemptMapper;

    private final ProcessTokenMapper tokenMapper;

    private final EventEligibilityService eligibilityService;

    private final PlanActivationService activationService;

    private final ProcessAuditService auditService;

    private final CommandReceiptService commandReceiptService;

    private final ProcessObjectAuthorizer objectAuthorizer;

    private final QueryRecoveryAdmission queryRecoveryAdmission;

    /**
     * 构造服务。
     *
     * @param runtimeDao 运行时 DAO
     * @param eventMapper 事件 Mapper
     * @param attemptMapper 尝试 Mapper
     * @param tokenMapper token Mapper
     * @param eligibilityService 资格服务
     * @param activationService 激活服务
     * @param auditService 审计服务
     * @param commandReceiptService 回执服务
     * @param objectAuthorizer 对象授权
     * @param queryRecoveryAdmission QUERY 恢复只读准入
     */
    public PlanEventServiceImpl(RuntimeDao runtimeDao, ProcessEventMapper eventMapper,
                                ProcessAttemptMapper attemptMapper, ProcessTokenMapper tokenMapper,
                                EventEligibilityService eligibilityService, PlanActivationService activationService,
                                ProcessAuditService auditService, CommandReceiptService commandReceiptService,
                                ProcessObjectAuthorizer objectAuthorizer,
                                QueryRecoveryAdmission queryRecoveryAdmission) {
        this.runtimeDao = runtimeDao;
        this.eventMapper = eventMapper;
        this.attemptMapper = attemptMapper;
        this.tokenMapper = tokenMapper;
        this.eligibilityService = eligibilityService;
        this.activationService = activationService;
        this.auditService = auditService;
        this.commandReceiptService = commandReceiptService;
        this.objectAuthorizer = objectAuthorizer;
        this.queryRecoveryAdmission = queryRecoveryAdmission;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommandResult retry(RetryEventCommand command) {
        objectAuthorizer.authorizeEvent(command.eventId(), "RETRY");
        String payload = commandReceiptService.payloadHash(command);
        if (replay(ActionType.EVENT_RETRY, command.eventId(), command.commandKey(), payload)) {
            return CommandResult.success(String.valueOf(command.eventId()), null, List.of());
        }
        ProcessEvent event = requireEvent(command.eventId(), command.expectedVersion());
        if (TERMINAL.contains(event.getEventStatus()) || event.getEventStatus() == EventStatus.PROCESSING) {
            throw new ServiceException("事件终态或正在执行，不能重试");
        }
        ProcessAttempt attempt = lastAttempt(event);
        if ((hasSuccessfulEffect(attempt) || hasSuccessfulEffect(event.getEffectResult()))
            && !reusableSuccess(event, attempt)) {
            throw new ServiceException("成功效果缺少可复用检查点，必须先对账");
        }
        EffectResult reusableEffect = reusableSuccess(event, attempt) ? attempt.getEffectResult() : null;
        if (command.recoveryOnly() && reusableEffect == null) {
            if (!isUnknown(event, attempt)) {
                throw new ServiceException("recoveryOnly 仅适用于结果未知事件");
            }
            QueryRecoveryAdmission.Admission admission = queryRecoveryAdmission.evaluate(event, attempt);
            if (!admission.allowed()) {
                throw new ServiceException(admission.reason());
            }
            ProcessEvent query = admission.queryEvent();
            long budget = admission.budgetMs();
            int queryRows = eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
                .eq(ProcessEvent::getId, query.getId())
                .eq(ProcessEvent::getEventVersion, query.getEventVersion())
                .set(ProcessEvent::getEventStatus, EventStatus.PENDING)
                .set(ProcessEvent::getWaitReason, null)
                .set(ProcessEvent::getExecutionPurpose, ExecutionPurpose.RECOVERY_ONLY)
                .set(ProcessEvent::getRecoveryRequested, Boolean.TRUE)
                .set(ProcessEvent::getRecoveryDeadline, LocalDateTime.now().plusNanos(budget * 1_000_000L))
                .set(ProcessEvent::getOriginOutboundEventId, event.getId())
                .set(ProcessEvent::getRecoveryForEventCode, event.getEventCode())
                .set(ProcessEvent::getInputHash, event.getInputHash())
                .set(ProcessEvent::getEventVersion, query.getEventVersion() + 1)
                .set(ProcessEvent::getUpdateTime, LocalDateTime.now()));
            if (queryRows != 1) {
                throw new ServiceException("QUERY 恢复事件版本冲突");
            }
            eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
                .eq(ProcessEvent::getId, event.getId())
                .eq(ProcessEvent::getEventVersion, command.expectedVersion())
                .set(ProcessEvent::getRecoveryRequested, Boolean.TRUE)
                .set(ProcessEvent::getRecoveryDeadline, LocalDateTime.now().plusNanos(budget * 1_000_000L))
                .set(ProcessEvent::getOwner, null)
                .set(ProcessEvent::getLeaseUntil, LocalDateTime.now())
                .set(ProcessEvent::getLeaseEpoch,
                    (event.getLeaseEpoch() == null ? 0 : event.getLeaseEpoch()) + 1)
                .set(ProcessEvent::getEventVersion, command.expectedVersion() + 1)
                .set(ProcessEvent::getUpdateTime, LocalDateTime.now()));
            ProcessToken queryToken = runtimeDao.token(query.getTokenId());
            tokenMapper.update(null, Wrappers.<ProcessToken>lambdaUpdate()
                .eq(ProcessToken::getId, queryToken.getId())
                .eq(ProcessToken::getTokenVersion, queryToken.getTokenVersion())
                .set(ProcessToken::getTokenStatus, TokenStatus.ACTIVE)
                .set(ProcessToken::getBlockedEventId, null)
                .set(ProcessToken::getTokenVersion, queryToken.getTokenVersion() + 1)
                .set(ProcessToken::getUpdateTime, LocalDateTime.now()));
            audit(ActionType.EVENT_RETRY, query, command.commandKey(), payload, "激活对账 QUERY");
            return CommandResult.success(String.valueOf(query.getId()),
                String.valueOf(query.getEventVersion() + 1), List.of());
        }
        if (isUnknown(event, attempt)) {
            throw new ServiceException("UNKNOWN 事件必须先对账或走已发布 QUERY 恢复分支，禁止盲目重试");
        }
        LocalDateTime recoveryDeadline = null;
        if (reusableEffect != null) {
            Long budget = runtimeDao.definition(event.getDefinitionVersionId()).getMaxExecutionMs();
            if (budget == null || budget <= 0) {
                throw new ServiceException("检查点收口缺少有限 maxExecutionMs 恢复预算");
            }
            recoveryDeadline = LocalDateTime.now().plusNanos(budget * 1_000_000L);
        }
        int rows = eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, event.getId())
            .eq(ProcessEvent::getEventVersion, command.expectedVersion())
            .in(ProcessEvent::getEventStatus, reusableEffect == null
                ? List.of(EventStatus.MANUAL_PENDING, EventStatus.RETRY_WAIT)
                : List.of(EventStatus.MANUAL_PENDING, EventStatus.RETRY_WAIT, EventStatus.PENDING))
            .set(ProcessEvent::getEventStatus, EventStatus.PENDING)
            .set(reusableEffect != null, ProcessEvent::getEffectResult, reusableEffect)
            .set(reusableEffect != null, ProcessEvent::getOutcome, null)
            .set(reusableEffect != null, ProcessEvent::getExecutionPurpose, ExecutionPurpose.RECOVERY_ONLY)
            .set(reusableEffect != null, ProcessEvent::getRecoveryDeadline, recoveryDeadline)
            .set(ProcessEvent::getWaitReason, null)
            .set(ProcessEvent::getRecoveryRequested, Boolean.TRUE)
            .set(ProcessEvent::getNextRetryTime, null)
            .set(ProcessEvent::getErrorCode, null)
            .set(ProcessEvent::getErrorMessage, null)
            .set(ProcessEvent::getEventVersion, command.expectedVersion() + 1)
            .set(ProcessEvent::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new ServiceException("事件版本冲突，请重新读取");
        }
        ProcessToken token = runtimeDao.token(event.getTokenId());
        tokenMapper.update(null, Wrappers.<ProcessToken>lambdaUpdate()
            .eq(ProcessToken::getId, token.getId())
            .eq(ProcessToken::getTokenVersion, token.getTokenVersion())
            .set(ProcessToken::getTokenStatus, TokenStatus.ACTIVE)
            .set(ProcessToken::getBlockedEventId, null)
            .set(ProcessToken::getTokenVersion, token.getTokenVersion() + 1)
            .set(ProcessToken::getUpdateTime, LocalDateTime.now()));
        activationService.updatePlanStatus(event.getInstanceId());
        audit(ActionType.EVENT_RETRY, event, command.commandKey(), payload, "事件重试已排队");
        return CommandResult.success(String.valueOf(event.getId()),
            String.valueOf(command.expectedVersion() + 1), List.of());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommandResult finalizeEvent(EventCommand command) {
        objectAuthorizer.authorizeEvent(command.eventId(), "FINALIZE");
        String payload = commandReceiptService.payloadHash(command);
        if (replay(ActionType.EVENT_FINALIZE, command.eventId(), command.commandKey(), payload)) {
            return CommandResult.success(String.valueOf(command.eventId()), null, List.of());
        }
        ProcessEvent event = requireEvent(command.eventId(), command.expectedVersion());
        ProcessAttempt attempt = lastAttempt(event);
        if (event.getEventStatus() == EventStatus.PROCESSING || isUnknown(event, attempt)) {
            throw new ServiceException("存在活动租约或未知副作用，不能最终失败");
        }
        if (event.getEventStatus() != EventStatus.MANUAL_PENDING
            && event.getEventStatus() != EventStatus.RETRY_WAIT) {
            throw new ServiceException("只有待人工/待重试事件可最终失败");
        }
        if (hasSuccessfulEffect(attempt) || hasSuccessfulEffect(event.getEffectResult())) {
            throw new ServiceException(reusableSuccess(event, attempt)
                ? "已存在成功检查点，不能最终失败；请显式重试复用检查点"
                : "已存在成功效果但缺少可复用检查点，不能最终失败；请先对账");
        }
        int rows = eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, event.getId())
            .eq(ProcessEvent::getEventVersion, command.expectedVersion())
            .set(ProcessEvent::getEventStatus, EventStatus.FAILED_FINAL)
            .set(ProcessEvent::getWaitReason, null)
            .set(ProcessEvent::getEndedAt, LocalDateTime.now())
            .set(ProcessEvent::getEventVersion, command.expectedVersion() + 1)
            .set(ProcessEvent::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new ServiceException("事件版本冲突，请重新读取");
        }
        activationService.updatePlanStatus(event.getInstanceId());
        audit(ActionType.EVENT_FINALIZE, event, command.commandKey(), payload, "事件最终失败");
        return CommandResult.success(String.valueOf(event.getId()),
            String.valueOf(command.expectedVersion() + 1), List.of());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommandResult revalidate(EventCommand command) {
        objectAuthorizer.authorizeEvent(command.eventId(), "REVALIDATE");
        ProcessEvent event = requireEvent(command.eventId(), command.expectedVersion());
        eligibilityService.recompute(event.getId());
        ProcessEvent latest = runtimeDao.event(event.getId());
        audit(ActionType.EVENT_REVALIDATE, latest, command.commandKey(), commandReceiptService.payloadHash(command),
            "重新校验，结果=" + latest.getEventStatus());
        return new CommandResult("SUCCEEDED", "重新校验完成", String.valueOf(latest.getInstanceId()),
            String.valueOf(latest.getEventVersion()), List.of());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommandResult setPriority(EventCommand command) {
        objectAuthorizer.authorizeEvent(command.eventId(), "PRIORITY");
        if (command.priority() == null) {
            throw new ServiceException("priority 不能为空");
        }
        ProcessEvent event = requireEvent(command.eventId(), command.expectedVersion());
        if (event.getEventStatus() == EventStatus.PROCESSING || TERMINAL.contains(event.getEventStatus())) {
            throw new ServiceException("已认领或终态事件不能调整优先级");
        }
        int rows = eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, event.getId())
            .eq(ProcessEvent::getEventVersion, command.expectedVersion())
            .set(ProcessEvent::getPriority, command.priority())
            .set(ProcessEvent::getEventVersion, command.expectedVersion() + 1)
            .set(ProcessEvent::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new ServiceException("事件版本冲突，请重新读取");
        }
        audit(ActionType.EVENT_PRIORITY, event, command.commandKey(), commandReceiptService.payloadHash(command),
            "调整事件优先级");
        return CommandResult.success(String.valueOf(event.getId()),
            String.valueOf(command.expectedVersion() + 1), List.of());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PageResult<AttemptVO> attempts(long eventId, Integer pageNum, Integer pageSize) {
        objectAuthorizer.authorizeEvent(eventId, "QUERY");
        runtimeDao.event(eventId);
        long normalizedPageNum = pageNum == null || pageNum < 1 ? DEFAULT_PAGE_NUM : pageNum;
        long normalizedPageSize = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE
            : Math.min(pageSize, MAX_PAGE_SIZE);
        Page<ProcessAttempt> page = attemptMapper.selectPage(new Page<>(normalizedPageNum, normalizedPageSize),
            Wrappers.<ProcessAttempt>lambdaQuery()
                .eq(ProcessAttempt::getEventId, eventId)
                .orderByDesc(ProcessAttempt::getAttemptNo));
        return PageResult.build(page.getRecords().stream().map(this::attempt).toList(), page.getTotal());
    }

    private ProcessEvent requireEvent(long eventId, long expectedVersion) {
        ProcessEvent event = runtimeDao.event(eventId);
        if (event.getEventVersion() != expectedVersion) {
            throw new ServiceException("事件版本冲突，当前 version=" + event.getEventVersion());
        }
        return event;
    }

    private AttemptVO attempt(ProcessAttempt item) {
        return new AttemptVO(ProcessText.id(item.getId()), item.getAttemptNo(),
            item.getCheckpointStatus() == null ? null : item.getCheckpointStatus().name(), item.getSent(),
            item.getRequestId(), item.getEffectResult() == null ? null : item.getEffectResult().name(),
            item.getErrorCode(), item.getErrorMessage(), item.getEvidenceRef(),
            item.getStartedAt() == null ? null : item.getStartedAt().toString(),
            item.getEndedAt() == null ? null : item.getEndedAt().toString());
    }

    private ProcessAttempt lastAttempt(ProcessEvent event) {
        return event.getLastAttemptId() == null ? null : attemptMapper.selectById(event.getLastAttemptId());
    }

    private boolean isUnknown(ProcessEvent event, ProcessAttempt attempt) {
        if (attempt != null && attempt.getEffectResult() == EffectResult.UNKNOWN) {
            return true;
        }
        return Boolean.TRUE.equals(event.getSent()) && (attempt == null
            || attempt.getCheckpointStatus() == CheckpointStatus.SEND_INTENT
            || attempt.getCheckpointStatus() == CheckpointStatus.UNKNOWN);
    }

    private boolean hasSuccessfulEffect(ProcessAttempt attempt) {
        return attempt != null && attempt.getEffectResult() != null
            && SUCCESS_EFFECTS.contains(attempt.getEffectResult());
    }

    private boolean hasSuccessfulEffect(EffectResult effectResult) {
        return effectResult != null && SUCCESS_EFFECTS.contains(effectResult);
    }

    private boolean reusableSuccess(ProcessEvent event, ProcessAttempt attempt) {
        return hasSuccessfulEffect(attempt)
            && RecoveryPolicy.decide(attempt, Boolean.TRUE.equals(event.getSent()))
            == RecoveryPolicy.Decision.COMPLETE;
    }

    private boolean replay(ActionType action, long eventId, String commandKey, String payload) {
        ProcessActionAudit existing = commandReceiptService.find(action, "event:" + eventId, commandKey);
        if (existing == null) {
            return false;
        }
        commandReceiptService.checkSamePayload(existing, payload);
        return true;
    }

    private void audit(ActionType action, ProcessEvent event, String commandKey, String payload, String reason) {
        ProcessActionAudit audit = auditService.newAudit(action, ActionSource.USER, AuditResult.SUCCESS);
        audit.setInstanceId(event.getInstanceId());
        audit.setTokenId(event.getTokenId());
        audit.setEventId(event.getId());
        audit.setCommandKey(commandKey);
        audit.setReason(reason);
        if (commandKey != null && !commandKey.isBlank()) {
            audit.setReceiptKey(commandReceiptService.key(action, "event:" + event.getId(), commandKey));
            audit.setPayloadSummary(payload);
        }
        auditService.append(audit);
    }
}
