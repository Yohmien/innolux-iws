/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 执行/跳过/恢复共用的唯一事件更新入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.domain.ProcessSystemContext;
import com.innolux.iws.process.domain.ProcessTriggerReceipt;
import com.innolux.iws.process.domain.ProcessState;
import com.innolux.iws.process.enums.CallPurpose;
import com.innolux.iws.process.enums.CompletionContract;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.OperationKind;
import com.innolux.iws.process.enums.PostingMode;
import com.innolux.iws.process.enums.WaitReason;
import com.innolux.iws.process.mapper.ProcessTriggerReceiptMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innolux.iws.process.runtime.StateContext;
import com.innolux.iws.process.runtime.StateValidator;
import com.innolux.iws.process.runtime.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 唯一事件推进入口。HTTP 与本地动作均在数据库事务之外执行，收口再走短事务。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class EventUpdateService {

    private static final Logger log = LoggerFactory.getLogger(EventUpdateService.class);

    private final RuntimeDao runtimeDao;

    private final EventMutationService mutationService;

    private final PlanActivationService activationService;

    private final CapabilityRegistry capabilityRegistry;

    private final EndpointTargetResolver targetResolver;

    private final ProcessTriggerReceiptMapper receiptMapper;

    private final ProcessAttemptMapper attemptMapper;

    private final ObjectMapper objectMapper;

    /**
     * 构造更新服务。
     *
     * @param runtimeDao 运行时 DAO
     * @param mutationService 事件写入服务
     * @param activationService 激活服务
     * @param capabilityRegistry 能力注册表
     * @param targetResolver 目标解析器
     * @param receiptMapper 回执 Mapper
     * @param attemptMapper 尝试 Mapper
     * @param objectMapper JSON Mapper
     */
    public EventUpdateService(RuntimeDao runtimeDao, EventMutationService mutationService,
                              PlanActivationService activationService, CapabilityRegistry capabilityRegistry,
                              EndpointTargetResolver targetResolver, ProcessTriggerReceiptMapper receiptMapper,
                              ProcessAttemptMapper attemptMapper, ObjectMapper objectMapper) {
        this.runtimeDao = runtimeDao;
        this.mutationService = mutationService;
        this.activationService = activationService;
        this.capabilityRegistry = capabilityRegistry;
        this.targetResolver = targetResolver;
        this.receiptMapper = receiptMapper;
        this.attemptMapper = attemptMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 推进一个到期事件。
     *
     * @param eventId 事件 ID
     * @param owner 执行者标识
     * @return 更新结果
     */
    public UpdateResult update(long eventId, String owner) {
        ProcessEvent snapshot = runtimeDao.event(eventId);
        boolean dueRetry = snapshot.getEventStatus() == EventStatus.RETRY_WAIT
            && (snapshot.getNextRetryTime() == null || !snapshot.getNextRetryTime().isAfter(LocalDateTime.now()));
        if (snapshot.getEventStatus() != EventStatus.PENDING && !dueRetry) {
            return UpdateResult.RACED;
        }
        PlanDefinition definition = runtimeDao.definition(snapshot.getDefinitionVersionId());
        long leaseMs = definition.getLeaseMs() == null ? 0 : definition.getLeaseMs();
        if (leaseMs <= 0) {
            throw new ServiceException("计划版本未配置 leaseMs，不能认领事件: " + definition.getId());
        }
        EventMutationService.ClaimContext claim;
        try {
            claim = mutationService.claim(eventId, owner == null ? UUID.randomUUID().toString() : owner, leaseMs);
        } catch (EventMutationService.LeaseLostException ex) {
            return UpdateResult.RACED;
        }
        if (claim == null) {
            return UpdateResult.RACED;
        }
        ProcessEvent event = claim.event();
        ProcessInstance instance = runtimeDao.instance(event.getInstanceId());
        ProcessEventDefinition eventDefinition = runtimeDao.graph(event.getDefinitionVersionId())
            .event(event.getEventCode());
        try {
            ProcessAttempt prior = claim.attempt().getResumeFromAttemptId() == null ? null
                : attemptMapper.selectById(claim.attempt().getResumeFromAttemptId());
            RecoveryPolicy.Decision recovery = RecoveryPolicy.decide(prior, Boolean.TRUE.equals(event.getSent()));
            if (recovery == RecoveryPolicy.Decision.COMPLETE) {
                mutationService.recordResult(claim, new ActionResult(prior.getEffectResult(),
                    prior.getEvidenceRef(), prior.getRequestId(), null, null, null, prior.getCheckpointJson(),
                    "复用已存成功检查点"));
                mutationService.finishSuccess(claim, prior.getEffectResult());
                return prior.getEffectResult() == EffectResult.SKIPPED_MODE
                    ? UpdateResult.SKIPPED : UpdateResult.SUCCEEDED;
            }
            if (recovery == RecoveryPolicy.Decision.UNKNOWN) {
                mutationService.fail(claim, EffectResult.UNKNOWN, "PRIOR_UNKNOWN",
                    "已有发送意图或未知结果，禁止自动重发");
                activationService.updatePlanStatus(event.getInstanceId());
                return UpdateResult.UNKNOWN;
            }
            ValidationResult structural = validateState(event, event.getSourceStateCode(), false);
            if (!structural.passed()) {
                mutationService.fail(claim, EffectResult.FAILED, "STRUCTURAL_VALIDATION", structural.message());
                activationService.updatePlanStatus(event.getInstanceId());
                return UpdateResult.FAILED;
            }
            if (!Boolean.TRUE.equals(eventDefinition.getEnabled())) {
                mutationService.recordResult(claim, new ActionResult(EffectResult.SKIPPED, null, null, null,
                    null, null, null, "整事件禁用，零动作跳过"));
                mutationService.finishSuccess(claim, EffectResult.SKIPPED);
                return UpdateResult.SKIPPED;
            }
            ProcessSystemContext context = runtimeDao.systemContext(event.getInstanceId(), event.getSystemCode());
            PostingMode mode = context == null ? PostingMode.AUTOMATIC : context.getPostingMode();
            if (event.getEventDirection() == EventDirection.OUTBOUND && mode == PostingMode.BYPASS
                && eventDefinition.getOperationKind() == OperationKind.EXTERNAL_POSTING
                && !eventDefinition.getSystemCode().equals(definition.getSelfSystemCode())) {
                mutationService.recordResult(claim, new ActionResult(EffectResult.SKIPPED_MODE, null, null, null,
                    null, null, null, "系统模式屏蔽，零发送"));
                mutationService.finishSuccess(claim, EffectResult.SKIPPED_MODE);
                return UpdateResult.SKIPPED;
            }
            if (event.getEventDirection() == EventDirection.INTERNAL) {
                var action = capabilityRegistry.localAction(eventDefinition.getLocalActionKey(),
                    eventDefinition.getLocalActionVersion());
                ProcessAttempt attempt = mutationService.executeLocalAndFinish(claim, action,
                    actionContext(eventDefinition, event, definition, Map.<String, String>of(),
                        payloadJson(Map.of("eventKey", event.getEventKey(), "inputHash", event.getInputHash(),
                            "localEffectKey", event.getLocalEffectKey(), "attemptId", claim.attempt().getId())),
                        "attempt:" + claim.attempt().getId() + ":input"));
                if (attempt.getEffectResult() == EffectResult.SUCCEEDED) {
                    return UpdateResult.SUCCEEDED;
                }
                activationService.updatePlanStatus(event.getInstanceId());
                return attempt.getEffectResult() == EffectResult.UNKNOWN ? UpdateResult.UNKNOWN : UpdateResult.FAILED;
            }
            ActionResult result;
            if (event.getEventDirection() == EventDirection.INBOUND) {
                result = executeInbound(eventDefinition, event, definition);
            } else {
                result = executeOutbound(eventDefinition, event, definition, claim);
            }
            mutationService.recordResult(claim, result);
            if (result.effectResult() == EffectResult.SUCCEEDED) {
                ValidationResult target = validateState(event, event.getTargetStateCode(), true);
                if (!target.passed()) {
                    mutationService.fail(claim, EffectResult.UNKNOWN, "TARGET_VALIDATION", target.message());
                    activationService.updatePlanStatus(event.getInstanceId());
                    return UpdateResult.UNKNOWN;
                }
                mutationService.finishSuccess(claim, EffectResult.SUCCEEDED);
                return UpdateResult.SUCCEEDED;
            }
            if (result.effectResult() == EffectResult.ACCEPTED
                && eventDefinition.getCompletionContract() == CompletionContract.ACCEPTANCE) {
                ValidationResult target = validateState(event, event.getTargetStateCode(), true);
                if (!target.passed()) {
                    mutationService.fail(claim, EffectResult.UNKNOWN, "TARGET_VALIDATION", target.message());
                    activationService.updatePlanStatus(event.getInstanceId());
                    return UpdateResult.UNKNOWN;
                }
                mutationService.finishSuccess(claim, EffectResult.ACCEPTED);
                return UpdateResult.SUCCEEDED;
            }
            EffectResult failure = result.effectResult() == EffectResult.UNKNOWN ? EffectResult.UNKNOWN
                : EffectResult.FAILED;
            mutationService.fail(claim, failure, failure.name(), result.message());
            activationService.updatePlanStatus(event.getInstanceId());
            return failure == EffectResult.UNKNOWN ? UpdateResult.UNKNOWN : UpdateResult.FAILED;
        } catch (EventMutationService.LeaseLostException ex) {
            return UpdateResult.RACED;
        } catch (EventMutationService.CapacityUnavailableException ex) {
            mutationService.requeueForCapacity(claim);
            return UpdateResult.RACED;
        } catch (EventMutationService.TargetValidationException ex) {
            mutationService.fail(claim, EffectResult.UNKNOWN, "TARGET_VALIDATION", ex.getMessage());
            activationService.updatePlanStatus(event.getInstanceId());
            return UpdateResult.UNKNOWN;
        } catch (EventMutationService.SendBlockedException ex) {
            mutationService.fail(claim, EffectResult.FAILED, ex.code(), ex.getMessage());
            activationService.updatePlanStatus(event.getInstanceId());
            return UpdateResult.FAILED;
        } catch (RuntimeException ex) {
            log.warn("计划事件执行异常: eventId={}", eventId, ex);
            try {
                mutationService.fail(claim, EffectResult.FAILED, "EXECUTION_ERROR", ex.getMessage());
                activationService.updatePlanStatus(event.getInstanceId());
            } catch (RuntimeException ignored) {
                // 代次已失效时不得覆盖新执行者。
            }
            return UpdateResult.FAILED;
        }
    }

    private ActionResult executeInbound(ProcessEventDefinition definition, ProcessEvent event,
                                        PlanDefinition planDefinition) {
        var operation = capabilityRegistry.operation(definition.getCallContractKey(),
            definition.getCallContractVersion());
        ProcessTriggerReceipt receipt = receiptMapper.selectOne(Wrappers.<ProcessTriggerReceipt>lambdaQuery()
            .eq(ProcessTriggerReceipt::getEventId, event.getId())
            .eq(ProcessTriggerReceipt::getCanonical, Boolean.TRUE)
            .last("LIMIT 1"));
        Map<String, String> facts = new LinkedHashMap<>();
        Map<String, Object> payload = new LinkedHashMap<>();
        String evidenceRef = "event:" + event.getId() + ":input";
        if (receipt != null) {
            facts.put("receiptId", String.valueOf(receipt.getId()));
            facts.put("sourceRequestId", receipt.getSourceRequestId());
            facts.put("callerSystemCode", receipt.getCallerSystemCode());
            payload.put("receiptId", receipt.getId());
            payload.put("sourceRequestId", receipt.getSourceRequestId());
            payload.put("callerSystemCode", receipt.getCallerSystemCode());
            payload.put("moduleCode", receipt.getModuleCode());
            payload.put("payloadHash", receipt.getPayloadHash());
            payload.put("evidenceSummary", receipt.getEvidenceSummary());
            evidenceRef = "receipt:" + receipt.getId();
        }
        payload.put("eventKey", event.getEventKey());
        payload.put("inputHash", event.getInputHash());
        ActionContext context = actionContext(definition, event, planDefinition, facts,
            payloadJson(payload), evidenceRef);
        return operation.execute(context);
    }

    private ActionResult executeOutbound(ProcessEventDefinition definition, ProcessEvent event,
                                         PlanDefinition planDefinition, EventMutationService.ClaimContext claim) {
        if (definition.getTargetSystemCode() == null || definition.getEndpointRef() == null) {
            return ActionResult.unknown("出站目标系统或地址引用未配置");
        }
        EndpointTargetResolver.ResolvedEndpoint target = targetResolver.resolve(
            definition.getTargetSystemCode(), definition.getEndpointRef());
        var contract = capabilityRegistry.callContract(definition.getCallContractKey(),
            definition.getCallContractVersion());
        if (contract.resourceKey() == null || contract.resourceKey().isBlank()) {
            return ActionResult.unknown("调用合同缺少 resourceKey，容量控制 fail-closed");
        }
        String requestId = UUID.randomUUID().toString();
        boolean recoveryQuery = event.getExecutionPurpose() == com.innolux.iws.process.enums.ExecutionPurpose.RECOVERY_ONLY
            && event.getRecoveryForEventCode() != null;
        Long callScopeEventId = recoveryQuery ? event.getOriginOutboundEventId() : null;
        mutationService.beginSend(claim, requestId, new SendTarget(target.addressId(), target.addressRevision(),
            target.catalogRevision(), target.environmentCode(),
            definition.getTargetSystemCode(), contract.key(), contract.version(),
            contract.httpMethod(), contract.apiPath(), contract.resourceKey(), event.getIdempotencyKey(),
            target.baseUrl(), callScopeEventId, recoveryQuery));
        var operation = capabilityRegistry.operation(definition.getCallContractKey(),
            definition.getCallContractVersion());
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("endpointBaseUrl", target.baseUrl());
        facts.put("environmentCode", target.environmentCode());
        facts.put("catalogRevision", target.catalogRevision());
        facts.put("httpMethod", contract.httpMethod());
        facts.put("apiPath", contract.apiPath());
        facts.put("targetSystemCode", definition.getTargetSystemCode());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventKey", event.getEventKey());
        payload.put("inputHash", event.getInputHash());
        payload.put("idempotencyKey", event.getIdempotencyKey());
        payload.put("attemptId", claim.attempt().getId());
        payload.put("businessInputRef", event.getIdempotencyKey());
        String evidenceRef = "attempt:" + claim.attempt().getId() + ":input";
        ActionContext context = actionContext(definition, event, planDefinition, facts,
            payloadJson(payload), evidenceRef);
        context = new ActionContext(context.eventId(), context.instanceId(), context.eventKey(), context.attemptId(),
            requestId, context.direction(), context.postingMode(), context.effectKey(), context.localEffectKey(),
            context.idempotencyKey(), context.inputHash(), context.payloadJson(), context.evidenceRef(),
            context.leaseEpoch(), context.callTimeoutMs(), context.facts());
        return operation.execute(context);
    }

    private ActionResult executeInternal(ProcessEventDefinition definition, ProcessEvent event,
                                         PlanDefinition planDefinition) {
        var action = capabilityRegistry.localAction(definition.getLocalActionKey(),
            definition.getLocalActionVersion());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventKey", event.getEventKey());
        payload.put("inputHash", event.getInputHash());
        payload.put("localEffectKey", event.getLocalEffectKey());
        payload.put("attemptId", event.getLastAttemptId());
        return action.execute(actionContext(definition, event, planDefinition, Map.of(),
            payloadJson(payload), "attempt:" + event.getLastAttemptId() + ":input"));
    }

    private ActionContext actionContext(ProcessEventDefinition definition, ProcessEvent event,
                                        PlanDefinition planDefinition, Map<String, String> facts,
                                        String payloadJson, String evidenceRef) {
        ProcessInstance instance = runtimeDao.instance(event.getInstanceId());
        ProcessSystemContext systemContext = runtimeDao.systemContext(event.getInstanceId(), event.getSystemCode());
        long remaining = remainingBudget(instance, event, planDefinition);
        return new ActionContext(event.getId(), event.getInstanceId(), event.getEventKey(), event.getLastAttemptId(),
            null, event.getEventDirection(), systemContext == null ? PostingMode.AUTOMATIC
            : systemContext.getPostingMode(), event.getEffectKey(), event.getLocalEffectKey(),
            event.getIdempotencyKey(), event.getInputHash(), payloadJson, evidenceRef,
            event.getLeaseEpoch() == null ? 0 : event.getLeaseEpoch(), remaining, facts);
    }

    private String payloadJson(Map<String, ?> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new ServiceException("输入引用序列化失败", ex);
        }
    }

    private long remainingBudget(ProcessInstance instance, ProcessEvent event, PlanDefinition definition) {
        if (event.getExecutionPurpose() == com.innolux.iws.process.enums.ExecutionPurpose.RECOVERY_ONLY) {
            return event.getRecoveryDeadline() == null ? 0
                : Math.max(0, Duration.between(LocalDateTime.now(), event.getRecoveryDeadline()).toMillis());
        }
        if (instance.getExecutionDeadline() != null) {
            long millis = Duration.between(LocalDateTime.now(), instance.getExecutionDeadline()).toMillis();
            return Math.max(0, millis);
        }
        if (definition.getMaxExecutionMs() != null) {
            return definition.getMaxExecutionMs();
        }
        return definition.getLeaseMs() == null ? 0 : definition.getLeaseMs();
    }

    private ValidationResult validateState(ProcessEvent event, String stateCode, boolean businessPhase) {
        ProcessState state = runtimeDao.graph(event.getDefinitionVersionId()).state(stateCode);
        if (state == null || state.getValidatorKey() == null || state.getValidatorKey().isBlank()) {
            return ValidationResult.fail("VALIDATOR_MISSING", "状态未绑定校验器: " + stateCode);
        }
        ProcessSystemContext systemContext = runtimeDao.systemContext(event.getInstanceId(), event.getSystemCode());
        StateContext context = new StateContext(event.getInstanceId(), event.getTokenId(), event.getId(),
            event.getSourceStateCode(), event.getTargetStateCode(),
            systemContext == null ? PostingMode.AUTOMATIC : systemContext.getPostingMode(),
            event.getInputHash(), event.getEffectResult() == null ? null : event.getEffectResult().name(), Map.of());
        StateValidator validator = capabilityRegistry.validator(state.getValidatorKey(), state.getValidatorVersion());
        ValidationResult result = businessPhase ? validator.validateBusiness(context)
            : validator.validateStructural(context);
        return result == null ? ValidationResult.fail("VALIDATOR_NULL", "校验器返回空结果") : result;
    }

    /**
     * 事件推进结果。
     */
    public enum UpdateResult {
        SUCCEEDED,
        SKIPPED,
        FAILED,
        UNKNOWN,
        RACED
    }
}
