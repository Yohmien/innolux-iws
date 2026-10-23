/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 即时计划监督轮次、PLAN_SLOT、实例与回执原子准入；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.api.ExecuteImmediateCommand;
import com.innolux.iws.process.api.ImmediateExecutionVO;
import com.innolux.iws.process.domain.BusinessSchedule;
import com.innolux.iws.process.domain.BusinessSchedulePlanType;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.domain.ProcessTriggerReceipt;
import com.innolux.iws.process.enums.AuditResult;
import com.innolux.iws.process.enums.ControlState;
import com.innolux.iws.process.enums.DefinitionStatus;
import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.PlanStatus;
import com.innolux.iws.process.enums.ProcessingStatus;
import com.innolux.iws.process.enums.RoundStatus;
import com.innolux.iws.process.enums.ScheduleControlScope;
import com.innolux.iws.process.enums.SlotState;
import com.innolux.iws.process.mapper.BusinessScheduleMapper;
import com.innolux.iws.process.mapper.BusinessSchedulePlanTypeMapper;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.mapper.ProcessTriggerReceiptMapper;
import com.innolux.iws.process.support.DefinitionGraph;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 即时计划的唯一准入实现。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled",
    havingValue = "true")
public class ImmediateAdmissionService {

    private static final String DISPATCH_DUTY = "DISPATCH";

    private static final String SLOT_DUTY = "PLAN_SLOT";

    private final BusinessScheduleMapper scheduleMapper;

    private final BusinessSchedulePlanTypeMapper planTypeMapper;

    private final PlanDefinitionMapper definitionMapper;

    private final ProcessInstanceMapper instanceMapper;

    private final ProcessScheduleControlMapper controlMapper;

    private final ProcessEventMapper eventMapper;

    private final ProcessTriggerReceiptMapper receiptMapper;

    private final RuntimeDao runtimeDao;

    private final PlanMaterializer materializer;

    private final SupervisorRuntimeService supervisorRuntimeService;

    private final PlanScopeResolver planScopeResolver;

    private final ImmediatePlanPreflight preflight;

    private final ImmediateCommandReceiptStore receiptStore;

    private final ImmediateInputEvidenceRegistry inputEvidence;

    private final EventEligibilityService eligibilityService;

    /**
     * 构造准入服务。
     *
     * @param scheduleMapper 调度 Mapper
     * @param planTypeMapper 类型 Mapper
     * @param definitionMapper 定义 Mapper
     * @param instanceMapper 实例 Mapper
     * @param controlMapper 控制 Mapper
     * @param eventMapper 事件 Mapper
     * @param receiptMapper 入站回执 Mapper
     * @param runtimeDao 运行时 DAO
     * @param materializer 物化器
     * @param supervisorRuntimeService 监督身份服务
     * @param planScopeResolver scope 解析
     * @param preflight 即时图门禁
     * @param receiptStore 回执存储
     * @param inputEvidence 已持久入站证据
     * @param eligibilityService 事件资格服务
     */
    public ImmediateAdmissionService(BusinessScheduleMapper scheduleMapper,
                                     BusinessSchedulePlanTypeMapper planTypeMapper,
                                     PlanDefinitionMapper definitionMapper, ProcessInstanceMapper instanceMapper,
                                     ProcessScheduleControlMapper controlMapper, ProcessEventMapper eventMapper,
                                     ProcessTriggerReceiptMapper receiptMapper, RuntimeDao runtimeDao,
                                     PlanMaterializer materializer,
                                     SupervisorRuntimeService supervisorRuntimeService,
                                     PlanScopeResolver planScopeResolver, ImmediatePlanPreflight preflight,
                                     ImmediateCommandReceiptStore receiptStore,
                                     ImmediateInputEvidenceRegistry inputEvidence,
                                     EventEligibilityService eligibilityService) {
        this.scheduleMapper = scheduleMapper;
        this.planTypeMapper = planTypeMapper;
        this.definitionMapper = definitionMapper;
        this.instanceMapper = instanceMapper;
        this.controlMapper = controlMapper;
        this.eventMapper = eventMapper;
        this.receiptMapper = receiptMapper;
        this.runtimeDao = runtimeDao;
        this.materializer = materializer;
        this.supervisorRuntimeService = supervisorRuntimeService;
        this.planScopeResolver = planScopeResolver;
        this.preflight = preflight;
        this.receiptStore = receiptStore;
        this.inputEvidence = inputEvidence;
        this.eligibilityService = eligibilityService;
    }

