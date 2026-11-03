package com.innolux.iws.process.runtime.recovery;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.enums.CheckpointStatus;
import com.innolux.iws.process.enums.CompletionContract;
import com.innolux.iws.process.enums.ControlState;
import com.innolux.iws.process.enums.EditPolicy;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventRole;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.ExecutionPurpose;
import com.innolux.iws.process.enums.GraphEditPolicy;
import com.innolux.iws.process.enums.PermitState;
import com.innolux.iws.process.enums.ScheduleControlScope;
import com.innolux.iws.process.mapper.ProcessActionAuditMapper;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessEventDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.runtime.EventMutationService;
import com.innolux.iws.process.runtime.RecoveryEvidenceService;
import com.innolux.iws.process.runtime.RecoveryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** R4 QUERY 恢复证据消费的真实 MySQL 合同测试。 */
@Tag("dev")
@SpringBootTest(classes = RecoveryEvidenceFixtureApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "iws.process.enabled=true")
@Transactional
class RecoveryEvidenceServiceMysqlTest {

    private static final long DEFINITION_VERSION_ID = 9101L;
    private static final long ORIGINAL_DEFINITION_ID = 9201L;
    private static final long QUERY_DEFINITION_ID = 9202L;
    private static final long INSTANCE_ID = 9301L;
    private static final long ORIGINAL_EVENT_ID = 9401L;
    private static final long QUERY_EVENT_ID = 9402L;
    private static final long ORIGINAL_ATTEMPT_ID = 9501L;
    private static final long QUERY_ATTEMPT_ID = 9502L;
    private static final long ORIGINAL_TOKEN_ID = 9601L;
    private static final long QUERY_TOKEN_ID = 9602L;

