/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.1
 * @Description  : 实例挂载、触发、确认、控制与摘要查询；
 * @ModifyRecord : V1.0 新建；
 *                 V1.1 复用实例动作准入描述；
 */

package com.innolux.iws.process.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.api.AttachResult;
import com.innolux.iws.process.api.AttachTaskCommand;
import com.innolux.iws.process.api.CommandResult;
import com.innolux.iws.process.api.ConfirmPostingCommand;
import com.innolux.iws.process.api.EventSummaryVO;
import com.innolux.iws.process.api.InstanceSummaryVO;
import com.innolux.iws.process.api.InstanceSystemVO;
import com.innolux.iws.process.api.PlanControlCommand;
import com.innolux.iws.process.api.PriorityCommand;
import com.innolux.iws.process.api.ReceiveTriggerCommand;
import com.innolux.iws.process.api.TokenSummaryVO;
import com.innolux.iws.process.api.TriggerReceiptResult;
import com.innolux.iws.process.domain.BusinessSchedule;
import com.innolux.iws.process.domain.BusinessSchedulePlanType;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessActionAudit;
import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.domain.ProcessSystemContext;
import com.innolux.iws.process.domain.ProcessToken;
import com.innolux.iws.process.domain.ProcessTriggerReceipt;
import com.innolux.iws.process.enums.ActionSource;
import com.innolux.iws.process.enums.ActionType;
import com.innolux.iws.process.enums.AuditResult;
import com.innolux.iws.process.enums.CallPurpose;
import com.innolux.iws.process.enums.ControlState;
import com.innolux.iws.process.enums.DefinitionStatus;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.ExecutionPurpose;
import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.PlanStatus;
import com.innolux.iws.process.enums.ProcessingStatus;
import com.innolux.iws.process.enums.RoundStatus;
import com.innolux.iws.process.enums.WaitReason;
import com.innolux.iws.process.mapper.BusinessScheduleMapper;
import com.innolux.iws.process.mapper.BusinessSchedulePlanTypeMapper;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.mapper.ProcessSystemContextMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import com.innolux.iws.process.mapper.ProcessTriggerReceiptMapper;
import com.innolux.iws.process.runtime.EventEligibilityService;
import com.innolux.iws.process.runtime.PlanActivationService;
import com.innolux.iws.process.runtime.PlanInstanceActionPolicy;
import com.innolux.iws.process.runtime.PlanMaterializer;
import com.innolux.iws.process.runtime.PlanScopeResolver;
import com.innolux.iws.process.runtime.ProcessObjectAuthorizer;
import com.innolux.iws.process.runtime.RuntimeDao;
import com.innolux.iws.process.runtime.SupervisorRuntimeService;
import com.innolux.iws.process.service.CommandReceiptService;
import com.innolux.iws.process.service.PlanInstanceService;
import com.innolux.iws.process.service.ProcessAuditService;
import com.innolux.iws.process.support.DefinitionGraph;
import com.innolux.iws.process.support.ProcessText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 计划实例应用服务实现。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class PlanInstanceServiceImpl implements PlanInstanceService {

    private static final String DEFAULT_SCOPE = "DEFAULT";

    private final BusinessScheduleMapper scheduleMapper;

    private final BusinessSchedulePlanTypeMapper planTypeMapper;

    private final PlanDefinitionMapper definitionMapper;

    private final ProcessInstanceMapper instanceMapper;

    private final ProcessSystemContextMapper contextMapper;

    private final ProcessEventMapper eventMapper;

    private final ProcessTokenMapper tokenMapper;

    private final ProcessAttemptMapper attemptMapper;

    private final ProcessTriggerReceiptMapper receiptMapper;

    private final ProcessScheduleControlMapper controlMapper;

    private final RuntimeDao runtimeDao;

    private final PlanMaterializer materializer;

    private final SupervisorRuntimeService supervisorRuntimeService;

    private final EventEligibilityService eligibilityService;

    private final PlanActivationService activationService;

    private final ProcessAuditService auditService;

    private final CommandReceiptService commandReceiptService;

    private final PlanScopeResolver planScopeResolver;

    private final ProcessObjectAuthorizer objectAuthorizer;
    private final PlanInstanceActionPolicy actionPolicy;
    private final com.innolux.iws.process.mapper.ProcessRequestDetailMapper requestDetailMapper;
    private final org.springframework.beans.factory.ObjectProvider<com.innolux.iws.process.runtime.InboundIdentityPolicy> inboundIdentities;

    /**
     * 构造实例服务。
     *
     * @param scheduleMapper 业务调度 Mapper
     * @param planTypeMapper 计划类型 Mapper
     * @param definitionMapper 定义 Mapper
     * @param instanceMapper 实例 Mapper
     * @param contextMapper 系统上下文 Mapper
     * @param eventMapper 事件 Mapper
     * @param tokenMapper token Mapper
     * @param attemptMapper 尝试 Mapper
     * @param receiptMapper 回执 Mapper
     * @param controlMapper 控制 Mapper
     * @param runtimeDao 运行时 DAO
     * @param materializer 物化器
     * @param supervisorRuntimeService 监督服务
     * @param eligibilityService 资格服务
     * @param activationService 激活服务
     * @param auditService 审计服务
     * @param commandReceiptService 命令回执服务
     * @param actionPolicy 实例动作准入描述
     */
    public PlanInstanceServiceImpl(BusinessScheduleMapper scheduleMapper,
                                   BusinessSchedulePlanTypeMapper planTypeMapper,
                                   PlanDefinitionMapper definitionMapper, ProcessInstanceMapper instanceMapper,
                                   ProcessSystemContextMapper contextMapper, ProcessEventMapper eventMapper,
                                   ProcessTokenMapper tokenMapper, ProcessAttemptMapper attemptMapper,
                                   ProcessTriggerReceiptMapper receiptMapper,
                                   ProcessScheduleControlMapper controlMapper, RuntimeDao runtimeDao,
                                   PlanMaterializer materializer, SupervisorRuntimeService supervisorRuntimeService,
                                   EventEligibilityService eligibilityService,
                                   PlanActivationService activationService, ProcessAuditService auditService,
                                   CommandReceiptService commandReceiptService, PlanScopeResolver planScopeResolver,
                                   ProcessObjectAuthorizer objectAuthorizer,
                                   PlanInstanceActionPolicy actionPolicy,
                                   com.innolux.iws.process.mapper.ProcessRequestDetailMapper requestDetailMapper,
                                   org.springframework.beans.factory.ObjectProvider<com.innolux.iws.process.runtime.InboundIdentityPolicy> inboundIdentities) {
        this.scheduleMapper = scheduleMapper;
        this.planTypeMapper = planTypeMapper;
        this.definitionMapper = definitionMapper;
        this.instanceMapper = instanceMapper;
        this.contextMapper = contextMapper;
        this.eventMapper = eventMapper;
        this.tokenMapper = tokenMapper;
        this.attemptMapper = attemptMapper;
        this.receiptMapper = receiptMapper;
        this.controlMapper = controlMapper;
        this.runtimeDao = runtimeDao;
        this.materializer = materializer;
        this.supervisorRuntimeService = supervisorRuntimeService;
        this.eligibilityService = eligibilityService;
        this.activationService = activationService;
        this.auditService = auditService;
        this.commandReceiptService = commandReceiptService;
        this.planScopeResolver = planScopeResolver;
        this.objectAuthorizer = objectAuthorizer;
        this.actionPolicy = actionPolicy;
        this.requestDetailMapper = requestDetailMapper;
        this.inboundIdentities = inboundIdentities;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AttachResult attach(AttachTaskCommand command) {
        objectAuthorizer.authorizeSchedule(command.scheduleId(), "ATTACH");
        BusinessSchedule schedule = scheduleMapper.selectById(command.scheduleId());
        if (schedule == null || !Boolean.TRUE.equals(schedule.getEnabled())) {
            throw new ServiceException("业务调度不存在或已停用");
        }
        BusinessSchedulePlanType type = planTypeMapper.selectOne(Wrappers.<BusinessSchedulePlanType>lambdaQuery()
            .eq(BusinessSchedulePlanType::getScheduleId, command.scheduleId())
            .eq(BusinessSchedulePlanType::getPlanType, command.planType()));
        if (type == null || !Boolean.TRUE.equals(type.getEnabled())) {
            throw new ServiceException("计划类型未登记或已停用: " + command.planType());
        }
        List<PlanDefinition> definitions = definitionMapper.selectList(Wrappers.<PlanDefinition>lambdaQuery()
            .eq(PlanDefinition::getBusinessScheduleCode, schedule.getBusinessCode())
            .eq(PlanDefinition::getPlanType, command.planType())
            .eq(PlanDefinition::getPlanRole, PlanRole.BUSINESS)
            .eq(PlanDefinition::getStatus, DefinitionStatus.ACTIVE));
        if (definitions.size() != 1) {
            throw new ServiceException("已发布 BUSINESS 计划版本不唯一: " + definitions.size());
        }
        PlanDefinition definition = definitions.getFirst();
        String scope = planScopeResolver.resolve(command.businessType(), command.planType(),
            command.businessId(), command.taskKey());
        if (scope == null || scope.isBlank()) {
            throw new ServiceException("scope 解析结果不能为空");
        }
        ProcessInstance supervisor = supervisorRuntimeService.ensureSupervisor(command.scheduleId(),
            command.planType(), scope);
        if (supervisor.getModeSnapshot() == PlanMode.IMMEDIATE) {
            throw new ServiceException("MODE_MISMATCH: IMMEDIATE 计划必须走同步执行入口");
        }
        String businessTaskKey = command.businessType() + ":" + command.taskKey();
        ProcessInstance existing = instanceMapper.selectOne(Wrappers.<ProcessInstance>lambdaQuery()
            .eq(ProcessInstance::getBusinessTaskKey, businessTaskKey));
        if (existing != null) {
            if (!existing.getInputHash().equals(command.inputHash())) {
                throw new ServiceException("同一任务已挂载但输入摘要不同");
            }
            return attachResult(existing, false, null);
        }
        ProcessScheduleControl supervisorControl = controlMapper.selectOne(
            Wrappers.<ProcessScheduleControl>lambdaQuery()
                .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisor.getId())
                .eq(ProcessScheduleControl::getDuty, "DISPATCH"));
        boolean forcedHold = supervisorControl != null
            && supervisorControl.getControlState() == ControlState.MANUAL_PAUSED;
        boolean holdStart = command.holdStart() || forcedHold;
        ProcessInstance instance = new ProcessInstance();
        instance.setPlanRole(PlanRole.BUSINESS);
        instance.setDefinitionVersionId(definition.getId());
        instance.setDefinitionRevision(definition.getRevision());
        instance.setScheduleId(command.scheduleId());
        instance.setPlanType(command.planType());
        instance.setBusinessScheduleCode(schedule.getBusinessCode());
        instance.setSupervisorInstanceId(supervisor.getId());
        instance.setBusinessTaskKey(businessTaskKey);
        instance.setBusinessType(command.businessType());
        instance.setBusinessId(command.businessId());
        instance.setTaskKey(command.taskKey());
        instance.setScheduleScope(scope);
        instance.setModeSnapshot(supervisor.getModeSnapshot());
        instance.setHoldStart(holdStart);
        instance.setPlanStatus(PlanStatus.CREATED);
        instance.setInstancePriority(0);
        instance.setSelectedRootSet(rootSet(command, definition));
        instance.setInputHash(command.inputHash() == null ? ProcessText.sha256(businessTaskKey)
            : command.inputHash());
        instance.setRoundNo(0L);
        instance.setStopRequested(Boolean.FALSE);
        instance.setCancelRequested(Boolean.FALSE);
        instance.setInstanceVersion(1L);
        instanceMapper.insert(instance);
        materializer.materialize(instance);
        boolean admitted = false;
        if (!holdStart && definition.getActivePlanLimit() != null) {
            PlanDefinition supervisorDefinition = definitionMapper.selectById(supervisor.getDefinitionVersionId());
            admitted = supervisorRuntimeService.admit(instance, supervisor, supervisorDefinition);
            if (admitted) {
                instance.setPlanStatus(PlanStatus.RUNNING);
                instance.setFirstAdmittedAt(LocalDateTime.now());
                instance.setInstanceVersion(instance.getInstanceVersion() + 1);
            }
        }
        audit(ActionType.ATTACH, instance.getId(), command.commandKey(), command.toString(), "实例挂载");
        return attachResult(instance, true, holdStart ? "HELD_BEFORE_START"
            : (admitted ? "RUNNING" : "WAITING_PLAN_SLOT"));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TriggerReceiptResult receiveTrigger(ReceiveTriggerCommand command) {
        objectAuthorizer.authorizeInstance(command.instanceId(), "TRIGGER");
        if (inboundIdentities.orderedStream().noneMatch(policy -> policy.accepts(command))) {
            throw new ServiceException("入站身份未绑定可信来源合同，拒绝受理");
        }
        ProcessInstance instance = runtimeDao.instance(command.instanceId());
        ProcessTriggerReceipt existing = receiptMapper.selectOne(
            Wrappers.<ProcessTriggerReceipt>lambdaQuery()
                .eq(ProcessTriggerReceipt::getCallerSystemCode, command.callerSystemCode())
                .eq(ProcessTriggerReceipt::getCallContractKey, command.callContractKey())
                .eq(ProcessTriggerReceipt::getCallContractVersion, command.callContractVersion())
                .eq(ProcessTriggerReceipt::getSourceRequestId, command.sourceRequestId()));
        TriggerReceiptResult replayed = replayReceipt(existing, command);
        if (replayed != null) {
            return replayed;
        }
        ProcessEvent candidate = matchInbound(instance, command);
        ProcessEvent matched = eventMapper.selectOne(Wrappers.<ProcessEvent>lambdaQuery()
            .eq(ProcessEvent::getId, candidate.getId()).last("FOR UPDATE"));
        ProcessTriggerReceipt lockedExisting = receiptMapper.selectOne(
            Wrappers.<ProcessTriggerReceipt>lambdaQuery()
                .eq(ProcessTriggerReceipt::getCallerSystemCode, command.callerSystemCode())
                .eq(ProcessTriggerReceipt::getCallContractKey, command.callContractKey())
                .eq(ProcessTriggerReceipt::getCallContractVersion, command.callContractVersion())
                .eq(ProcessTriggerReceipt::getSourceRequestId, command.sourceRequestId())
                .last("FOR UPDATE"));
        replayed = replayReceipt(lockedExisting, command);
        if (replayed != null) {
            return replayed;
        }
        ProcessTriggerReceipt canonical = receiptMapper.selectOne(
            Wrappers.<ProcessTriggerReceipt>lambdaQuery()
                .eq(ProcessTriggerReceipt::getEventId, matched.getId())
                .eq(ProcessTriggerReceipt::getCanonical, Boolean.TRUE)
                .last("LIMIT 1 FOR UPDATE"));
        if (canonical != null && (!canonical.getEffectKey().equals(command.effectKey())
            || !canonical.getPayloadHash().equals(command.payloadHash()))) {
            throw new ServiceException("同一入站事件已有不同效果键或载荷摘要的 canonical 回执");
        }
        ProcessTriggerReceipt receipt = new ProcessTriggerReceipt();
        receipt.setCallerSystemCode(command.callerSystemCode());
        receipt.setModuleCode(command.moduleCode());
        receipt.setCallContractKey(command.callContractKey());
        receipt.setCallContractVersion(command.callContractVersion());
        receipt.setSourceRequestId(command.sourceRequestId());
        receipt.setPayloadHash(command.payloadHash());
        receipt.setEffectKey(command.effectKey());
        receipt.setLogicalCallKey(matched.getIdempotencyKey());
        receipt.setInstanceId(instance.getId());
        receipt.setEventId(matched.getId());
        receipt.setExternalTaskId(command.externalTaskId());
        receipt.setOriginOutboundEventId(command.originOutboundEventId());
        receipt.setPurpose(command.purpose() == null ? null : CallPurpose.valueOf(command.purpose()));
        receipt.setCanonical(canonical == null);
        receipt.setProcessingStatus(ProcessingStatus.PENDING_LINK);
        receipt.setAckSummary("已受理");
        receipt.setEvidenceSummary(command.payloadSummary());
        receipt.setReceivedAt(LocalDateTime.now());
        receiptMapper.insert(receipt);
        requestDetailMapper.insert(com.innolux.iws.process.support.ProcessRequestRecords.inbound(receipt, matched));
        if (canonical != null) {
            receipt.setProcessingStatus(ProcessingStatus.DUPLICATE);
            receipt.setAppliedAt(LocalDateTime.now());
            receiptMapper.updateById(receipt);
            return new TriggerReceiptResult(ProcessText.id(receipt.getId()), ProcessText.id(matched.getId()),
                true, true, receipt.getProcessingStatus().name());
        }
        int eventRows = eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, matched.getId())
            .eq(ProcessEvent::getEventVersion, matched.getEventVersion())
            .set(ProcessEvent::getInputHash, command.payloadHash())
            .set(ProcessEvent::getPayloadReady, Boolean.TRUE)
            .set(ProcessEvent::getOriginOutboundEventId, command.originOutboundEventId())
            .set(ProcessEvent::getEventVersion, matched.getEventVersion() + 1)
            .set(ProcessEvent::getUpdateTime, LocalDateTime.now()));
        if (eventRows != 1) {
            throw new ServiceException("事件输入竞争失败，请按原请求键重试");
        }
        receipt.setProcessingStatus(ProcessingStatus.APPLIED);
        receipt.setAppliedAt(LocalDateTime.now());
        receiptMapper.updateById(receipt);
        eligibilityService.recompute(matched.getId());
        audit(ActionType.TRIGGER, instance.getId(), command.sourceRequestId(), command.toString(), "入站受理");
        return new TriggerReceiptResult(ProcessText.id(receipt.getId()), ProcessText.id(matched.getId()),
            true, false, receipt.getProcessingStatus().name());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommandResult confirmPosting(ConfirmPostingCommand command) {
        objectAuthorizer.authorizeInstance(command.instanceId(), "CONFIRM");
        String payload = commandReceiptService.payloadHash(command);
        if (replay(ActionType.CONFIRM_POSTING, command.instanceId(), command.commandKey(), payload)) {
            return CommandResult.success(String.valueOf(command.instanceId()), null, command.systemCodes());
        }
        ProcessInstance instance = requireInstance(command.instanceId(), command.expectedVersion());
        List<ProcessSystemContext> contexts = new ArrayList<>();
        for (String systemCode : command.systemCodes()) {
            ProcessSystemContext context = runtimeDao.systemContext(instance.getId(), systemCode);
            String rejection = actionPolicy.confirmSystemRejection(context, systemCode);
            if (rejection != null) {
                throw new ServiceException(rejection);
            }
            contexts.add(context);
        }
        LocalDateTime now = LocalDateTime.now();
        List<String> confirmed = new ArrayList<>();
        for (ProcessSystemContext context : contexts) {
            if (Boolean.TRUE.equals(context.getFirstConfirmed())) {
                continue;
            }
            int rows = contextMapper.update(null, Wrappers.<ProcessSystemContext>lambdaUpdate()
                .eq(ProcessSystemContext::getId, context.getId())
                .eq(ProcessSystemContext::getRevision, context.getRevision())
                .eq(ProcessSystemContext::getFirstConfirmed, Boolean.FALSE)
                .set(ProcessSystemContext::getFirstConfirmed, Boolean.TRUE)
                .set(ProcessSystemContext::getConfirmedAt, now)
                .set(ProcessSystemContext::getRevision, context.getRevision() + 1)
                .set(ProcessSystemContext::getUpdateTime, now));
            if (rows != 1) {
                throw new ServiceException("系统确认竞争失败: " + context.getSystemCode());
            }
            confirmed.add(context.getSystemCode());
        }
        int instanceRows = instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, instance.getId())
            .eq(ProcessInstance::getInstanceVersion, command.expectedVersion())
            .set(ProcessInstance::getInstanceVersion, command.expectedVersion() + 1)
            .set(ProcessInstance::getUpdateTime, now));
        if (instanceRows != 1) {
            throw new ServiceException("实例版本冲突，请重新读取");
        }
        Set<String> systems = new HashSet<>(command.systemCodes());
        for (ProcessEvent event : runtimeDao.events(instance.getId())) {
            if (systems.contains(event.getSystemCode())) {
                eligibilityService.recompute(event.getId());
            }
        }
        audit(ActionType.CONFIRM_POSTING, instance.getId(), command.commandKey(), payload, "首次确认");
        return CommandResult.success(String.valueOf(instance.getId()),
            String.valueOf(command.expectedVersion() + 1), confirmed);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommandResult pause(PlanControlCommand command) {
        objectAuthorizer.authorizeInstance(command.instanceId(), "PAUSE");
        return changePlanStatus(command, PlanStatus.PAUSED, ActionType.PLAN_PAUSE, "暂停计划");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommandResult resume(PlanControlCommand command) {
        objectAuthorizer.authorizeInstance(command.instanceId(), "RESUME");
        CommandResult result = changePlanStatus(command, PlanStatus.RUNNING, ActionType.PLAN_RESUME, "恢复计划");
        eligibilityService.recomputeInstance(command.instanceId());
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommandResult cancel(PlanControlCommand command) {
        objectAuthorizer.authorizeInstance(command.instanceId(), "CANCEL");
        String payload = commandReceiptService.payloadHash(command);
        if (replay(ActionType.PLAN_CANCEL, command.instanceId(), command.commandKey(), payload)) {
            return CommandResult.success(String.valueOf(command.instanceId()), null, List.of());
        }
        ProcessInstance instance = requireInstance(command.instanceId(), command.expectedVersion());
        boolean unresolved = runtimeDao.events(instance.getId()).stream().anyMatch(event ->
            event.getEventStatus() == EventStatus.PROCESSING || event.getEffectResult() == EffectResult.UNKNOWN
                || (Boolean.TRUE.equals(event.getSent()) && event.getEventStatus() != EventStatus.SUCCESS
                && event.getEventStatus() != EventStatus.SKIPPED
                && event.getEventStatus() != EventStatus.NOT_SELECTED));
        ProcessStatusTarget target = unresolved
            ? new ProcessStatusTarget(PlanStatus.EXCEPTION_HOLD, "取消待收敛")
            : new ProcessStatusTarget(PlanStatus.CANCELLED, "已安全取消");
        LocalDateTime now = LocalDateTime.now();
        int rows = instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, instance.getId())
            .eq(ProcessInstance::getInstanceVersion, command.expectedVersion())
            .set(ProcessInstance::getPlanStatus, target.status())
            .set(ProcessInstance::getCancelRequested, Boolean.TRUE)
            .set(ProcessInstance::getStopRequested, Boolean.TRUE)
            .set(target.status() == PlanStatus.CANCELLED, ProcessInstance::getEndedAt, now)
            .set(ProcessInstance::getExceptionSummary, unresolved ? "存在在途或未知副作用，待安全收敛" : null)
            .set(ProcessInstance::getInstanceVersion, command.expectedVersion() + 1)
            .set(ProcessInstance::getUpdateTime, now));
        if (rows != 1) {
            throw new ServiceException("实例版本冲突，请重新读取");
        }
        if (!unresolved) {
            activationService.releaseSlot(instance.getId());
        }
        audit(ActionType.PLAN_CANCEL, instance.getId(), command.commandKey(), payload, target.message());
        return new CommandResult(unresolved ? "FAILED_HOLD" : "SUCCEEDED", target.message(),
            String.valueOf(instance.getId()), String.valueOf(command.expectedVersion() + 1), List.of());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommandResult holdStart(PlanControlCommand command) {
        objectAuthorizer.authorizeInstance(command.instanceId(), "HOLD");
        String payload = commandReceiptService.payloadHash(command);
        if (replay(ActionType.SCHEDULE_CONTROL, command.instanceId(), command.commandKey(), payload)) {
            return CommandResult.success(String.valueOf(command.instanceId()), null, List.of());
        }
        ProcessInstance instance = requireInstance(command.instanceId(), command.expectedVersion());
        String rejection = actionPolicy.holdStartRejection(instance);
        if (rejection != null) {
            throw new ServiceException(rejection);
        }
        updateInstance(instance, command.expectedVersion(), PlanStatus.CREATED, Boolean.TRUE, null);
        audit(ActionType.SCHEDULE_CONTROL, instance.getId(), command.commandKey(), payload, "准入前挂起");
        return CommandResult.success(String.valueOf(instance.getId()),
            String.valueOf(command.expectedVersion() + 1), List.of());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommandResult releaseStart(PlanControlCommand command) {
        objectAuthorizer.authorizeInstance(command.instanceId(), "RELEASE");
        String payload = commandReceiptService.payloadHash(command);
        if (replay(ActionType.SCHEDULE_CONTROL, command.instanceId(), command.commandKey(), payload)) {
            return CommandResult.success(String.valueOf(command.instanceId()), null, List.of());
        }
        ProcessInstance instance = requireInstance(command.instanceId(), command.expectedVersion());
        String rejection = actionPolicy.releaseStartRejection(instance);
        if (rejection != null) {
            throw new ServiceException(rejection);
        }
        updateInstance(instance, command.expectedVersion(), PlanStatus.CREATED, Boolean.FALSE, null);
        audit(ActionType.SCHEDULE_CONTROL, instance.getId(), command.commandKey(), payload, "解除准入前挂起");
        return CommandResult.success(String.valueOf(instance.getId()),
            String.valueOf(command.expectedVersion() + 1), List.of());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommandResult setPriority(PriorityCommand command) {
        objectAuthorizer.authorizeInstance(command.instanceId(), "PRIORITY");
        String payload = commandReceiptService.payloadHash(command);
        if (replay(ActionType.EVENT_PRIORITY, command.instanceId(), command.commandKey(), payload)) {
            return CommandResult.success(String.valueOf(command.instanceId()), null, List.of());
        }
        ProcessInstance instance = requireInstance(command.instanceId(), command.expectedVersion());
        String rejection = actionPolicy.priorityRejection(runtimeDao.events(instance.getId()));
        if (rejection != null) {
            throw new ServiceException(rejection);
        }
        int rows = instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, instance.getId())
            .eq(ProcessInstance::getInstanceVersion, command.expectedVersion())
            .set(ProcessInstance::getInstancePriority, command.priority())
            .set(ProcessInstance::getInstanceVersion, command.expectedVersion() + 1)
            .set(ProcessInstance::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new ServiceException("实例版本冲突，请重新读取");
        }
        audit(ActionType.EVENT_PRIORITY, instance.getId(), command.commandKey(), payload, "调整实例优先级");
        return CommandResult.success(String.valueOf(instance.getId()),
            String.valueOf(command.expectedVersion() + 1), List.of());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InstanceSummaryVO get(long instanceId) {
        objectAuthorizer.authorizeInstance(instanceId, "QUERY");
        ProcessInstance instance = runtimeDao.instance(instanceId);
        PlanDefinition definition = runtimeDao.definition(instance.getDefinitionVersionId());
        DefinitionGraph graph = runtimeDao.graph(instance.getDefinitionVersionId());
        List<ProcessSystemContext> contexts = contextMapper.selectList(
            Wrappers.<ProcessSystemContext>lambdaQuery()
                .eq(ProcessSystemContext::getInstanceId, instanceId)
                .orderByAsc(ProcessSystemContext::getSystemCode));
        List<InstanceSystemVO> systems = contexts.stream()
            .map(item -> new InstanceSystemVO(item.getSystemCode(), item.getPostingMode().name(),
                item.getFirstConfirmed(), ProcessText.id(item.getConfirmedBy()),
                item.getConfirmedAt() == null ? null : item.getConfirmedAt().toString())).toList();
        List<TokenSummaryVO> tokens = tokenMapper.selectList(Wrappers.<ProcessToken>lambdaQuery()
                .eq(ProcessToken::getInstanceId, instanceId).orderByAsc(ProcessToken::getId))
            .stream().map(item -> new TokenSummaryVO(ProcessText.id(item.getId()),
                item.getTokenRole().name(), item.getTokenStatus().name(), item.getStateCode(),
                item.getScopeKey(), item.getBranchKey(), ProcessText.id(item.getBlockedEventId()))).toList();
        List<ProcessEvent> instanceEvents = runtimeDao.events(instanceId);
        List<EventSummaryVO> eventRows = instanceEvents.stream().map(item -> {
            ProcessEventDefinition eventDefinition = graph.event(item.getEventCode());
            ProcessAttempt attempt = item.getLastAttemptId() == null ? null
                : attemptMapper.selectById(item.getLastAttemptId());
            return new EventSummaryVO(ProcessText.id(item.getId()), item.getEventCode(),
                eventDefinition == null ? null : eventDefinition.getDisplayName(),
                item.getEventDirection() == null ? null : item.getEventDirection().name(),
                item.getEventStatus().name(), item.getWaitReason() == null ? null : item.getWaitReason().name(),
                item.getEffectResult() == null ? null : item.getEffectResult().name(),
                ProcessText.id(item.getTokenId()), attempt == null ? null : attempt.getAttemptNo());
        }).toList();
        PlanInstanceActionPolicy.Admission admission = actionPolicy.describe(instance, instanceEvents, contexts);
        return new InstanceSummaryVO(ProcessText.id(instance.getId()), definition.getPlanCode(),
            definition.getPlanName(), instance.getPlanRole().name(), instance.getPlanStatus().name(),
            instance.getModeSnapshot() == null ? null : instance.getModeSnapshot().name(), instance.getTaskKey(),
            ProcessText.id(instance.getSupervisorInstanceId()), ProcessText.id(instance.getInstanceVersion()),
            instance.getInstancePriority(), instance.getExceptionSummary(), systems, tokens, eventRows,
            admission.allowedActionCodes(), admission.reasons());
    }

    private CommandResult changePlanStatus(PlanControlCommand command, PlanStatus target, ActionType action,
                                           String reason) {
        return changePlanStatusInternal(command, target, action, reason);
    }

    @Transactional(rollbackFor = Exception.class)
    protected CommandResult changePlanStatusInternal(PlanControlCommand command, PlanStatus target, ActionType action,
                                                     String reason) {
        String payload = commandReceiptService.payloadHash(command);
        if (replay(action, command.instanceId(), command.commandKey(), payload)) {
            return CommandResult.success(String.valueOf(command.instanceId()), null, List.of());
        }
        ProcessInstance instance = requireInstance(command.instanceId(), command.expectedVersion());
        if (target == PlanStatus.RUNNING) {
            String rejection = actionPolicy.resumeRejection(instance);
            if (rejection != null) {
                throw new ServiceException(rejection);
            }
        }
        if (target == PlanStatus.PAUSED) {
            String rejection = actionPolicy.pauseRejection(instance);
            if (rejection != null) {
                throw new ServiceException(rejection);
            }
        }
        int rows = instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, instance.getId())
            .eq(ProcessInstance::getInstanceVersion, command.expectedVersion())
            .set(ProcessInstance::getPlanStatus, target)
            .set(target == PlanStatus.RUNNING, ProcessInstance::getExceptionSummary, null)
            .set(ProcessInstance::getInstanceVersion, command.expectedVersion() + 1)
            .set(ProcessInstance::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new ServiceException("实例版本冲突，请重新读取");
        }
        audit(action, instance.getId(), command.commandKey(), payload, reason);
        return CommandResult.success(String.valueOf(instance.getId()),
            String.valueOf(command.expectedVersion() + 1), List.of());
    }

    private ProcessInstance requireInstance(long instanceId, long expectedVersion) {
        ProcessInstance instance = runtimeDao.instance(instanceId);
        if (instance.getInstanceVersion() != expectedVersion) {
            throw new ServiceException("实例版本冲突，当前 version=" + instance.getInstanceVersion());
        }
        return instance;
    }

    private AttachResult attachResult(ProcessInstance instance, boolean created, String queueStatus) {
        return new AttachResult(ProcessText.id(instance.getId()),
            ProcessText.id(instance.getSupervisorInstanceId()),
            instance.getModeSnapshot() == null ? null : instance.getModeSnapshot().name(), queueStatus,
            instance.getPlanStatus() == PlanStatus.RUNNING, queueStatus == null ? null : queueStatus,
            null, ProcessText.id(instance.getInstanceVersion()));
    }

    private String rootSet(AttachTaskCommand command, PlanDefinition definition) {
        if (command.rootSet() != null && !command.rootSet().isEmpty()) {
            return String.join(",", command.rootSet());
        }
        return String.join(",", runtimeDao.graph(definition.getId()).roots());
    }

    private TriggerReceiptResult replayReceipt(ProcessTriggerReceipt existing, ReceiveTriggerCommand command) {
        if (existing == null) {
            return null;
        }
        if (existing.getInstanceId() == null || existing.getInstanceId() != command.instanceId()) {
            throw new ServiceException("同一来源请求键已绑定其他实例");
        }
        if (!existing.getPayloadHash().equals(command.payloadHash())) {
            throw new ServiceException("同一来源请求键的载荷摘要不同");
        }
        return new TriggerReceiptResult(ProcessText.id(existing.getId()), ProcessText.id(existing.getEventId()),
            true, true, existing.getProcessingStatus().name());
    }

    private ProcessEvent matchInbound(ProcessInstance instance, ReceiveTriggerCommand command) {
        List<ProcessEvent> candidates = eventMapper.selectList(Wrappers.<ProcessEvent>lambdaQuery()
            .eq(ProcessEvent::getInstanceId, instance.getId())
            .eq(ProcessEvent::getCallContractKey, command.callContractKey())
            .eq(ProcessEvent::getCallContractVersion, command.callContractVersion())
            .eq(ProcessEvent::getEventDirection, EventDirection.INBOUND));
        if (candidates.isEmpty()) {
            throw new ServiceException("未找到匹配的 INBOUND 事件合同");
        }
        if (candidates.size() == 1) {
            validateResultOrigin(instance, command);
            return candidates.getFirst();
        }
        DefinitionGraph graph = runtimeDao.graph(instance.getDefinitionVersionId());
        List<ProcessEvent> matched = candidates.stream().filter(item -> {
            ProcessEventDefinition definition = graph.event(item.getEventCode());
            return definition != null && definition.getPurpose() != null
                && definition.getPurpose().name().equals(command.purpose());
        }).toList();
        if (matched.size() != 1) {
            throw new ServiceException("入站合同匹配不唯一或缺少用途: " + matched.size());
        }
        ProcessEvent event = matched.getFirst();
        validateResultOrigin(instance, command);
        return event;
    }

    private void validateResultOrigin(ProcessInstance instance, ReceiveTriggerCommand command) {
        if (command.purpose() == null || !"RESULT_CALLBACK".equals(command.purpose())) {
            return;
        }
        if (command.originOutboundEventId() == null) {
            throw new ServiceException("结果回调缺少 originOutboundEventId");
        }
        ProcessEvent origin = runtimeDao.event(command.originOutboundEventId());
        if (origin.getInstanceId() != instance.getId()
            || origin.getEventDirection() != EventDirection.OUTBOUND) {
            throw new ServiceException("结果回调来源出站事件归属不匹配");
        }
    }

    private void updateInstance(ProcessInstance instance, long expectedVersion, PlanStatus status,
                                Boolean holdStart, Integer priority) {
        int rows = instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, instance.getId())
            .eq(ProcessInstance::getInstanceVersion, expectedVersion)
            .set(ProcessInstance::getPlanStatus, status)
            .set(holdStart != null, ProcessInstance::getHoldStart, holdStart)
            .set(priority != null, ProcessInstance::getInstancePriority, priority)
            .set(ProcessInstance::getInstanceVersion, expectedVersion + 1)
            .set(ProcessInstance::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new ServiceException("实例版本冲突，请重新读取");
        }
    }

    private boolean replay(ActionType action, long instanceId, String commandKey, String payload) {
        ProcessActionAudit existing = commandReceiptService.find(action, "instance:" + instanceId, commandKey);
        if (existing == null) {
            return false;
        }
        commandReceiptService.checkSamePayload(existing, payload);
        return true;
    }

    private void audit(ActionType action, long instanceId, String commandKey, String payload, String reason) {
        ProcessActionAudit audit = auditService.newAudit(action, ActionSource.USER, AuditResult.SUCCESS);
        audit.setInstanceId(instanceId);
        audit.setCommandKey(commandKey);
        audit.setReason(reason);
        if (commandKey != null && !commandKey.isBlank()) {
            audit.setReceiptKey(commandReceiptService.key(action, "instance:" + instanceId, commandKey));
            audit.setPayloadSummary(payload);
        }
        auditService.append(audit);
    }

    private record ProcessStatusTarget(PlanStatus status, String message) {
    }
}
