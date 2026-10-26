/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 消费 QUERY 恢复证据并为原 UNKNOWN 事件排队只读收口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.ProcessActionAudit;
import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.enums.ActionSource;
import com.innolux.iws.process.enums.ActionType;
import com.innolux.iws.process.enums.AuditResult;
import com.innolux.iws.process.enums.CheckpointStatus;
import com.innolux.iws.process.enums.CompletionContract;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.ExecutionPurpose;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessEventDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.service.ProcessAuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将可信 QUERY 结果关联为原 UNKNOWN 事件的只读恢复证据。
 *
 * <p>调用方必须已把本次 QUERY 的事件和 attempt 成功检查点持久化，并在同一事务内调用本服务。
 * 本服务只读取 QUERY attempt，为原事件追加新的证据 attempt，再将其排入 RECOVERY_ONLY；
 * 原 attempt 不变，也不执行外部动作、释放 CALL 或推进 I_RESULT。</p>
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled",
    havingValue = "true")
public class RecoveryEvidenceService {

    private static final String EVIDENCE_STATUS = "RECOVERY_EVIDENCE";

    private final ProcessEventMapper eventMapper;

    private final ProcessAttemptMapper attemptMapper;

    private final ProcessEventDefinitionMapper definitionMapper;

    private final ProcessAuditService auditService;

    private final JsonMapper objectMapper;