    /**
     * 原子准入或写拒绝回执。
     *
     * @param command 命令
     * @return 准入结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Admission admit(ExecuteImmediateCommand command) {
        LocalDateTime startedAt = LocalDateTime.now();
        BusinessSchedule schedule = scheduleMapper.selectById(command.scheduleId());
        if (schedule == null || !Boolean.TRUE.equals(schedule.getEnabled())) {
            throw new ServiceException("业务调度不存在或已停用");
        }
        BusinessSchedulePlanType type = planTypeMapper.selectOne(Wrappers.<BusinessSchedulePlanType>lambdaQuery()
            .eq(BusinessSchedulePlanType::getScheduleId, command.scheduleId())
            .eq(BusinessSchedulePlanType::getPlanType, command.planType()));
        if (type == null || !Boolean.TRUE.equals(type.getEnabled())) {
            return reject(command, null, null, null, "NOT_READY", "计划类型未登记或已停用", startedAt);
        }
        List<PlanDefinition> supervisorDefinitions = definitionMapper.selectList(
            Wrappers.<PlanDefinition>lambdaQuery()
                .eq(PlanDefinition::getBusinessScheduleCode, schedule.getBusinessCode())
                .eq(PlanDefinition::getPlanType, command.planType())
                .eq(PlanDefinition::getPlanRole, PlanRole.SUPERVISOR)
                .eq(PlanDefinition::getStatus, DefinitionStatus.ACTIVE)
                .isNotNull(PlanDefinition::getMode));
        if (supervisorDefinitions.size() != 1) {
            return reject(command, null, null, null, "NOT_READY", "已发布监督计划版本不唯一", startedAt);
        }
        PlanDefinition supervisorDefinition = supervisorDefinitions.getFirst();
        if (supervisorDefinition.getMode() != PlanMode.IMMEDIATE) {
            return reject(command, null, null, null, "MODE_MISMATCH", "监督计划不是 IMMEDIATE 模式", startedAt);
        }
        if (supervisorDefinition.getActivePlanLimit() == null || supervisorDefinition.getActivePlanLimit() != 1) {
            return reject(command, null, null, null, "NOT_READY", "IMMEDIATE 必须固定 activePlanLimit=1", startedAt);
        }
        if (supervisorDefinition.getRoundMaxDurationMs() == null
            || supervisorDefinition.getRoundMaxDurationMs() <= 0) {
            return reject(command, null, null, null, "NOT_READY", "监督计划缺少 roundMaxDurationMs", startedAt);
        }
        List<PlanDefinition> businessDefinitions = definitionMapper.selectList(
            Wrappers.<PlanDefinition>lambdaQuery()
                .eq(PlanDefinition::getBusinessScheduleCode, schedule.getBusinessCode())
                .eq(PlanDefinition::getPlanType, command.planType())
                .eq(PlanDefinition::getPlanRole, PlanRole.BUSINESS)
                .eq(PlanDefinition::getStatus, DefinitionStatus.ACTIVE));
        if (businessDefinitions.size() != 1) {
            return reject(command, null, null, null, "NOT_READY", "已发布 BUSINESS 计划版本不唯一", startedAt);
        }
        PlanDefinition businessDefinition = businessDefinitions.getFirst();
        List<ImmediateInputEvidenceRef> evidenceRefs;
        try {
            evidenceRefs = inputEvidence.resolve(businessDefinition, command);
        } catch (RuntimeException ex) {
            return reject(command, null, null, null, "NOT_READY",
                "读取本库入站证据失败: " + ex.getMessage(), startedAt);
        }
        List<String> preflightErrors = preflight.validate(businessDefinition, evidenceRefs);
        if (!preflightErrors.isEmpty()) {
            return reject(command, null, null, null, "NOT_READY",
                "即时合同不满足: " + String.join(";", preflightErrors), startedAt);
        }
        List<ValidatedEvidence> validatedEvidence;
        try {
            validatedEvidence = validateEvidence(businessDefinition, command, evidenceRefs);
        } catch (InputEvidenceException ex) {
            return reject(command, null, null, null, "NOT_READY", ex.getMessage(), startedAt);
        }
        String scope = planScopeResolver.resolve(command.businessType(), command.planType(),
            command.businessId(), command.taskKey());
        if (scope == null || scope.isBlank()) {
            return reject(command, null, null, null, "NOT_READY", "scope 解析结果为空", startedAt);
        }
        ProcessInstance supervisor = supervisorRuntimeService.ensureSupervisor(command.scheduleId(),
            command.planType(), scope);
        if (supervisor.getModeSnapshot() != PlanMode.IMMEDIATE) {
            return reject(command, supervisor.getId(), null, null, "MODE_MISMATCH",
                "冻结监督实例不是 IMMEDIATE 模式", startedAt);
        }
        if (supervisor.getPlanStatus() == PlanStatus.PAUSED) {
            return reject(command, supervisor.getId(), null, null, "SUPERVISOR_PAUSED",
                "监督计划已暂停", startedAt);
        }
        ProcessScheduleControl control = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.SUPERVISOR)
            .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisor.getId())
            .eq(ProcessScheduleControl::getDuty, DISPATCH_DUTY)
            .last("FOR UPDATE"));
        if (control == null || control.getControlState() == ControlState.MANUAL_PAUSED) {
            return reject(command, supervisor.getId(), null, null, "SUPERVISOR_PAUSED",
                "监督控制行已暂停", startedAt);
        }
        LocalDateTime now = LocalDateTime.now();
        if (control.getRoundStatus() == RoundStatus.OPEN && control.getLeaseUntil() != null
            && control.getLeaseUntil().isAfter(now)) {
            return reject(command, supervisor.getId(), null, control.getRoundNo(), "SUPERVISOR_BUSY",
                "监督正在处理其他轮次", startedAt);
        }
        String businessTaskKey = command.businessType() + ":" + command.taskKey();
        ProcessInstance existing = instanceMapper.selectOne(Wrappers.<ProcessInstance>lambdaQuery()
            .eq(ProcessInstance::getBusinessTaskKey, businessTaskKey)
            .last("LIMIT 1"));
        if (existing != null) {
            String reason = existing.getInputHash() != null && existing.getInputHash().equals(command.inputHash())
                ? "TASK_KEY_ALREADY_EXISTS" : "TASK_INPUT_CONFLICT";
            return reject(command, supervisor.getId(), existing.getId(), null, "BUSY",
                reason + ": 原实例 " + existing.getId(), startedAt);
        }
        ProcessInstance activeChild = instanceMapper.selectOne(Wrappers.<ProcessInstance>lambdaQuery()
            .eq(ProcessInstance::getSupervisorInstanceId, supervisor.getId())
            .eq(ProcessInstance::getPlanRole, PlanRole.BUSINESS)
            .in(ProcessInstance::getPlanStatus, PlanStatus.CREATED, PlanStatus.RUNNING,
                PlanStatus.MANUAL_PENDING, PlanStatus.EXCEPTION_HOLD, PlanStatus.PAUSED)
            .orderByAsc(ProcessInstance::getId)
            .last("LIMIT 1"));
        long held = controlMapper.selectCount(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.PLAN_SLOT)
            .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisor.getId())
            .eq(ProcessScheduleControl::getSlotState, SlotState.HELD));
        if (held > 0 || activeChild != null) {
            Long blocker = activeChild == null ? null : activeChild.getId();
            return reject(command, supervisor.getId(), blocker, null, "BUSY",
                "已有计划占用唯一 PLAN_SLOT", startedAt);
        }

        long roundNo = (control.getRoundNo() == null ? 0L : control.getRoundNo()) + 1;
        long epoch = (control.getEpoch() == null ? 0L : control.getEpoch()) + 1;
        String owner = "IMMEDIATE:" + UUID.randomUUID();
        int roundRows = controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
            .eq(ProcessScheduleControl::getId, control.getId())
            .eq(ProcessScheduleControl::getRevision, control.getRevision())
            .set(ProcessScheduleControl::getRoundNo, roundNo)
            .set(ProcessScheduleControl::getRoundStatus, RoundStatus.OPEN)
            .set(ProcessScheduleControl::getEpoch, epoch)
            .set(ProcessScheduleControl::getOwner, owner)
            .set(ProcessScheduleControl::getLeaseUntil, now.plusNanos(
                supervisorDefinition.getRoundMaxDurationMs() * 1_000_000L))
            .set(ProcessScheduleControl::getRevision, control.getRevision() + 1)
            .set(ProcessScheduleControl::getLastActivityAt, now));
        if (roundRows != 1) {
            return reject(command, supervisor.getId(), null, null, "SUPERVISOR_BUSY",
                "监督轮次竞争失败", startedAt);
        }

        ProcessInstance instance = new ProcessInstance();
        instance.setPlanRole(PlanRole.BUSINESS);
        instance.setDefinitionVersionId(businessDefinition.getId());
        instance.setDefinitionRevision(businessDefinition.getRevision());
        instance.setScheduleId(command.scheduleId());
        instance.setPlanType(command.planType());
        instance.setBusinessScheduleCode(schedule.getBusinessCode());
        instance.setSupervisorInstanceId(supervisor.getId());
        instance.setBusinessTaskKey(businessTaskKey);
        instance.setBusinessType(command.businessType());
        instance.setBusinessId(command.businessId());
        instance.setTaskKey(command.taskKey());
        instance.setScheduleScope(scope);
        instance.setModeSnapshot(PlanMode.IMMEDIATE);
        instance.setHoldStart(Boolean.FALSE);
        instance.setPlanStatus(PlanStatus.CREATED);
        instance.setInstancePriority(0);
        instance.setSelectedRootSet(roots(businessDefinition));
        instance.setInputHash(command.inputHash());
        LocalDateTime deadline = now.plusNanos(businessDefinition.getMaxExecutionMs() * 1_000_000L);
        instance.setExecutionDeadline(deadline);
        instance.setRoundNo(0L);
        instance.setStopRequested(Boolean.FALSE);
        instance.setCancelRequested(Boolean.FALSE);
        instance.setInstanceVersion(1L);
        instance.setStartedAt(now);
        instanceMapper.insert(instance);
        materializer.materialize(instance);
        eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getInstanceId, instance.getId())
            .set(ProcessEvent::getInputHash, command.inputHash())
            .set(ProcessEvent::getUpdateTime, now));
        bindInputEvidence(instance, validatedEvidence, command.inputHash(), now);

        ProcessScheduleControl slot = new ProcessScheduleControl();
        slot.setScopeType(ScheduleControlScope.PLAN_SLOT);
        slot.setScopeKey(String.valueOf(instance.getId()));
        slot.setDuty(SLOT_DUTY);
        slot.setRevision(1L);
        slot.setEpoch(epoch);
        slot.setOwner(owner);
        slot.setControlState(ControlState.AUTO_RUNNING);
        slot.setSlotState(SlotState.HELD);
        slot.setSupervisorInstanceId(supervisor.getId());
        slot.setChildInstanceId(instance.getId());
        slot.setDefinitionVersionId(supervisorDefinition.getId());
        slot.setLastAdmittedAt(now);
        slot.setLastActivityAt(now);
        controlMapper.insert(slot);

        int instanceRows = instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, instance.getId())
            .eq(ProcessInstance::getInstanceVersion, instance.getInstanceVersion())
            .eq(ProcessInstance::getPlanStatus, PlanStatus.CREATED)
            .set(ProcessInstance::getPlanStatus, PlanStatus.RUNNING)
            .set(ProcessInstance::getFirstAdmittedAt, now)
            .set(ProcessInstance::getInstanceVersion, instance.getInstanceVersion() + 1)
            .set(ProcessInstance::getUpdateTime, now));
        if (instanceRows != 1) {
            throw new ServiceException("即时计划实例准入竞争失败");
        }
        instance.setPlanStatus(PlanStatus.RUNNING);
        instance.setFirstAdmittedAt(now);
        instance.setInstanceVersion(instance.getInstanceVersion() + 1);

        controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
            .eq(ProcessScheduleControl::getId, control.getId())
            .eq(ProcessScheduleControl::getRevision, control.getRevision() + 1)
            .set(ProcessScheduleControl::getRoundStatus, RoundStatus.COMPLETED)
            .set(ProcessScheduleControl::getLeaseUntil, now)
            .set(ProcessScheduleControl::getRevision, control.getRevision() + 2)
            .set(ProcessScheduleControl::getLastActivityAt, now));

        receiptStore.writeAnchor(command, supervisor.getId(), instance.getId(), roundNo, "PROCESSING",
            "原命令处理中", null, false, startedAt, null, AuditResult.SUCCESS);
        ImmediateExecutionVO view = receiptStore.findView(command.scheduleId(), command.commandKey());
        return new Admission(true, view, command, instance, supervisor, supervisorDefinition,
            businessDefinition, deadline, roundNo, owner);
    }

    private Admission reject(ExecuteImmediateCommand command, Long supervisorId, Long instanceId, Long roundNo,
                             String resultCode, String message, LocalDateTime startedAt) {
        LocalDateTime finishedAt = LocalDateTime.now();
        receiptStore.writeAnchor(command, supervisorId, instanceId, roundNo, resultCode, message, null,
            false, startedAt, finishedAt, AuditResult.REJECTED);
        return new Admission(false, receiptStore.findView(command.scheduleId(), command.commandKey()), command,
            null, null, null, null, null, roundNo, null);
    }

    private String roots(PlanDefinition definition) {
        DefinitionGraph graph = runtimeDao.graph(definition.getId());
        return String.join(",", graph.roots());
    }

    private List<ValidatedEvidence> validateEvidence(PlanDefinition definition, ExecuteImmediateCommand command,
                                                     List<ImmediateInputEvidenceRef> refs) {
        List<ValidatedEvidence> values = new java.util.ArrayList<>();
        for (ImmediateInputEvidenceRef ref : refs) {
            ProcessTriggerReceipt receipt = receiptMapper.selectOne(Wrappers.<ProcessTriggerReceipt>lambdaQuery()
                .eq(ProcessTriggerReceipt::getId, ref.receiptId())
                .last("FOR UPDATE"));
            if (receipt == null) {
                throw new InputEvidenceException("INPUT_EVIDENCE_NOT_FOUND: 回执不存在 " + ref.receiptId());
            }
            if (receipt.getInstanceId() != null || receipt.getEventId() != null) {
                throw new InputEvidenceException("INPUT_EVIDENCE_ALREADY_BOUND: 回执已绑定 " + ref.receiptId());
            }
            if (Boolean.TRUE.equals(receipt.getCanonical())) {
                throw new InputEvidenceException("INPUT_EVIDENCE_ALREADY_CANONICAL: 回执已被登记 " + ref.receiptId());
            }
            if (receipt.getProcessingStatus() != ProcessingStatus.PENDING_LINK) {
                throw new InputEvidenceException("INPUT_EVIDENCE_STATE_INVALID: 回执不是待绑定状态 "
                    + ref.receiptId());
            }
            ProcessEventDefinition eventDefinition = runtimeDao.graph(definition.getId()).event(ref.eventCode());
            if (eventDefinition == null) {
                throw new InputEvidenceException("INPUT_EVIDENCE_EVENT_INVALID: " + ref.eventCode());
            }
            if (!java.util.Objects.equals(receipt.getCallerSystemCode(), eventDefinition.getSystemCode())) {
                throw new InputEvidenceException("INPUT_EVIDENCE_SOURCE_MISMATCH: " + ref.eventCode());
            }
            if (!java.util.Objects.equals(receipt.getCallContractKey(), eventDefinition.getCallContractKey())
                || !java.util.Objects.equals(receipt.getCallContractVersion(),
                eventDefinition.getCallContractVersion())) {
                throw new InputEvidenceException("INPUT_EVIDENCE_CONTRACT_MISMATCH: " + ref.eventCode());
            }
            if (!java.util.Objects.equals(receipt.getEffectKey(), eventDefinition.getEffectKey())) {
                throw new InputEvidenceException("INPUT_EVIDENCE_EFFECT_MISMATCH: " + ref.eventCode());
            }
            if (!java.util.Objects.equals(receipt.getPayloadHash(), command.inputHash())) {
                throw new InputEvidenceException("INPUT_EVIDENCE_HASH_CONFLICT: " + ref.eventCode());
            }
            values.add(new ValidatedEvidence(eventDefinition, receipt));
        }
        return values;
    }

    private void bindInputEvidence(ProcessInstance instance, List<ValidatedEvidence> values, String inputHash,
                                   LocalDateTime now) {
        for (ValidatedEvidence value : values) {
            ProcessEvent event = runtimeDao.findEvent(instance.getId(), value.definition().getEventCode(), 0L);
            if (event == null) {
                throw new ServiceException("即时入站事件未物化: " + value.definition().getEventCode());
            }
            int receiptRows = receiptMapper.update(null, Wrappers.<ProcessTriggerReceipt>lambdaUpdate()
                .eq(ProcessTriggerReceipt::getId, value.receipt().getId())
                .isNull(ProcessTriggerReceipt::getInstanceId)
                .isNull(ProcessTriggerReceipt::getEventId)
                .eq(ProcessTriggerReceipt::getCanonical, Boolean.FALSE)
                .eq(ProcessTriggerReceipt::getProcessingStatus, ProcessingStatus.PENDING_LINK)
                .set(ProcessTriggerReceipt::getInstanceId, instance.getId())
                .set(ProcessTriggerReceipt::getEventId, event.getId())
                .set(ProcessTriggerReceipt::getCanonical, Boolean.TRUE)
                .set(ProcessTriggerReceipt::getProcessingStatus, ProcessingStatus.APPLIED)
                .set(ProcessTriggerReceipt::getAppliedAt, now)
                .set(ProcessTriggerReceipt::getUpdateTime, now));
            if (receiptRows != 1) {
                throw new ServiceException("入站证据绑定竞争失败: " + value.receipt().getId());
            }
            int eventRows = eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
                .eq(ProcessEvent::getId, event.getId())
                .eq(ProcessEvent::getEventVersion, event.getEventVersion())
                .set(ProcessEvent::getInputHash, inputHash)
                .set(ProcessEvent::getPayloadReady, Boolean.TRUE)
                .set(ProcessEvent::getEventVersion, event.getEventVersion() + 1)
                .set(ProcessEvent::getUpdateTime, now));
            if (eventRows != 1) {
                throw new ServiceException("入站证据事件版本竞争: " + value.definition().getEventCode());
            }
            eligibilityService.recompute(event.getId());
        }
    }

    private record ValidatedEvidence(ProcessEventDefinition definition, ProcessTriggerReceipt receipt) {
    }

    private static final class InputEvidenceException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private InputEvidenceException(String message) {
            super(message);
        }
    }

    /**
     * 准入结果。
     *
     * @param admitted 是否准入
     * @param view 当前结果
     * @param command 原命令
     * @param instance 业务实例
     * @param supervisor 监督实例
     * @param supervisorDefinition 冻结监督定义
     * @param businessDefinition 冻结业务定义
     * @param deadline 绝对执行期限
     * @param roundNo 监督轮次
     * @param owner 本次执行者
     */
    public record Admission(boolean admitted, ImmediateExecutionVO view, ExecuteImmediateCommand command,
                            ProcessInstance instance, ProcessInstance supervisor,
                            PlanDefinition supervisorDefinition, PlanDefinition businessDefinition,
                            LocalDateTime deadline, Long roundNo, String owner) {
    }
}
