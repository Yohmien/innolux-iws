/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : T5 终态提交租约过期反例的真实 MySQL 事务验证；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.domain.ProcessState;
import com.innolux.iws.process.domain.ProcessToken;
import com.innolux.iws.process.enums.CheckpointStatus;
import com.innolux.iws.process.enums.CompletionPolicy;
import com.innolux.iws.process.enums.ControlState;
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
import com.innolux.iws.process.enums.SlotState;
import com.innolux.iws.process.enums.StateKind;
import com.innolux.iws.process.enums.TokenRole;
import com.innolux.iws.process.enums.TokenStatus;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.mapper.ProcessStateMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T5 终态提交在租约失效时必须整体回滚。 */
@Tag("dev")
@SpringBootTest(classes = {RuntimeFixtureApplication.class, TerminalLeaseMysqlTest.ValidatorConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "iws.process.enabled=true")
@Timeout(10)
class TerminalLeaseMysqlTest {

    private static final long DEFINITION_ID = 7101L;
    private static final long INSTANCE_ID = 7102L;
    private static final long EVENT_ID = 7103L;
    private static final long TOKEN_ID = 7104L;
    private static final long STATE_START_ID = 7105L;
    private static final long STATE_END_ID = 7106L;
    private static final long PLAN_SLOT_ID = 7107L;
    private static final long CALL_PERMIT_ID = 7108L;
    private static final long LEASE_MS = 2000L;
    private static final long WAIT_MARGIN_MS = 100L;

    private static final AtomicReference<LocalDateTime> VALIDATOR_BLOCK_UNTIL = new AtomicReference<>();
    private static final AtomicBoolean VALIDATOR_ENTERED = new AtomicBoolean();

    @Autowired EventMutationService mutationService;
    @Autowired PlanDefinitionMapper definitionMapper;
    @Autowired ProcessInstanceMapper instanceMapper;
    @Autowired ProcessTokenMapper tokenMapper;
    @Autowired ProcessEventMapper eventMapper;
    @Autowired ProcessAttemptMapper attemptMapper;
    @Autowired ProcessStateMapper stateMapper;
    @Autowired ProcessScheduleControlMapper controlMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        VALIDATOR_BLOCK_UNTIL.set(null);
        VALIDATOR_ENTERED.set(false);
        jdbcTemplate.execute("DELETE FROM iws_process_attempt");
        jdbcTemplate.execute("DELETE FROM iws_process_request_detail");
        jdbcTemplate.execute("DELETE FROM iws_process_event");
        jdbcTemplate.execute("DELETE FROM iws_process_token");
        jdbcTemplate.execute("DELETE FROM iws_process_schedule_control");
        jdbcTemplate.execute("DELETE FROM iws_process_action_audit");
        jdbcTemplate.execute("DELETE FROM iws_process_instance");
        jdbcTemplate.execute("DELETE FROM iws_process_state");
        jdbcTemplate.execute("DELETE FROM iws_process_event_definition");
        jdbcTemplate.execute("DELETE FROM iws_process_edge");
        jdbcTemplate.execute("DELETE FROM iws_process_definition");
        jdbcTemplate.execute("DELETE FROM iws_process_system_context");
        jdbcTemplate.execute("DELETE FROM iws_process_system_policy");
        jdbcTemplate.execute("DELETE FROM runtime_test_effect");
    }

    @Test
    void finishSuccessRejectsExpiredLeaseAndRollsBackRuntimeRows() {
        seed();
        EventMutationService.ClaimContext claim = mutationService.claim(EVENT_ID, "T5_LEASE", LEASE_MS);
        assertThat(claim).isNotNull();
        assertThat(claim.leaseUntil()).isAfter(LocalDateTime.now());

        ProcessToken tokenAtClaim = tokenMapper.selectById(TOKEN_ID);
        ProcessAttempt attemptAtClaim = attemptMapper.selectById(claim.attempt().getId());
        assertThat(tokenAtClaim).isNotNull();
        assertThat(attemptAtClaim).isNotNull();
        // 预置 CALL permit，失败路径不得释放许可或推进其修订号。
        seedCallPermit();

        VALIDATOR_BLOCK_UNTIL.set(claim.leaseUntil().plusNanos(WAIT_MARGIN_MS * 1_000_000L));
        try {
            assertThatThrownBy(() -> mutationService.finishSuccess(claim, EffectResult.SUCCEEDED))
                .isInstanceOf(EventMutationService.LeaseLostException.class)
                .hasMessage("LEASE_LOST");
        } finally {
            VALIDATOR_BLOCK_UNTIL.set(null);
        }
        assertThat(VALIDATOR_ENTERED.get()).isTrue();

        ProcessEvent event = eventMapper.selectById(EVENT_ID);
        assertThat(event.getEventStatus()).isEqualTo(EventStatus.PROCESSING);
        assertThat(event.getEffectResult()).isNull();
        assertThat(event.getOutcome()).isNull();
        assertThat(event.getEndedAt()).isNull();
        assertThat(event.getEventVersion()).isEqualTo(claim.eventVersion());
        assertThat(event.getLeaseUntil()).isBefore(LocalDateTime.now());

        ProcessAttempt attempt = attemptMapper.selectById(claim.attempt().getId());
        assertThat(attempt.getCheckpointStatus()).isEqualTo(CheckpointStatus.CLAIMED);
        assertThat(attempt.getEffectResult()).isNull();
        assertThat(attempt.getEndedAt()).isNull();

        // finishSuccess 先推进 token，再由事件租约 CAS 拒绝提交；前序写入必须一起回滚。
        ProcessToken token = tokenMapper.selectById(TOKEN_ID);
        assertThat(token.getStateCode()).isEqualTo(tokenAtClaim.getStateCode());
        assertThat(token.getTokenVersion()).isEqualTo(tokenAtClaim.getTokenVersion());
        assertThat(token.getLastAttemptId()).isNull();

        ProcessScheduleControl permit = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.CALL)
            .eq(ProcessScheduleControl::getScopeKey, String.valueOf(EVENT_ID))
            .eq(ProcessScheduleControl::getDuty, "CALL_PERMIT"));
        assertThat(permit).isNotNull();
        assertThat(permit.getPermitState()).isEqualTo(PermitState.INTENT);
        assertThat(permit.getRevision()).isEqualTo(1L);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iws_process_event"
            + " WHERE id=? AND event_status IN ('SUCCESS','SKIPPED')", Integer.class, EVENT_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iws_process_attempt"
            + " WHERE event_id=? AND effect_result IS NOT NULL", Integer.class, EVENT_ID)).isZero();
    }

    private void seed() {
        LocalDateTime now = LocalDateTime.now();
        PlanDefinition definition = new PlanDefinition();
        definition.setId(DEFINITION_ID);
        definition.setPlanCode("T5_LEASE_DEF");
        definition.setPlanVersion(1);
        definition.setPlanType("TEST");
        definition.setPlanName("T5 租约过期反例");
        definition.setPlanRole(PlanRole.BUSINESS);
        definition.setStatus(DefinitionStatus.ACTIVE);
        definition.setRevision(1L);
        definition.setSelfSystemCode("SELF");
        definition.setCompletionPolicy(CompletionPolicy.ALL_SELECTED);
        definition.setLeaseMs(LEASE_MS);
        definition.setDelFlag("0");
        definitionMapper.insert(definition);

        ProcessState start = new ProcessState();
        start.setId(STATE_START_ID);
        start.setDefinitionVersionId(DEFINITION_ID);
        start.setStateCode("START");
        start.setDisplayName("起点");
        start.setStateKind(StateKind.START);
        start.setValidatorKey(TerminalLeaseValidator.KEY);
        start.setValidatorVersion(TerminalLeaseValidator.VERSION);
        start.setTerminal(Boolean.FALSE);
        start.setSortOrder(1);
        stateMapper.insert(start);

        ProcessState end = new ProcessState();
        end.setId(STATE_END_ID);
        end.setDefinitionVersionId(DEFINITION_ID);
        end.setStateCode("END");
        end.setDisplayName("终点");
        end.setStateKind(StateKind.END);
        end.setValidatorKey(TerminalLeaseValidator.KEY);
        end.setValidatorVersion(TerminalLeaseValidator.VERSION);
        end.setTerminal(Boolean.TRUE);
        end.setSortOrder(2);
        stateMapper.insert(end);

        ProcessInstance instance = new ProcessInstance();
        instance.setId(INSTANCE_ID);
        instance.setPlanRole(PlanRole.BUSINESS);
        instance.setDefinitionVersionId(DEFINITION_ID);
        instance.setDefinitionRevision(1L);
        instance.setScheduleId(7100L);
        instance.setPlanType("TEST");
        instance.setBusinessScheduleCode("T5_SCHEDULE");
        instance.setScheduleScope("DEFAULT");
        instance.setBusinessType("TEST");
        instance.setBusinessId("T5_BIZ");
        instance.setTaskKey("T5_TASK");
        instance.setBusinessTaskKey("T5:TASK");
        instance.setModeSnapshot(PlanMode.QUEUE);
        instance.setHoldStart(Boolean.FALSE);
        instance.setPlanStatus(PlanStatus.RUNNING);
        instance.setInstancePriority(0);
        instance.setRoundNo(0L);
        instance.setStopRequested(Boolean.FALSE);
        instance.setCancelRequested(Boolean.FALSE);
        instance.setInstanceVersion(1L);
        instance.setCreateTime(now);
        instance.setUpdateTime(now);
        instanceMapper.insert(instance);

        ProcessScheduleControl slot = new ProcessScheduleControl();
        slot.setId(PLAN_SLOT_ID);
        slot.setScopeType(ScheduleControlScope.PLAN_SLOT);
        slot.setScopeKey(String.valueOf(INSTANCE_ID));
        slot.setDuty("PLAN_SLOT");
        slot.setControlState(ControlState.AUTO_RUNNING);
        slot.setSlotState(SlotState.HELD);
        slot.setChildInstanceId(INSTANCE_ID);
        slot.setRevision(1L);
        slot.setLastAdmittedAt(now);
        controlMapper.insert(slot);

        ProcessToken token = new ProcessToken();
        token.setId(TOKEN_ID);
        token.setInstanceId(INSTANCE_ID);
        token.setTokenRole(TokenRole.BRANCH);
        token.setTokenStatus(TokenStatus.ACTIVE);
        token.setStateCode("START");
        token.setParentScopeKey("ROOT");
        token.setScopeKey("ROOT:" + EVENT_ID);
        token.setBranchKey("ROOT");
        token.setBarrierClosed(Boolean.FALSE);
        token.setTokenVersion(1L);
        token.setRoundNo(0L);
        tokenMapper.insert(token);

        ProcessEvent event = new ProcessEvent();
        event.setId(EVENT_ID);
        event.setInstanceId(INSTANCE_ID);
        event.setTokenId(TOKEN_ID);
        event.setDefinitionVersionId(DEFINITION_ID);
        event.setEventDefinitionId(7109L);
        event.setEventCode("TEST");
        event.setEventKey("E:TEST");
        event.setRoundNo(0L);
        event.setBranchIdentity("ROOT");
        event.setEventRole(EventRole.LOCAL);
        event.setEventDirection(EventDirection.INTERNAL);
        event.setSystemCode("SELF");
        event.setLocalActionKey("T5_LOCAL");
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
        event.setIdempotencyKey("IDEM:" + EVENT_ID);
        eventMapper.insert(event);
    }

    private void seedCallPermit() {
        ProcessScheduleControl permit = new ProcessScheduleControl();
        permit.setId(CALL_PERMIT_ID);
        permit.setScopeType(ScheduleControlScope.CALL);
        permit.setScopeKey(String.valueOf(EVENT_ID));
        permit.setDuty("CALL_PERMIT");
        permit.setControlState(ControlState.AUTO_RUNNING);
        permit.setPermitState(PermitState.INTENT);
        permit.setResourceKey("RES");
        permit.setRevision(1L);
        permit.setLastActivityAt(LocalDateTime.now());
        controlMapper.insert(permit);
    }

    /** 专用状态校验器：在业务校验阶段故意阻塞到租约过期之后。 */
    @TestConfiguration
    static class ValidatorConfig {

        @Bean
        StateValidator terminalLeaseValidator() {
            return new TerminalLeaseValidator();
        }
    }

    private static final class TerminalLeaseValidator implements StateValidator {

        private static final String KEY = "T5_TERMINAL_LEASE";
        private static final String VERSION = "1";

        @Override
        public StateValidatorDescriptor describe() {
            return new StateValidatorDescriptor(KEY, VERSION, "T5 租约过期反例校验");
        }

        @Override
        public ValidationResult validateStructural(StateContext context) {
            return ValidationResult.pass();
        }

        @Override
        public ValidationResult validateBusiness(StateContext context) {
            LocalDateTime blockUntil = VALIDATOR_BLOCK_UNTIL.get();
            if (blockUntil == null) {
                return ValidationResult.pass();
            }
            VALIDATOR_ENTERED.set(true);
            while (true) {
                long remainingNanos = Duration.between(LocalDateTime.now(), blockUntil).toNanos();
                if (remainingNanos <= 0) {
                    break;
                }
                LockSupport.parkNanos(Math.min(remainingNanos, 50_000_000L));
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("等待租约过期被中断");
                }
            }
            return ValidationResult.pass();
        }
    }
}