    /**
     * 构造 QUERY 恢复证据服务。
     *
     * @param eventMapper 事件 Mapper
     * @param attemptMapper 尝试 Mapper
     * @param definitionMapper 事件定义 Mapper
     * @param auditService 审计服务
     * @param objectMapper JSON Mapper
     */
    public RecoveryEvidenceService(ProcessEventMapper eventMapper, ProcessAttemptMapper attemptMapper,
                                   ProcessEventDefinitionMapper definitionMapper,
                                   ProcessAuditService auditService, JsonMapper objectMapper) {
        this.eventMapper = eventMapper;
        this.attemptMapper = attemptMapper;
        this.definitionMapper = definitionMapper;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * 消费一次成功 QUERY 的可信证据，并排队原 UNKNOWN 事件只补编排。
     *
     * <p>必须在 {@code EventMutationService.finishSuccess} 的 QUERY 事务内、QUERY 事件及 attempt
     * 成功检查点写完后调用。ACCEPTANCE 原事件归一为 ACCEPTED；BUSINESS_COMPLETION 原事件归一为
     * SUCCEEDED。QUERY 自身成功只表示查询完成，不表示原业务或 I_RESULT 已完成。</p>
     *
     * @param queryClaim QUERY 当前认领上下文
     * @param queryResult QUERY 自身结果，仅 SUCCEEDED 可消费
     * @return 消费结果
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Consumption consumeQueryEvidence(EventMutationService.ClaimContext queryClaim, EffectResult queryResult) {
        if (queryClaim == null || queryResult != EffectResult.SUCCEEDED) {
            return new Consumption(Outcome.NOT_QUERY, null, null, null, "非 QUERY 成功结果");
        }
        ProcessEvent query = eventMapper.selectById(queryClaim.event().getId());
        if (!isAuthorizedQuery(query, queryClaim)) {
            return new Consumption(Outcome.NOT_QUERY, null, null, null, "QUERY 授权或终态校验失败");
        }
        ProcessAttempt queryAttempt = attemptMapper.selectById(queryClaim.attempt().getId());
        if (!isSuccessfulQueryAttempt(query, queryAttempt, queryResult)) {
            return new Consumption(Outcome.NOT_QUERY, null, null, null, "QUERY attempt 成功检查点校验失败");
        }
        String evidenceRef = trimToNull(queryAttempt.getEvidenceRef());
        if (evidenceRef == null) {
            return new Consumption(Outcome.NO_EVIDENCE, query.getOriginOutboundEventId(), null, null,
                "QUERY 成功结果缺少 evidenceRef");
        }

        LocalDateTime now = LocalDateTime.now();
        if (query.getRecoveryDeadline() == null || !query.getRecoveryDeadline().isAfter(now)) {
            return new Consumption(Outcome.NOT_QUERY, query.getOriginOutboundEventId(), null, null,
                "QUERY 独立恢复期限已失效");
        }
        ProcessEvent original = eventMapper.selectOne(Wrappers.<ProcessEvent>lambdaQuery()
            .eq(ProcessEvent::getId, query.getOriginOutboundEventId())
            .last("FOR UPDATE"));
        if (!isOriginalUnknown(original, query, now)) {
            return new Consumption(Outcome.ORIGINAL_NOT_QUEUEABLE, query.getOriginOutboundEventId(), null, null,
                "原事件不在可消费的 UNKNOWN 恢复状态");
        }
        ProcessAttempt prior = original.getLastAttemptId() == null ? null
            : attemptMapper.selectById(original.getLastAttemptId());
        if (referencesQuery(prior, query.getId(), queryAttempt.getId())) {
            return new Consumption(Outcome.ALREADY_QUEUED, original.getId(), prior.getId(), prior.getEffectResult(),
                "同一 QUERY 证据已排队");
        }
        if (RecoveryPolicy.decide(prior, Boolean.TRUE.equals(original.getSent())) != RecoveryPolicy.Decision.UNKNOWN) {
            return new Consumption(Outcome.ORIGINAL_NOT_QUEUEABLE, original.getId(), null, null,
                "原事件最新检查点不是 UNKNOWN");
        }
        EffectResult originalEffect = recoveryEffect(original);
        if (originalEffect == null) {
            return new Consumption(Outcome.ORIGINAL_NOT_QUEUEABLE, original.getId(), null, null,
                "原事件完成合同缺失或不支持");
        }

        ProcessAttempt evidence = buildEvidenceAttempt(original, prior, query, queryAttempt, evidenceRef,
            originalEffect, now);
        attemptMapper.insert(evidence);
        int rows = queueOriginal(original, query, evidence, now);
        if (rows != 1) {
            throw new ServiceException("原事件恢复证据排队竞争失败");
        }
        audit(original, evidence, query, queryAttempt, original.getEventVersion() + 1, originalEffect);
        return new Consumption(Outcome.QUEUED, original.getId(), evidence.getId(), originalEffect,
            "QUERY 证据已持久化，原事件仅待补编排");
    }

    private boolean isAuthorizedQuery(ProcessEvent query, EventMutationService.ClaimContext claim) {
        return query != null
            && query.getEventVersion() != null
            && query.getEventVersion() == claim.eventVersion()
            && query.getEventStatus() == EventStatus.SUCCESS
            && query.getEventDirection() == EventDirection.OUTBOUND
            && query.getExecutionPurpose() == ExecutionPurpose.RECOVERY_ONLY
            && Boolean.TRUE.equals(query.getRecoveryRequested())
            && query.getRecoveryForEventCode() != null
            && !query.getRecoveryForEventCode().isBlank()
            && query.getOriginOutboundEventId() != null
            && query.getLastAttemptId() != null
            && query.getLastAttemptId().equals(claim.attempt().getId());
    }

    private boolean isSuccessfulQueryAttempt(ProcessEvent query, ProcessAttempt attempt, EffectResult queryResult) {
        return attempt != null
            && attempt.getEventId() != null
            && attempt.getEventId().equals(query.getId())
            && attempt.getLeaseEpoch() != null
            && attempt.getLeaseEpoch().equals(query.getLeaseEpoch())
            && attempt.getOwner() != null
            && attempt.getOwner().equals(query.getOwner())
            && attempt.getEffectResult() == queryResult
            && attempt.getCheckpointStatus() == CheckpointStatus.SUCCEEDED
            && hasQuerySendEvidence(attempt)
            && Boolean.TRUE.equals(query.getSent());
    }

    private boolean hasQuerySendEvidence(ProcessAttempt attempt) {
        java.util.Set<Long> visited = new java.util.HashSet<>();
        String evidence = attempt.getEvidenceRef();
        Long eventId = attempt.getEventId();
        while (attempt != null && visited.add(attempt.getId())
            && java.util.Objects.equals(eventId, attempt.getEventId())
            && java.util.Objects.equals(evidence, attempt.getEvidenceRef())
            && attempt.getEffectResult() == EffectResult.SUCCEEDED) {
            if (Boolean.TRUE.equals(attempt.getSent())) {
                return true;
            }
            attempt = attempt.getResumeFromAttemptId() == null ? null
                : attemptMapper.selectById(attempt.getResumeFromAttemptId());
        }
        return false;
    }

    private boolean isOriginalUnknown(ProcessEvent original, ProcessEvent query, LocalDateTime now) {
        return original != null
            && original.getInstanceId() != null
            && original.getInstanceId().equals(query.getInstanceId())
            && original.getEventCode() != null
            && original.getEventCode().equals(query.getRecoveryForEventCode())
            && original.getEventDirection() == EventDirection.OUTBOUND
            && original.getRecoveryForEventCode() == null
            && Boolean.TRUE.equals(original.getRecoveryRequested())
            && original.getRecoveryDeadline() != null
            && original.getRecoveryDeadline().isAfter(now)
            && (original.getEventStatus() == EventStatus.PENDING
            || original.getEventStatus() == EventStatus.MANUAL_PENDING
            || original.getEventStatus() == EventStatus.RETRY_WAIT);
    }

    private EffectResult recoveryEffect(ProcessEvent original) {
        ProcessEventDefinition definition = original.getEventDefinitionId() == null ? null
            : definitionMapper.selectById(original.getEventDefinitionId());
        if (definition == null
            || definition.getDefinitionVersionId() == null
            || !definition.getDefinitionVersionId().equals(original.getDefinitionVersionId())
            || !original.getEventCode().equals(definition.getEventCode())) {
            return null;
        }
        CompletionContract contract = definition.getCompletionContract();
        if (contract == CompletionContract.ACCEPTANCE) {
            return EffectResult.ACCEPTED;
        }
        if (contract == CompletionContract.BUSINESS_COMPLETION) {
            return EffectResult.SUCCEEDED;
        }
        return null;
    }

    private ProcessAttempt buildEvidenceAttempt(ProcessEvent original, ProcessAttempt prior, ProcessEvent query,
                                                ProcessAttempt queryAttempt, String evidenceRef,
                                                EffectResult effectResult, LocalDateTime now) {
        long count = attemptMapper.selectCount(Wrappers.<ProcessAttempt>lambdaQuery()
            .eq(ProcessAttempt::getEventId, original.getId()));
        ProcessAttempt evidence = new ProcessAttempt();
        evidence.setId(IdWorker.getId());
        evidence.setEventId(original.getId());
        evidence.setAttemptNo(Math.toIntExact(count + 1));
        evidence.setResumeFromAttemptId(prior == null ? null : prior.getId());
        evidence.setCheckpointStatus(effectResult == EffectResult.ACCEPTED
            ? CheckpointStatus.ACCEPTED : CheckpointStatus.SUCCEEDED);
        evidence.setSent(Boolean.FALSE);
        evidence.setInputHash(original.getInputHash());
        evidence.setIdempotencyKey(original.getIdempotencyKey());
        evidence.setOperationVersion(original.getCallContractVersion());
        evidence.setLocalActionVersion(original.getLocalActionVersion());
        evidence.setLocalEffectKey(original.getLocalEffectKey());
        evidence.setEvidenceRef(evidenceRef);
        evidence.setEffectResult(effectResult);
        evidence.setSourceStateCode(original.getSourceStateCode());
        evidence.setTargetStateCode(original.getTargetStateCode());
        evidence.setLeaseEpoch(original.getLeaseEpoch() == null ? 0L : original.getLeaseEpoch());
        evidence.setExecutionPurpose(ExecutionPurpose.RECOVERY_ONLY);
        evidence.setCheckpointJson(evidenceCheckpoint(query, queryAttempt, effectResult, evidenceRef, now));
        evidence.setStartedAt(now);
        evidence.setEndedAt(now);
        evidence.setRecordedAt(now);
        return evidence;
    }

    private int queueOriginal(ProcessEvent original, ProcessEvent query, ProcessAttempt evidence, LocalDateTime now) {
        LambdaUpdateWrapper<ProcessEvent> update = Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, original.getId())
            .eq(ProcessEvent::getInstanceId, query.getInstanceId())
            .eq(ProcessEvent::getEventCode, query.getRecoveryForEventCode())
            .eq(ProcessEvent::getEventVersion, original.getEventVersion())
            .eq(ProcessEvent::getLeaseEpoch, original.getLeaseEpoch())
            .eq(ProcessEvent::getRecoveryRequested, Boolean.TRUE)
            .in(ProcessEvent::getEventStatus, EventStatus.PENDING, EventStatus.MANUAL_PENDING, EventStatus.RETRY_WAIT)
            .set(ProcessEvent::getEventStatus, EventStatus.PENDING)
            .set(ProcessEvent::getWaitReason, null)
            .set(ProcessEvent::getExecutionPurpose, ExecutionPurpose.RECOVERY_ONLY)
            .set(ProcessEvent::getRecoveryDeadline, query.getRecoveryDeadline())
            .set(ProcessEvent::getLastAttemptId, evidence.getId())
            .set(ProcessEvent::getOwner, null)
            .set(ProcessEvent::getLeaseUntil, now)
            .set(ProcessEvent::getLeaseEpoch, (original.getLeaseEpoch() == null ? 0L : original.getLeaseEpoch()) + 1)
            .set(ProcessEvent::getNextRetryTime, null)
            .set(ProcessEvent::getErrorCode, null)
            .set(ProcessEvent::getErrorMessage, null)
            .set(ProcessEvent::getEventVersion, original.getEventVersion() + 1)
            .set(ProcessEvent::getUpdateTime, now);
        if (original.getLastAttemptId() == null) {
            update.isNull(ProcessEvent::getLastAttemptId);
        } else {
            update.eq(ProcessEvent::getLastAttemptId, original.getLastAttemptId());
        }
        return eventMapper.update(null, update);
    }