    @Autowired RecoveryEvidenceService recoveryEvidenceService;
    @Autowired ProcessEventMapper eventMapper;
    @Autowired ProcessAttemptMapper attemptMapper;
    @Autowired ProcessEventDefinitionMapper definitionMapper;
    @Autowired ProcessScheduleControlMapper controlMapper;
    @Autowired ProcessActionAuditMapper auditMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM iws_process_action_audit");
        jdbcTemplate.execute("DELETE FROM iws_process_attempt");
        jdbcTemplate.execute("DELETE FROM iws_process_request_detail");
        jdbcTemplate.execute("DELETE FROM iws_process_event");
        jdbcTemplate.execute("DELETE FROM iws_process_schedule_control");
        jdbcTemplate.execute("DELETE FROM iws_process_event_definition");
        jdbcTemplate.execute("DELETE FROM iws_process_token");
        jdbcTemplate.execute("DELETE FROM iws_process_instance");
        jdbcTemplate.execute("DELETE FROM iws_process_definition");
    }

    @Test
    void queuesBusinessCompletionOriginalWithReadOnlyEvidenceAndKeepsCall() {
        Fixture fixture = fixture(CompletionContract.BUSINESS_COMPLETION, true);

        RecoveryEvidenceService.Consumption consumed = recoveryEvidenceService
            .consumeQueryEvidence(fixture.queryClaim(), EffectResult.SUCCEEDED);

        assertThat(consumed.outcome()).isEqualTo(RecoveryEvidenceService.Outcome.QUEUED);
        assertThat(consumed.originalEffect()).isEqualTo(EffectResult.SUCCEEDED);
        ProcessEvent original = eventMapper.selectById(ORIGINAL_EVENT_ID);
        assertThat(original.getEventStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(original.getExecutionPurpose()).isEqualTo(ExecutionPurpose.RECOVERY_ONLY);
        assertThat(original.getLastAttemptId()).isEqualTo(consumed.evidenceAttemptId());
        assertThat(original.getEffectResult()).isEqualTo(EffectResult.UNKNOWN);
        assertThat(original.getRecoveryRequested()).isTrue();

        ProcessAttempt oldAttempt = attemptMapper.selectById(ORIGINAL_ATTEMPT_ID);
        assertThat(oldAttempt.getEffectResult()).isEqualTo(EffectResult.UNKNOWN);
        assertThat(oldAttempt.getCheckpointStatus()).isEqualTo(CheckpointStatus.UNKNOWN);
        assertThat(oldAttempt.getEvidenceRef()).isNull();

        ProcessAttempt evidence = attemptMapper.selectById(consumed.evidenceAttemptId());
        assertThat(evidence.getEventId()).isEqualTo(ORIGINAL_EVENT_ID);
        assertThat(evidence.getResumeFromAttemptId()).isEqualTo(ORIGINAL_ATTEMPT_ID);
        assertThat(evidence.getEffectResult()).isEqualTo(EffectResult.SUCCEEDED);
        assertThat(evidence.getCheckpointStatus()).isEqualTo(CheckpointStatus.SUCCEEDED);
        assertThat(evidence.getSent()).isFalse();
        assertThat(evidence.getEvidenceRef()).isEqualTo("query:evidence:original:success");
        assertThat(evidence.getCheckpointJson()).contains("RECOVERY_EVIDENCE", "9402", "9502");
        assertThat(RecoveryPolicy.decide(evidence, true)).isEqualTo(RecoveryPolicy.Decision.COMPLETE);

        ProcessScheduleControl call = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.CALL)
            .eq(ProcessScheduleControl::getScopeKey, String.valueOf(ORIGINAL_EVENT_ID))
            .eq(ProcessScheduleControl::getDuty, "CALL_PERMIT"));
        assertThat(call.getPermitState()).isEqualTo(PermitState.INTENT);
        assertThat(call.getRevision()).isEqualTo(1L);
        assertThat(auditMapper.selectCount(Wrappers.<com.innolux.iws.process.domain.ProcessActionAudit>lambdaQuery()
            .eq(com.innolux.iws.process.domain.ProcessActionAudit::getEventId, ORIGINAL_EVENT_ID)
            .eq(com.innolux.iws.process.domain.ProcessActionAudit::getAttemptId,
                consumed.evidenceAttemptId()))).isOne();
    }

    @Test
    void normalizesAcceptanceWithoutCompletingResultOrReleasingCall() {
        Fixture fixture = fixture(CompletionContract.ACCEPTANCE, true);

        RecoveryEvidenceService.Consumption consumed = recoveryEvidenceService
            .consumeQueryEvidence(fixture.queryClaim(), EffectResult.SUCCEEDED);

        assertThat(consumed.outcome()).isEqualTo(RecoveryEvidenceService.Outcome.QUEUED);
        assertThat(consumed.originalEffect()).isEqualTo(EffectResult.ACCEPTED);
        ProcessAttempt evidence = attemptMapper.selectById(consumed.evidenceAttemptId());
        assertThat(evidence.getEffectResult()).isEqualTo(EffectResult.ACCEPTED);
        assertThat(evidence.getCheckpointStatus()).isEqualTo(CheckpointStatus.ACCEPTED);
        assertThat(evidence.getSent()).isFalse();
        ProcessScheduleControl call = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.CALL)
            .eq(ProcessScheduleControl::getScopeKey, String.valueOf(ORIGINAL_EVENT_ID))
            .eq(ProcessScheduleControl::getDuty, "CALL_PERMIT"));
        assertThat(call.getPermitState()).isEqualTo(PermitState.INTENT);
        assertThat(eventMapper.selectById(QUERY_EVENT_ID).getEventStatus()).isEqualTo(EventStatus.SUCCESS);
    }

    @Test
    void ignoresSuccessfulQueryWithoutTrustedEvidence() {
        Fixture fixture = fixture(CompletionContract.BUSINESS_COMPLETION, false);

        RecoveryEvidenceService.Consumption consumed = recoveryEvidenceService
            .consumeQueryEvidence(fixture.queryClaim(), EffectResult.SUCCEEDED);

        assertThat(consumed.outcome()).isEqualTo(RecoveryEvidenceService.Outcome.NO_EVIDENCE);
        assertThat(attemptMapper.selectCount(Wrappers.<ProcessAttempt>lambdaQuery()
            .eq(ProcessAttempt::getEventId, ORIGINAL_EVENT_ID))).isOne();
        ProcessEvent original = eventMapper.selectById(ORIGINAL_EVENT_ID);
        assertThat(original.getLastAttemptId()).isEqualTo(ORIGINAL_ATTEMPT_ID);
        assertThat(original.getEventStatus()).isEqualTo(EventStatus.MANUAL_PENDING);
        assertThat(attemptMapper.selectById(ORIGINAL_ATTEMPT_ID).getEffectResult()).isEqualTo(EffectResult.UNKNOWN);
    }

    @Test
    void sameQueryEvidenceIsIdempotentAndDoesNotAppendAnotherAttempt() {
        Fixture fixture = fixture(CompletionContract.BUSINESS_COMPLETION, true);
        RecoveryEvidenceService.Consumption first = recoveryEvidenceService
            .consumeQueryEvidence(fixture.queryClaim(), EffectResult.SUCCEEDED);

        RecoveryEvidenceService.Consumption second = recoveryEvidenceService
            .consumeQueryEvidence(fixture.queryClaim(), EffectResult.SUCCEEDED);

        assertThat(first.outcome()).isEqualTo(RecoveryEvidenceService.Outcome.QUEUED);
        assertThat(second.outcome()).isEqualTo(RecoveryEvidenceService.Outcome.ALREADY_QUEUED);
        assertThat(second.evidenceAttemptId()).isEqualTo(first.evidenceAttemptId());
        assertThat(attemptMapper.selectCount(Wrappers.<ProcessAttempt>lambdaQuery()
            .eq(ProcessAttempt::getEventId, ORIGINAL_EVENT_ID))).isEqualTo(2);
        assertThat(eventMapper.selectById(ORIGINAL_EVENT_ID).getLastAttemptId()).isEqualTo(first.evidenceAttemptId());
    }

    @Test
    void expiredIndependentDeadlineDoesNotConsumeEvidence() {
        Fixture fixture = fixture(CompletionContract.BUSINESS_COMPLETION, true);
        eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, QUERY_EVENT_ID)
            .set(ProcessEvent::getRecoveryDeadline, LocalDateTime.now().minusSeconds(1)));
        ProcessEvent query = eventMapper.selectById(QUERY_EVENT_ID);
        ProcessAttempt queryAttempt = attemptMapper.selectById(QUERY_ATTEMPT_ID);

        RecoveryEvidenceService.Consumption consumed = recoveryEvidenceService.consumeQueryEvidence(
            new EventMutationService.ClaimContext(query, queryAttempt), EffectResult.SUCCEEDED);

        assertThat(consumed.outcome()).isEqualTo(RecoveryEvidenceService.Outcome.NOT_QUERY);
        assertThat(attemptMapper.selectCount(Wrappers.<ProcessAttempt>lambdaQuery()
            .eq(ProcessAttempt::getEventId, ORIGINAL_EVENT_ID))).isOne();
    }

    @Test
    void resumedQueryConsumesPriorSentEvidenceWithoutResending() {
        fixture(CompletionContract.BUSINESS_COMPLETION, true);
        ProcessAttempt prior = attemptMapper.selectById(QUERY_ATTEMPT_ID);
        ProcessAttempt resumed = new ProcessAttempt();
        resumed.setId(QUERY_ATTEMPT_ID + 1);
        resumed.setEventId(QUERY_EVENT_ID);
        resumed.setAttemptNo(2);
        resumed.setResumeFromAttemptId(prior.getId());
        resumed.setCheckpointStatus(CheckpointStatus.SUCCEEDED);
        resumed.setEffectResult(EffectResult.SUCCEEDED);
        resumed.setEvidenceRef(prior.getEvidenceRef());
        resumed.setCheckpointJson(prior.getCheckpointJson());
        resumed.setSourceStateCode(prior.getSourceStateCode());
        resumed.setTargetStateCode(prior.getTargetStateCode());
        resumed.setExecutionPurpose(ExecutionPurpose.RECOVERY_ONLY);
        resumed.setSent(false);
        resumed.setOwner(prior.getOwner());
        resumed.setLeaseEpoch(prior.getLeaseEpoch());
        resumed.setStartedAt(LocalDateTime.now());
        resumed.setRecordedAt(LocalDateTime.now());
        attemptMapper.insert(resumed);
        eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate().eq(ProcessEvent::getId, QUERY_EVENT_ID)
            .set(ProcessEvent::getLastAttemptId, resumed.getId()));
        var claim = new EventMutationService.ClaimContext(eventMapper.selectById(QUERY_EVENT_ID), resumed);

        assertThat(recoveryEvidenceService.consumeQueryEvidence(claim, EffectResult.SUCCEEDED).outcome())
            .isEqualTo(RecoveryEvidenceService.Outcome.QUEUED);
        assertThat(attemptMapper.selectById(prior.getId()).getSent()).isTrue();
        assertThat(attemptMapper.selectById(resumed.getId()).getSent()).isFalse();
    }

    private Fixture fixture(CompletionContract completionContract, boolean withEvidence) {
        insertDefinition(ORIGINAL_DEFINITION_ID, "ORIGINAL", completionContract, null);
        insertDefinition(QUERY_DEFINITION_ID, "RECOVERY_QUERY", CompletionContract.BUSINESS_COMPLETION,
            "ORIGINAL");

        ProcessAttempt oldAttempt = new ProcessAttempt();
        oldAttempt.setId(ORIGINAL_ATTEMPT_ID);
        oldAttempt.setEventId(ORIGINAL_EVENT_ID);
        oldAttempt.setAttemptNo(1);
        oldAttempt.setCheckpointStatus(CheckpointStatus.UNKNOWN);
        oldAttempt.setSent(Boolean.TRUE);
        oldAttempt.setRequestId("REQ-ORIGINAL");
        oldAttempt.setInputHash("HASH-ORIGINAL");
        oldAttempt.setIdempotencyKey("IDEM-ORIGINAL");
        oldAttempt.setOperationVersion("1");
        oldAttempt.setEffectResult(EffectResult.UNKNOWN);
        oldAttempt.setSourceStateCode("START");
        oldAttempt.setTargetStateCode("END");
        oldAttempt.setOwner("worker:original");
        oldAttempt.setLeaseEpoch(2L);
        oldAttempt.setExecutionPurpose(ExecutionPurpose.NORMAL);
        oldAttempt.setStartedAt(LocalDateTime.now().minusMinutes(2));
        oldAttempt.setRecordedAt(LocalDateTime.now().minusMinutes(2));
        attemptMapper.insert(oldAttempt);

        ProcessAttempt queryAttempt = new ProcessAttempt();
        queryAttempt.setId(QUERY_ATTEMPT_ID);
        queryAttempt.setEventId(QUERY_EVENT_ID);
        queryAttempt.setAttemptNo(1);
        queryAttempt.setCheckpointStatus(CheckpointStatus.SUCCEEDED);
        queryAttempt.setSent(Boolean.TRUE);
        queryAttempt.setRequestId("REQ-QUERY");
        queryAttempt.setInputHash("HASH-ORIGINAL");
        queryAttempt.setIdempotencyKey("IDEM-QUERY");
        queryAttempt.setOperationVersion("1");
        queryAttempt.setEvidenceRef(withEvidence ? "query:evidence:original:success" : null);
        queryAttempt.setEffectResult(EffectResult.SUCCEEDED);
        queryAttempt.setSourceStateCode("START");
        queryAttempt.setTargetStateCode("END");
        queryAttempt.setOwner("worker:query");
        queryAttempt.setLeaseEpoch(3L);
        queryAttempt.setExecutionPurpose(ExecutionPurpose.RECOVERY_ONLY);
        queryAttempt.setCheckpointJson("{\"status\":\"SUCCEEDED\"}");
        queryAttempt.setStartedAt(LocalDateTime.now().minusSeconds(1));
        queryAttempt.setEndedAt(LocalDateTime.now());
        queryAttempt.setRecordedAt(LocalDateTime.now());
        attemptMapper.insert(queryAttempt);

        LocalDateTime now = LocalDateTime.now();
        ProcessEvent original = new ProcessEvent();
        original.setId(ORIGINAL_EVENT_ID);
        original.setInstanceId(INSTANCE_ID);
        original.setTokenId(ORIGINAL_TOKEN_ID);
        original.setDefinitionVersionId(DEFINITION_VERSION_ID);
        original.setEventDefinitionId(ORIGINAL_DEFINITION_ID);
        original.setEventCode("ORIGINAL");
        original.setEventKey("E:ORIGINAL");
        original.setRoundNo(0L);
        original.setBranchIdentity("ROOT");
        original.setEventRole(EventRole.BUSINESS);
        original.setEventDirection(EventDirection.OUTBOUND);
        original.setSystemCode("ERP");
        original.setCallContractKey("POST_ORDER");
        original.setCallContractVersion("1");
        original.setEffectKey("ORDER");
        original.setSourceStateCode("START");
        original.setTargetStateCode("END");
        original.setEventStatus(EventStatus.MANUAL_PENDING);
        original.setOutcome("UNKNOWN");
        original.setEffectResult(EffectResult.UNKNOWN);
        original.setSent(Boolean.TRUE);
        original.setPayloadReady(Boolean.TRUE);
        original.setInputHash("HASH-ORIGINAL");
        original.setIdempotencyKey("IDEM-ORIGINAL");
        original.setExecutionPurpose(ExecutionPurpose.NORMAL);
        original.setPriority(0);
        original.setRetryCount(1);
        original.setOwner("worker:original");
        original.setLeaseEpoch(2L);
        original.setLeaseUntil(now.minusSeconds(1));
        original.setEventVersion(7L);
        original.setLastAttemptId(ORIGINAL_ATTEMPT_ID);
        original.setRecoveryRequested(Boolean.TRUE);
        original.setRecoveryDeadline(now.plusMinutes(2));
        eventMapper.insert(original);

        ProcessEvent query = new ProcessEvent();
        query.setId(QUERY_EVENT_ID);
        query.setInstanceId(INSTANCE_ID);
        query.setTokenId(QUERY_TOKEN_ID);
        query.setDefinitionVersionId(DEFINITION_VERSION_ID);
        query.setEventDefinitionId(QUERY_DEFINITION_ID);
        query.setEventCode("RECOVERY_QUERY");
        query.setEventKey("E:RECOVERY_QUERY");
        query.setRoundNo(0L);
        query.setBranchIdentity("ROOT");
        query.setEventRole(EventRole.BUSINESS);
        query.setEventDirection(EventDirection.OUTBOUND);
        query.setSystemCode("ERP");
        query.setCallContractKey("QUERY_ORDER");
        query.setCallContractVersion("1");
        query.setEffectKey("QUERY");
        query.setSourceStateCode("START");
        query.setTargetStateCode("END");
        query.setEventStatus(EventStatus.SUCCESS);
        query.setOutcome("SUCCEEDED");
        query.setEffectResult(EffectResult.SUCCEEDED);
        query.setSent(Boolean.TRUE);
        query.setPayloadReady(Boolean.TRUE);
        query.setInputHash("HASH-ORIGINAL");
        query.setIdempotencyKey("IDEM-QUERY");
        query.setOriginOutboundEventId(ORIGINAL_EVENT_ID);
        query.setRecoveryForEventCode("ORIGINAL");
        query.setExecutionPurpose(ExecutionPurpose.RECOVERY_ONLY);
        query.setPriority(0);
        query.setRetryCount(0);
        query.setOwner("worker:query");
        query.setLeaseEpoch(3L);
        query.setLeaseUntil(now);
        query.setEventVersion(4L);
        query.setLastAttemptId(QUERY_ATTEMPT_ID);
        query.setRecoveryRequested(Boolean.TRUE);
        query.setRecoveryDeadline(now.plusMinutes(2));
        eventMapper.insert(query);

        ProcessScheduleControl call = new ProcessScheduleControl();
        call.setId(9701L);
        call.setScopeType(ScheduleControlScope.CALL);
        call.setScopeKey(String.valueOf(ORIGINAL_EVENT_ID));
        call.setDuty("CALL_PERMIT");
        call.setControlState(ControlState.AUTO_RUNNING);
        call.setPermitState(PermitState.INTENT);
        call.setRevision(1L);
        call.setEpoch(2L);
        call.setResourceKey("ORDER");
        call.setLastActivityAt(now);
        controlMapper.insert(call);
        return new Fixture(query, queryAttempt);
    }

    private void insertDefinition(long id, String eventCode, CompletionContract contract,
                                  String recoveryForEventCode) {
        ProcessEventDefinition definition = new ProcessEventDefinition();
        definition.setId(id);
        definition.setDefinitionVersionId(DEFINITION_VERSION_ID);
        definition.setEventCode(eventCode);
        definition.setEventRole(EventRole.BUSINESS);
        definition.setEventDirection(EventDirection.OUTBOUND);
        definition.setCompletionContract(contract);
        definition.setRecoveryForEventCode(recoveryForEventCode);
        definition.setEnabled(Boolean.TRUE);
        definition.setPriority(0);
        definition.setEditPolicy(EditPolicy.EDITABLE);
        definition.setGraphEditPolicy(GraphEditPolicy.EDITABLE_NEW_VERSION);
        definitionMapper.insert(definition);
    }

    private record Fixture(ProcessEvent query, ProcessAttempt queryAttempt) {

        private EventMutationService.ClaimContext queryClaim() {
            return new EventMutationService.ClaimContext(query, queryAttempt);
        }
    }
}
