package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.process.config.ProcessProperties;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.domain.ProcessToken;
import com.innolux.iws.process.enums.CompletionPolicy;
import com.innolux.iws.process.enums.DefinitionStatus;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventRole;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.ExecutionPurpose;
import com.innolux.iws.process.enums.PermitState;
import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.PlanStatus;
import com.innolux.iws.process.enums.ScheduleControlScope;
import com.innolux.iws.process.enums.TokenRole;
import com.innolux.iws.process.enums.TokenStatus;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** D03/D04/D05/D16 真实 MySQL 事务与并发。 */
@Tag("dev")
@SpringBootTest(classes = RuntimeFixtureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "iws.process.enabled=true")
class EventRuntimeMysqlTest {

    @Autowired EventMutationService mutationService;
    @Autowired CallCapacityService capacityService;
    @Autowired PlanDefinitionMapper definitionMapper;
    @Autowired ProcessInstanceMapper instanceMapper;
    @Autowired ProcessEventMapper eventMapper;
    @Autowired ProcessTokenMapper tokenMapper;
    @Autowired ProcessAttemptMapper attemptMapper;
    @Autowired ProcessScheduleControlMapper controlMapper;
    @Autowired ProcessProperties properties;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM iws_process_attempt");
        jdbcTemplate.execute("DELETE FROM iws_process_request_detail");
        jdbcTemplate.execute("DELETE FROM iws_process_event");
        jdbcTemplate.execute("DELETE FROM iws_process_token");
        jdbcTemplate.execute("DELETE FROM iws_process_schedule_control");
        jdbcTemplate.execute("DELETE FROM iws_process_action_audit");
        jdbcTemplate.execute("DELETE FROM iws_process_instance");
        jdbcTemplate.execute("DELETE FROM iws_process_event_definition");
        jdbcTemplate.execute("DELETE FROM iws_process_definition");
        jdbcTemplate.execute("DELETE FROM runtime_test_effect");
        properties.getCallCapacities().clear();
    }

    @Test
    void d03ConcurrentClaimHasSingleWinner() throws Exception {
        Fixture fixture = fixture(100, 200, 300, 400);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> first = () -> {
            start.await();
            try {
                return mutationService.claim(fixture.eventId, "A", 60000L) != null;
            } catch (EventMutationService.LeaseLostException ex) {
                return false;
            }
        };
        Callable<Boolean> second = () -> {
            start.await();
            try {
                return mutationService.claim(fixture.eventId, "B", 60000L) != null;
            } catch (EventMutationService.LeaseLostException ex) {
                return false;
            }
        };
        Future<Boolean> a = pool.submit(first);
        Future<Boolean> b = pool.submit(second);
        start.countDown();
        int winners = (a.get() ? 1 : 0) + (b.get() ? 1 : 0);
        pool.shutdownNow();
        assertThat(winners).isEqualTo(1);
        assertThat(attemptMapper.selectCount(Wrappers.<ProcessAttempt>lambdaQuery()
            .eq(ProcessAttempt::getEventId, fixture.eventId))).isEqualTo(1);
    }

    @Test
    void d04T2CancelAndDeadlineDoNotWriteSendIntent() {
        Fixture fixture = fixture(110, 210, 310, 410);
        EventMutationService.ClaimContext claim = mutationService.claim(fixture.eventId, "A", 60000L);
        assertThat(claim).isNotNull();
        instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, fixture.instanceId)
            .set(ProcessInstance::getCancelRequested, Boolean.TRUE));
        assertThatThrownBy(() -> mutationService.beginSend(claim, "req-cancel", "target", "RES"))
            .isInstanceOf(EventMutationService.SendBlockedException.class)
            .hasMessageContaining("取消");
        assertThat(controlMapper.selectCount(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.CALL))).isZero();

        Fixture expired = fixture(111, 211, 311, 411);
        EventMutationService.ClaimContext expiredClaim = mutationService.claim(expired.eventId, "B", 60000L);
        instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, expired.instanceId)
            .set(ProcessInstance::getExecutionDeadline, LocalDateTime.now().minusSeconds(1)));
        assertThatThrownBy(() -> mutationService.beginSend(expiredClaim, "req-deadline", "target", "RES"))
            .isInstanceOf(EventMutationService.SendBlockedException.class)
            .hasMessageContaining("期限");
        assertThat(controlMapper.selectCount(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.CALL))).isZero();
    }

    @Test
    void d05LocalEffectRollsBackWhenCheckpointCannotCommit() {
        Fixture fixture = fixture(120, 220, 320, 420);
        EventMutationService.ClaimContext claim = mutationService.claim(fixture.eventId, "A", 60000L);
        assertThat(claim).isNotNull();
        tokenMapper.deleteById(fixture.tokenId);
        TestLocalAction action = new TestLocalAction(jdbcTemplate);
        ActionContext context = new ActionContext(fixture.eventId, fixture.instanceId, "E:TEST",
            claim.attempt().getId(), null, EventDirection.INTERNAL, com.innolux.iws.process.enums.PostingMode.AUTOMATIC,
            null, "LOCAL", "LOCAL", "hash", null, null, claim.attempt().getLeaseEpoch(), 60000L, Map.of());
        assertThatThrownBy(() -> mutationService.executeLocalAndFinish(claim, action, context)).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM runtime_test_effect", Integer.class)).isZero();
    }

    @Test
    void d16CapacityFailsClosedAndInheritsOwnPermit() {
        properties.getCallCapacities().put("RES", 1);
        assertThat(capacityService.tryAcquire("MISSING", 1L)).isFalse();
        assertThat(capacityService.tryAcquire("RES", 10L)).isTrue();
        ProcessScheduleControl permit = new ProcessScheduleControl();
        permit.setId(900L);
        permit.setScopeType(ScheduleControlScope.CALL);
        permit.setScopeKey("10");
        permit.setDuty("CALL_PERMIT");
        permit.setControlState(com.innolux.iws.process.enums.ControlState.AUTO_RUNNING);
        permit.setPermitState(PermitState.INTENT);
        permit.setResourceKey("RES");
        permit.setRevision(1L);
        controlMapper.insert(permit);
        assertThat(capacityService.tryAcquire("RES", 10L)).isTrue();
        assertThat(capacityService.tryAcquire("RES", 11L)).isFalse();
    }

    @Test
    void successfulCheckpointSurvivesLeaseRecoveryWithoutRewritingHistory() {
        Fixture fixture = fixture(130, 230, 330, 430);
        definitionMapper.update(null, Wrappers.<PlanDefinition>lambdaUpdate()
            .eq(PlanDefinition::getId, 130L).set(PlanDefinition::getMaxExecutionMs, 60000L));
        EventMutationService.ClaimContext claim = mutationService.claim(fixture.eventId, "A", 60000L);
        mutationService.recordResult(claim, ActionResult.succeeded("effect:330"));
        expireLease(fixture.eventId);

        mutationService.recoverExpiredLeases();

        ProcessEvent recovered = eventMapper.selectById(fixture.eventId);
        assertThat(recovered.getEventStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(recovered.getExecutionPurpose()).isEqualTo(ExecutionPurpose.RECOVERY_ONLY);
        assertThat(recovered.getRecoveryDeadline()).isAfter(LocalDateTime.now());
        ProcessAttempt original = attemptMapper.selectById(claim.attempt().getId());
        assertThat(original.getEffectResult()).isEqualTo(EffectResult.SUCCEEDED);
        assertThat(original.getEvidenceRef()).isEqualTo("effect:330");
        EventMutationService.ClaimContext resumed = mutationService.claim(fixture.eventId, "B", 60000L);
        assertThat(resumed).isNotNull();
        assertThat(resumed.attempt().getResumeFromAttemptId()).isEqualTo(original.getId());
        assertThat(resumed.attempt().getEffectResult()).isEqualTo(EffectResult.SUCCEEDED);
        expireLease(fixture.eventId);
        mutationService.recoverExpiredLeases();
        assertThat(eventMapper.selectById(fixture.eventId).getEventStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(eventMapper.selectById(fixture.eventId).getExecutionPurpose()).isEqualTo(ExecutionPurpose.RECOVERY_ONLY);
    }

    @Test
    void immediateLeaseExpiryRequiresManualActionAndCannotBeClaimedAgain() {
        Fixture fixture = fixture(131, 231, 331, 431);
        instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, fixture.instanceId).set(ProcessInstance::getModeSnapshot, PlanMode.IMMEDIATE));
        mutationService.claim(fixture.eventId, "A", 60000L);
        expireLease(fixture.eventId);

        mutationService.recoverExpiredLeases();

        assertThat(eventMapper.selectById(fixture.eventId).getEventStatus()).isEqualTo(EventStatus.MANUAL_PENDING);
        assertThat(mutationService.claim(fixture.eventId, "B", 60000L)).isNull();
    }

    @Test
    void outboundResultPreservesFrozenTargetAndRecordsOneRealInteraction() {
        Fixture fixture = fixture(132, 232, 332, 432);
        jdbcTemplate.update("INSERT INTO iws_process_event_definition"
            + "(id,definition_version_id,event_code,purpose,edit_policy,graph_edit_policy,create_time,update_time)"
            + " VALUES (?,?,?,?,?,?,NOW(),NOW())", 1L, 132L, "TEST", "EXECUTE_ACTION", "EDITABLE", "EDITABLE_NEW_VERSION");
        properties.getCallCapacities().put("RES", 1);
        eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, fixture.eventId).set(ProcessEvent::getEventDirection, EventDirection.OUTBOUND));
        EventMutationService.ClaimContext claim = mutationService.claim(fixture.eventId, "A", 60000L);
        mutationService.beginSend(claim, "REQ-FROZEN", new SendTarget("10", "2", "3", "TEST", "ERP",
            "POST", "1", "POST", "/execute", "RES", "IDEM:332", "http://127.0.0.1", null, false));
        ActionResult result = new ActionResult(EffectResult.SUCCEEDED, "effect:332", "REQ-FROZEN", null,
            200, null, "{\"sendIntent\":{\"target\":\"changed\"},\"receipt\":\"ok\"}", "成功");
        mutationService.recordResult(claim, result);
        mutationService.recordResult(claim, result);

        String checkpoint = attemptMapper.selectById(claim.attempt().getId()).getCheckpointJson();
        assertThat(checkpoint).contains("http://127.0.0.1", "addressRevision", "catalogRevision", "receipt");
        assertThat(checkpoint).doesNotContain("changed");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iws_process_request_detail WHERE request_id=?",
            Integer.class, "REQ-FROZEN")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT direction FROM iws_process_request_detail WHERE request_id=?",
            String.class, "REQ-FROZEN")).isEqualTo("OUTBOUND");
    }

    private void expireLease(long eventId) {
        eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, eventId).set(ProcessEvent::getLeaseUntil, LocalDateTime.now().minusSeconds(1)));
    }

    @Test
    void orchestrationFailureRetainsSuccessfulCheckpoint() {
        Fixture fixture = fixture(133, 233, 333, 433);
        EventMutationService.ClaimContext claim = mutationService.claim(fixture.eventId, "A", 60000L);
        mutationService.recordResult(claim, ActionResult.succeeded("effect:333"));

        mutationService.fail(claim, EffectResult.UNKNOWN, "TARGET_VALIDATION", "目标暂未满足");

        ProcessAttempt attempt = attemptMapper.selectById(claim.attempt().getId());
        assertThat(attempt.getEffectResult()).isEqualTo(EffectResult.SUCCEEDED);
        assertThat(attempt.getEvidenceRef()).isEqualTo("effect:333");
        assertThat(RecoveryPolicy.decide(attempt, false)).isEqualTo(RecoveryPolicy.Decision.COMPLETE);
        assertThat(eventMapper.selectById(fixture.eventId).getEffectResult()).isEqualTo(EffectResult.SUCCEEDED);
    }

    @Test
    void failureAfterSendIntentRemainsUnknownWithCallHeld() {
        Fixture fixture = fixture(134, 234, 334, 434);
        properties.getCallCapacities().put("RES", 1);
        EventMutationService.ClaimContext claim = mutationService.claim(fixture.eventId, "A", 60000L);
        mutationService.beginSend(claim, "REQ-UNKNOWN", "target", "RES");

        mutationService.fail(claim, EffectResult.FAILED, "EXECUTION_ERROR", "响应解析失败");

        assertThat(attemptMapper.selectById(claim.attempt().getId()).getEffectResult()).isEqualTo(EffectResult.UNKNOWN);
        assertThat(eventMapper.selectById(fixture.eventId).getEffectResult()).isEqualTo(EffectResult.UNKNOWN);
        assertThat(instanceMapper.selectById(fixture.instanceId).getPlanStatus()).isEqualTo(PlanStatus.EXCEPTION_HOLD);
        assertThat(controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.CALL)
            .eq(ProcessScheduleControl::getScopeKey, String.valueOf(fixture.eventId))).getPermitState())
            .isNotEqualTo(PermitState.RELEASED);
    }

    @Test
    void recoveryQueryUsesIndependentDeadlineAndOriginalCall() {
        Fixture fixture = fixture(135, 235, 335, 435);
        instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, fixture.instanceId)
            .set(ProcessInstance::getStopRequested, true)
            .set(ProcessInstance::getExecutionDeadline, LocalDateTime.now().minusMinutes(1)));
        eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, fixture.eventId)
            .set(ProcessEvent::getExecutionPurpose, ExecutionPurpose.RECOVERY_ONLY)
            .set(ProcessEvent::getRecoveryRequested, true)
            .set(ProcessEvent::getRecoveryForEventCode, "ORIGINAL")
            .set(ProcessEvent::getOriginOutboundEventId, 999L)
            .set(ProcessEvent::getRecoveryDeadline, LocalDateTime.now().plusMinutes(1)));
        ProcessScheduleControl permit = new ProcessScheduleControl();
        permit.setScopeType(ScheduleControlScope.CALL);
        permit.setScopeKey("999");
        permit.setDuty("CALL_PERMIT");
        permit.setControlState(com.innolux.iws.process.enums.ControlState.AUTO_RUNNING);
        permit.setPermitState(PermitState.INTENT);
        permit.setRevision(1L);
        controlMapper.insert(permit);
        EventMutationService.ClaimContext claim = mutationService.claim(fixture.eventId, "A", 60000L);

        mutationService.beginSend(claim, "REQ-QUERY", new SendTarget("10", "2", "3", "TEST", "ERP",
            "QUERY", "1", "GET", "/query", "RES", "IDEM:335", "http://127.0.0.1", 999L, true));

        assertThat(controlMapper.selectCount(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.CALL))).isEqualTo(1);
        assertThat(instanceMapper.selectById(fixture.instanceId).getStopRequested()).isTrue();
        assertThat(instanceMapper.selectById(fixture.instanceId).getExecutionDeadline()).isBefore(LocalDateTime.now());
    }

    @Test
    void leaseExpiringWhileWaitingForInstanceLockCannotCreateSendIntent() throws Exception {
        Fixture fixture = fixture(136, 236, 336, 436);
        properties.getCallCapacities().put("RES", 1);
        EventMutationService.ClaimContext claim = mutationService.claim(fixture.eventId, "A", 300L);
        var pool = Executors.newSingleThreadExecutor();
        try (var connection = jdbcTemplate.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM iws_process_instance WHERE id=? FOR UPDATE")) {
                statement.setLong(1, fixture.instanceId);
                statement.executeQuery().close();
            }
            var entered = new CountDownLatch(1);
            var sending = pool.submit(() -> {
                entered.countDown();
                return mutationService.beginSend(claim, "REQ-EXPIRED", "target", "RES");
            });
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            Thread.sleep(600L);
            connection.commit();
            assertThatThrownBy(() -> sending.get(5, java.util.concurrent.TimeUnit.SECONDS))
                .hasCauseInstanceOf(EventMutationService.LeaseLostException.class);
        } finally {
            pool.shutdownNow();
        }
        assertThat(controlMapper.selectCount(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.CALL))).isZero();
        assertThat(attemptMapper.selectById(claim.attempt().getId()).getSent()).isFalse();
    }

    private Fixture fixture(long definitionId, long instanceId, long eventId, long tokenId) {
        PlanDefinition definition = new PlanDefinition();
        definition.setId(definitionId);
        definition.setPlanCode("TEST_" + definitionId);
        definition.setPlanVersion(1);
        definition.setPlanName("测试计划");
        definition.setPlanRole(PlanRole.BUSINESS);
        definition.setStatus(DefinitionStatus.ACTIVE);
        definition.setRevision(1L);
        definition.setSelfSystemCode("SELF");
        definition.setCompletionPolicy(CompletionPolicy.ALL_SELECTED);
        definition.setLeaseMs(60000L);
        definition.setDelFlag("0");
        definitionMapper.insert(definition);

        ProcessInstance instance = new ProcessInstance();
        instance.setId(instanceId);
        instance.setPlanRole(PlanRole.BUSINESS);
        instance.setDefinitionVersionId(definitionId);
        instance.setDefinitionRevision(1L);
        instance.setScheduleId(1000L + instanceId);
        instance.setPlanType("TEST");
        instance.setBusinessScheduleCode("TEST_SCHEDULE");
        instance.setScheduleScope("DEFAULT");
        instance.setBusinessType("TEST");
        instance.setBusinessId("BIZ_" + instanceId);
        instance.setTaskKey("TASK_" + instanceId);
        instance.setBusinessTaskKey("T:" + instanceId);
        instance.setModeSnapshot(PlanMode.QUEUE);
        instance.setHoldStart(Boolean.FALSE);
        instance.setPlanStatus(PlanStatus.RUNNING);
        instance.setInstancePriority(0);
        instance.setRoundNo(0L);
        instance.setStopRequested(Boolean.FALSE);
        instance.setCancelRequested(Boolean.FALSE);
        instance.setInstanceVersion(1L);
        instance.setCreateTime(LocalDateTime.now());
        instance.setUpdateTime(LocalDateTime.now());
        instanceMapper.insert(instance);

        ProcessScheduleControl slot = new ProcessScheduleControl();
        slot.setId(instanceId + 5000);
        slot.setScopeType(ScheduleControlScope.PLAN_SLOT);
        slot.setScopeKey(String.valueOf(instanceId));
        slot.setDuty("PLAN_SLOT");
        slot.setControlState(com.innolux.iws.process.enums.ControlState.AUTO_RUNNING);
        slot.setSlotState(com.innolux.iws.process.enums.SlotState.HELD);
        slot.setChildInstanceId(instanceId);
        slot.setRevision(1L);
        slot.setLastAdmittedAt(LocalDateTime.now());
        controlMapper.insert(slot);

        ProcessToken token = new ProcessToken();
        token.setId(tokenId);
        token.setInstanceId(instanceId);
        token.setTokenRole(TokenRole.BRANCH);
        token.setTokenStatus(TokenStatus.ACTIVE);
        token.setStateCode("START");
        token.setParentScopeKey("ROOT");
        token.setScopeKey("ROOT:" + eventId);
        token.setBranchKey("ROOT");
        token.setBarrierClosed(Boolean.FALSE);
        token.setTokenVersion(1L);
        token.setRoundNo(0L);
        tokenMapper.insert(token);

        ProcessEvent event = new ProcessEvent();
        event.setId(eventId);
        event.setInstanceId(instanceId);
        event.setTokenId(tokenId);
        event.setDefinitionVersionId(definitionId);
        event.setEventDefinitionId(1L);
        event.setEventCode("TEST");
        event.setEventKey("E:TEST");
        event.setRoundNo(0L);
        event.setBranchIdentity("ROOT");
        event.setEventRole(EventRole.LOCAL);
        event.setEventDirection(EventDirection.INTERNAL);
        event.setSystemCode("SELF");
        event.setLocalActionKey("TEST_LOCAL");
        event.setLocalActionVersion("1");
        event.setLocalEffectKey("LOCAL");
        event.setSourceStateCode("START");
        event.setTargetStateCode("END");
        event.setEventStatus(EventStatus.PENDING);
        event.setSent(Boolean.FALSE);
        event.setPayloadReady(Boolean.TRUE);
        event.setRetryCount(0);
        event.setRecoveryRequested(Boolean.FALSE);
        event.setExecutionPurpose(ExecutionPurpose.NORMAL);
        event.setPriority(0);
        event.setLeaseEpoch(0L);
        event.setEventVersion(1L);
        event.setIdempotencyKey("IDEM:" + eventId);
        eventMapper.insert(event);
        return new Fixture(instanceId, eventId, tokenId);
    }

    private record Fixture(long instanceId, long eventId, long tokenId) {
    }

    private static final class TestLocalAction implements LocalAction {

        private final JdbcTemplate jdbcTemplate;

        private TestLocalAction(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public LocalActionDescriptor describe() {
            return new LocalActionDescriptor("TEST_LOCAL", "1", "测试本地动作", "SAME_TX", true);
        }

        @Override
        public ActionResult execute(ActionContext context) {
            jdbcTemplate.update("INSERT INTO runtime_test_effect(event_id, effect_key) VALUES (?, ?)",
                context.eventId(), "LOCAL");
            return new ActionResult(EffectResult.SUCCEEDED, "LOCAL", null, null, null, null, null, "ok");
        }
    }
}