    private String evidenceCheckpoint(ProcessEvent query, ProcessAttempt queryAttempt, EffectResult effectResult,
                                      String evidenceRef, LocalDateTime now) {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("status", EVIDENCE_STATUS);
        checkpoint.put("queryEventId", query.getId());
        checkpoint.put("queryEventCode", query.getEventCode());
        checkpoint.put("queryAttemptId", queryAttempt.getId());
        checkpoint.put("queryEvidenceRef", evidenceRef);
        checkpoint.put("originalEffectResult", effectResult.name());
        checkpoint.put("recordedAt", now.toString());
        try {
            return objectMapper.writeValueAsString(checkpoint);
        } catch (JacksonException ex) {
            throw new ServiceException("恢复证据检查点序列化失败", ex);
        }
    }

    private boolean referencesQuery(ProcessAttempt attempt, long queryEventId, long queryAttemptId) {
        if (attempt == null || attempt.getCheckpointJson() == null || attempt.getCheckpointJson().isBlank()) {
            return false;
        }
        try {
            JsonNode checkpoint = objectMapper.readTree(attempt.getCheckpointJson());
            return EVIDENCE_STATUS.equals(checkpoint.path("status").asText())
                && checkpoint.path("queryEventId").asLong() == queryEventId
                && checkpoint.path("queryAttemptId").asLong() == queryAttemptId;
        } catch (JacksonException ex) {
            return false;
        }
    }

    private void audit(ProcessEvent original, ProcessAttempt evidence, ProcessEvent query,
                       ProcessAttempt queryAttempt, long afterVersion, EffectResult effectResult) {
        ProcessActionAudit audit = auditService.newAudit(ActionType.EVENT_EXECUTE, ActionSource.SCHEDULER,
            AuditResult.SUCCESS);
        audit.setInstanceId(original.getInstanceId());
        audit.setTokenId(original.getTokenId());
        audit.setEventId(original.getId());
        audit.setAttemptId(evidence.getId());
        audit.setDefinitionVersionId(original.getDefinitionVersionId());
        audit.setRequestId(queryAttempt.getRequestId());
        audit.setBeforeStatus(original.getEventStatus().name());
        audit.setBeforeVersion(original.getEventVersion());
        audit.setAfterStatus(EventStatus.PENDING.name());
        audit.setAfterVersion(afterVersion);
        audit.setEpoch(evidence.getLeaseEpoch() + 1);
        audit.setReason("QUERY_EVIDENCE_QUEUED");
        audit.setDecisionSummary("source=QUERY;queryEventId=" + query.getId()
            + ";queryAttemptId=" + queryAttempt.getId() + ";originalEffect=" + effectResult.name());
        auditService.append(audit);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 消费结论。
     */
    public enum Outcome {
        /** 不是可消费的 QUERY。 */
        NOT_QUERY,
        /** QUERY 成功但没有可信证据引用。 */
        NO_EVIDENCE,
        /** 原事件已存在同一 QUERY 证据排队。 */
        ALREADY_QUEUED,
        /** 原事件不满足 UNKNOWN 或完成合同条件。 */
        ORIGINAL_NOT_QUEUEABLE,
        /** 已追加证据并排队原事件。 */
        QUEUED
    }

    /**
     * QUERY 证据消费结果。
     *
     * @param outcome 结论
     * @param originalEventId 原事件 ID
     * @param evidenceAttemptId 新增只读证据 attempt ID
     * @param originalEffect 原事件归一效果
     * @param detail 说明
     */
    public record Consumption(Outcome outcome, Long originalEventId, Long evidenceAttemptId,
                              EffectResult originalEffect, String detail) {
    }
}
