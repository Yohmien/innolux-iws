package com.innolux.iws.process.supervisor;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.process.api.ControlIntent;
import com.innolux.iws.process.api.ControlIntentSnapshot;
import com.innolux.iws.process.api.PlatformScopeResultCommand;
import com.innolux.iws.process.api.ProcessControlIntentPort;
import com.innolux.iws.process.api.SupervisorRoundLease;
import com.innolux.iws.process.api.SupervisorSlot;
import com.innolux.iws.process.api.SupervisorTickResult;
import com.innolux.iws.process.api.SupervisorControlResult;
import com.innolux.iws.process.domain.BusinessSchedule;
import com.innolux.iws.process.domain.BusinessSchedulePlanType;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.domain.ProcessEdge;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.domain.ProcessState;
import com.innolux.iws.process.enums.BranchKind;
import com.innolux.iws.process.enums.CompletionPolicy;
import com.innolux.iws.process.enums.ControlState;
import com.innolux.iws.process.enums.DefinitionStatus;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventRole;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.OperationKind;
import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.PlanStatus;
import com.innolux.iws.process.enums.RoundStatus;
import com.innolux.iws.process.enums.ScheduleControlScope;
import com.innolux.iws.process.enums.SlotState;
import com.innolux.iws.process.enums.StateKind;
import com.innolux.iws.process.mapper.BusinessScheduleMapper;
import com.innolux.iws.process.mapper.BusinessSchedulePlanTypeMapper;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessEdgeMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessEventDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.mapper.ProcessStateMapper;
import com.innolux.iws.process.runtime.SupervisorControlService;
import com.innolux.iws.process.runtime.SupervisorQueryService;
import com.innolux.iws.process.runtime.SupervisorRuntimeService;
import com.innolux.iws.process.runtime.SupervisorTickService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** D35-D42 监督有限轮次、名额、公平、控制与持久意图隔离验收。 */
@Tag("dev")
@SpringBootTest(classes = SupervisorFixtureApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.main.banner-mode=off", "logging.level.root=WARN", "iws.process.enabled=true"})
@EnabledIfSystemProperty(named = "iws.process.test.mysql-datadir", matches = ".+")
class SupervisorMysqlIntegrationTest {

    private static final long SCHEDULE_ID = 100L;
    private static final long TYPE_ID = 101L;
    private static final long SUP_DEF = 200L;
    private static final long DECIDE_DEF = 210L;
    private static final long ADMIT_DEF = 211L;
    private static final long START_STATE = 220L;
    private static final long END_STATE = 221L;
    private static final long EDGE_ID = 230L;
    private static final String PLAN_TYPE = "QUEUE_TYPE";
    private static final String SCOPE = "DEFAULT";
    private static final String BUSINESS_CODE = "SUP_TEST";

    private long activeSupervisorId;

    @Autowired SupervisorRuntimeService runtime;
    @Autowired SupervisorTickService tick;
    @Autowired SupervisorControlService controlService;
    @Autowired SupervisorQueryService queryService;
    @Autowired ProcessControlIntentPort intents;
    @Autowired PlanDefinitionMapper definitionMapper;
    @Autowired ProcessInstanceMapper instanceMapper;
    @Autowired ProcessEventMapper eventMapper;
    @Autowired ProcessEventDefinitionMapper eventDefinitionMapper;
    @Autowired ProcessEdgeMapper edgeMapper;
    @Autowired ProcessStateMapper stateMapper;
    @Autowired ProcessAttemptMapper attemptMapper;
    @Autowired ProcessScheduleControlMapper controlMapper;
    @Autowired BusinessScheduleMapper scheduleMapper;
    @Autowired BusinessSchedulePlanTypeMapper planTypeMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() throws Exception {
        SupervisorMysqlFixture.resetSchema();
        SupervisorFixtureApplication.allowAllObjectScopes();
        loginWithPermissions(1001L, List.<String>of());
    }

    @AfterEach
    void clearSecurityContext() {
        SupervisorFixtureApplication.allowAllObjectScopes();
        try {
            StpUtil.logout();
        } catch (RuntimeException ignored) {
            // 测试上下文可能已清理。
        }
        SaTokenContextMockUtil.clearContext();
    }

    @Test
    void d35FirstRoundMaterializesDecisionAndPerCandidateLocalAndKeepsSupervisorRunning() {
        long supervisorId = seedSupervisor(1, 2);
        long first = child(1001L, 5, LocalDateTime.now().minusMinutes(3));
        long second = child(1002L, 1, LocalDateTime.now().minusMinutes(2));

        SupervisorTickResult round1 = tick.tick(supervisorId, 10, 5000);
        assertThat(round1.roundStatus()).isEqualTo("COMPLETED");
        assertThat(round1.candidateCount()).isEqualTo(2);
        assertThat(round1.selectedCount()).isEqualTo(1);
        assertThat(instanceMapper.selectById(supervisorId).getPlanStatus()).isEqualTo(PlanStatus.RUNNING);
        assertThat(instanceMapper.selectById(first).getPlanStatus()).isEqualTo(PlanStatus.RUNNING);
        assertThat(instanceMapper.selectById(second).getPlanStatus()).isEqualTo(PlanStatus.CREATED);
        assertThat(heldSlots(supervisorId)).isEqualTo(1);

        ProcessEvent decision = eventMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
            .<ProcessEvent>lambdaQuery()
            .eq(ProcessEvent::getInstanceId, supervisorId)
            .eq(ProcessEvent::getRoundNo, 1L)
            .eq(ProcessEvent::getEventCode, "DECIDE"));
        assertThat(decision.getEventStatus()).isEqualTo(EventStatus.SUCCESS);
        ProcessAttempt attempt = attemptMapper.selectById(decision.getLastAttemptId());
        assertThat(attempt.getCheckpointJson()).contains("\"selected\":[1001]");
        ProcessEvent local = eventMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
            .<ProcessEvent>lambdaQuery()
            .eq(ProcessEvent::getInstanceId, supervisorId)
            .eq(ProcessEvent::getRoundNo, 1L)
            .eq(ProcessEvent::getEventCode, "ADMIT"));
        assertThat(local.getEventStatus()).isEqualTo(EventStatus.SUCCESS);
        assertThat(attemptMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
            .<ProcessAttempt>lambdaQuery().eq(ProcessAttempt::getEventId, local.getId()))).isEqualTo(1);

        SupervisorTickResult round2 = tick.tick(supervisorId, 10, 5000);
        assertThat(round2.roundStatus()).isEqualTo("COMPLETED");
        assertThat(round2.candidateCount()).isEqualTo(1);
        assertThat(round2.selectedCount()).isZero();
        assertThat(instanceMapper.selectById(supervisorId).getPlanStatus()).isEqualTo(PlanStatus.RUNNING);

        jdbcTemplate.update("UPDATE iws_process_instance SET plan_status='COMPLETED',"
            + " instance_version=instance_version+1, ended_at=NOW(3) WHERE id=?", first);
        assertThat(runtime.releaseSlot(supervisorId, first, "done")).isTrue();
        SupervisorTickResult round3 = tick.tick(supervisorId, 10, 5000);
        assertThat(round3.selectedCount()).isEqualTo(1);
        assertThat(instanceMapper.selectById(second).getPlanStatus()).isEqualTo(PlanStatus.RUNNING);
    }

    @Test
    void d35EmptyQueueCompletesRoundButNeverCompletesSupervisor() {
        long supervisorId = seedSupervisor(1, 2);
        SupervisorTickResult round1 = tick.tick(supervisorId, 10, 5000);
        assertThat(round1.roundStatus()).isEqualTo("COMPLETED");
        assertThat(round1.candidateCount()).isZero();
        assertThat(round1.selectedCount()).isZero();
        assertThat(instanceMapper.selectById(supervisorId).getPlanStatus()).isEqualTo(PlanStatus.RUNNING);

        SupervisorTickResult round2 = tick.tick(supervisorId, 10, 5000);
        assertThat(round2.roundNo()).isEqualTo(2L);
        assertThat(round2.roundStatus()).isEqualTo("COMPLETED");
        assertThat(instanceMapper.selectById(supervisorId).getPlanStatus()).isEqualTo(PlanStatus.RUNNING);
    }

    @Test
    void d36ConcurrentAdmissionNeverExceedsPlanSlotLimit() throws Exception {
        long supervisorId = seedSupervisor(1, 2);
        long first = child(1101L, 1, LocalDateTime.now().minusMinutes(2));
        long second = child(1102L, 1, LocalDateTime.now().minusMinutes(1));
        SupervisorRoundLease lease = runtime.beginRound(supervisorId, "concurrent", 5000);
        assertThat(lease).isNotNull();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<SupervisorSlot> a = pool.submit(() -> {
            ready.countDown();
            start.await();
            return runtime.admitWithLease(supervisorId, lease, first);
        });
        Future<SupervisorSlot> b = pool.submit(() -> {
            ready.countDown();
            start.await();
            return runtime.admitWithLease(supervisorId, lease, second);
        });
        ready.await();
        start.countDown();
        List<SupervisorSlot> slots = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertThat(slots.stream().filter(SupervisorSlot::granted).count()).isEqualTo(1);
        assertThat(heldSlots(supervisorId)).isEqualTo(1);
        assertThat(runtime.closeRound(lease, RoundStatus.COMPLETED, "done")).isTrue();
    }

    @Test
    void d37FairnessWindowSelectsOldestNormalAtLastSlot() {
        long supervisorId = seedSupervisor(1, 2);
        long promoted = child(1201L, 10, LocalDateTime.now().minusMinutes(4));
        long oldestNormal = child(1202L, 0, LocalDateTime.now().minusMinutes(3));
        long newerNormal = child(1203L, 0, LocalDateTime.now().minusMinutes(2));

        SupervisorTickResult first = tick.tick(supervisorId, 10, 5000);
        assertThat(first.selectedCount()).isEqualTo(1);
        assertThat(instanceMapper.selectById(promoted).getPlanStatus()).isEqualTo(PlanStatus.RUNNING);
        assertThat(instanceMapper.selectById(oldestNormal).getPlanStatus()).isEqualTo(PlanStatus.CREATED);

        jdbcTemplate.update("UPDATE iws_process_instance SET plan_status='COMPLETED',"
            + " instance_version=instance_version+1, ended_at=NOW(3) WHERE id=?", promoted);
        assertThat(runtime.releaseSlot(supervisorId, promoted, "done")).isTrue();

        SupervisorTickResult second = tick.tick(supervisorId, 10, 5000);
        assertThat(second.selectedCount()).isEqualTo(1);
        assertThat(instanceMapper.selectById(oldestNormal).getPlanStatus()).isEqualTo(PlanStatus.RUNNING);
        assertThat(instanceMapper.selectById(newerNormal).getPlanStatus()).isEqualTo(PlanStatus.CREATED);
        ProcessScheduleControl control = dispatchControl(supervisorId);
        assertThat(control.getFairnessCount()).isZero();
    }

    @Test
    void d40RoundStaysOpenWhenBatchExhaustedAndStaleLeaseCannotClose() {
        long supervisorId = seedSupervisor(3, 2);
        child(1301L, 1, LocalDateTime.now().minusMinutes(3));
        child(1302L, 1, LocalDateTime.now().minusMinutes(2));
        child(1303L, 1, LocalDateTime.now().minusMinutes(1));

        SupervisorTickResult first = tick.tick(supervisorId, 1, 5000);
        assertThat(first.roundStatus()).isEqualTo("OPEN");
        assertThat(first.continueImmediately()).isTrue();
        ProcessScheduleControl control = dispatchControl(supervisorId);
        assertThat(control.getRoundNo()).isEqualTo(1L);
        assertThat(control.getRoundStatus()).isEqualTo(RoundStatus.OPEN);

        SupervisorTickResult second = tick.tick(supervisorId, 2, 5000);
        assertThat(second.roundNo()).isEqualTo(1L);
        assertThat(second.roundStatus()).isEqualTo("OPEN");
        SupervisorTickResult third = tick.tick(supervisorId, 2, 5000);
        assertThat(third.roundNo()).isEqualTo(1L);
        assertThat(third.roundStatus()).isEqualTo("COMPLETED");
        assertThat(heldSlots(supervisorId)).isEqualTo(3);

        SupervisorRoundLease firstLease = runtime.beginRound(supervisorId, "stale-1", 5000);
        assertThat(firstLease).isNotNull();
        jdbcTemplate.update("UPDATE iws_process_schedule_control SET lease_until=DATE_SUB(NOW(3),"
            + " INTERVAL 1 SECOND) WHERE supervisor_instance_id=? AND duty='DISPATCH'", supervisorId);
        SupervisorRoundLease secondLease = runtime.beginRound(supervisorId, "stale-2", 5000);
        assertThat(secondLease).isNotNull();
        assertThat(runtime.closeRound(firstLease, RoundStatus.COMPLETED, "stale")).isFalse();
        ProcessScheduleControl latest = dispatchControl(supervisorId);
        assertThat(latest.getRoundStatus()).isEqualTo(RoundStatus.OPEN);
        assertThat(latest.getOwner()).isEqualTo(secondLease.owner());
        assertThat(runtime.closeRound(secondLease, RoundStatus.COMPLETED, "cleanup")).isTrue();
    }

    @Test
    void d41PauseClosesOpenRoundAndVersionSwitchNeedsConvergedControl() {
        long supervisorId = seedSupervisor(3, 2);
        child(1401L, 1, LocalDateTime.now().minusMinutes(2));
        child(1402L, 1, LocalDateTime.now().minusMinutes(1));
        SupervisorTickResult open = tick.tick(supervisorId, 1, 5000);
        assertThat(open.roundStatus()).isEqualTo("OPEN");

        ProcessScheduleControl beforePause = dispatchControl(supervisorId);
        controlService.pause(supervisorId, beforePause.getRevision(), "pause-key", "review");
        ProcessScheduleControl paused = dispatchControl(supervisorId);
        assertThat(paused.getControlState()).isEqualTo(ControlState.MANUAL_PAUSED);
        assertThat(paused.getRoundStatus()).isEqualTo(RoundStatus.COMPLETED);
        assertThat(paused.getOwner()).isNull();
        assertThat(instanceMapper.selectById(supervisorId).getPlanStatus()).isEqualTo(PlanStatus.PAUSED);
        assertThat(eventMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
            .<ProcessEvent>lambdaQuery()
            .eq(ProcessEvent::getInstanceId, supervisorId)
            .eq(ProcessEvent::getEventStatus, EventStatus.NOT_SELECTED)
            .eq(ProcessEvent::getOutcome, "PAUSED_REJECTED"))).isEqualTo(2);

        SupervisorControlResult replay = controlService.pause(supervisorId, beforePause.getRevision(),
            "pause-key", "review");
        assertThat(replay.message()).contains("幂等回放");
        assertThatThrownBy(() -> controlService.pause(supervisorId, beforePause.getRevision(),
            "pause-key", "other")).hasMessageContaining("参数不同");
        loginWithPermissions(1002L, List.<String>of());
        assertThatThrownBy(() -> controlService.pause(supervisorId, beforePause.getRevision(),
            "pause-key", "review")).hasMessageContaining("原操作者");
        loginWithPermissions(1001L, List.<String>of());

        long targetId = seedTargetDefinition();
        assertThatThrownBy(() -> controlService.activateVersion(supervisorId, paused.getRevision(), targetId,
            "activate-key", "switch")).hasMessageContaining("VERSION_CONFLICT");

        intents.recordResult(new PlatformScopeResultCommand("SUPERVISOR", paused.getScopeKey(),
            "DISPATCH", "ENSURE_STOPPED", paused.getRevision(), true, "STOPPED", "job-pause", "applied"));
        ProcessScheduleControl converged = dispatchControl(supervisorId);
        assertThat(converged.getWakePending()).isFalse();
        controlService.activateVersion(supervisorId, converged.getRevision(), targetId, "activate-key-2",
            "switch");
        assertThat(instanceMapper.selectById(supervisorId).getDefinitionVersionId()).isEqualTo(targetId);
        assertThat(instanceMapper.selectById(supervisorId).getPlanStatus()).isEqualTo(PlanStatus.PAUSED);

        controlService.resume(supervisorId, dispatchControl(supervisorId).getRevision(), "resume-key", "resume");
        assertThat(dispatchControl(supervisorId).getControlState()).isEqualTo(ControlState.AUTO_RUNNING);
        assertThat(instanceMapper.selectById(supervisorId).getPlanStatus()).isEqualTo(PlanStatus.RUNNING);
        SupervisorTickResult next = tick.tick(supervisorId, 10, 5000);
        assertThat(next.roundNo()).isEqualTo(2L);
        assertThat(next.roundStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void d42IntentDiscoveryQueryAndPlatformCasDoNotClearFreshWake() {
        long supervisorId = seedSupervisor(1, 2);
        List<ControlIntent> pending = intents.listPendingIntents(10);
        assertThat(pending).hasSize(2);
        ControlIntent supervisorIntent = pending.stream()
            .filter(item -> "DISPATCH".equals(item.duty())).findFirst().orElseThrow();
        ControlIntent workerIntent = pending.stream()
            .filter(item -> "WORKER".equals(item.duty())).findFirst().orElseThrow();
        assertThat(supervisorIntent.jobSpec()).isNotNull();
        assertThat(supervisorIntent.jobSpec().executorName()).isEqualTo("businessScheduleExecutor");
        assertThat(supervisorIntent.jobSpec().paramsJson()).contains(String.valueOf(supervisorId));
        assertThat(workerIntent.scopeType()).isEqualTo("BUSINESS");
        assertThat(workerIntent.scopeKey()).isEqualTo("schedule:" + SCHEDULE_ID);
        assertThat(workerIntent.jobSpec()).isNotNull();
        assertThat(workerIntent.jobSpec().executorName()).isEqualTo("planEventWorkerExecutor");
        assertThat(workerIntent.jobSpec().paramsJson()).contains(String.valueOf(SCHEDULE_ID));

        tick.tick(supervisorId, 10, 5000);
        PageResult<?> supervisors = queryService.pageSupervisors(SCHEDULE_ID, null, null, null, null, page());
        assertThat(supervisors.getRows().size()).isEqualTo(supervisors.getTotal());
        assertThat(queryService.detail(supervisorId).summary().supervisorInstanceId())
            .isEqualTo(String.valueOf(supervisorId));
        PageResult<?> rounds = queryService.pageRounds(supervisorId, null, null, page());
        assertThat(rounds.getTotal()).isEqualTo(1);
        assertThat(rounds.getRows().size()).isEqualTo(rounds.getTotal());
        PageResult<?> records = queryService.pageDispatchRecords(supervisorId, null, null, null,
            null, null, null, null, page());
        assertThat(records.getTotal()).isGreaterThan(0);
        assertThat(records.getRows().size()).isEqualTo(Math.min(20, records.getTotal()));

        ProcessScheduleControl control = dispatchControl(supervisorId);
        long staleRevision = control.getRevision();
        jdbcTemplate.update("UPDATE iws_process_schedule_control SET revision=revision+1,"
            + " wake_pending=1 WHERE id=?", control.getId());
        var conflict = intents.recordResult(new PlatformScopeResultCommand("SUPERVISOR",
            control.getScopeKey(), "DISPATCH", "TRIGGER_ONCE", staleRevision, true,
            "TRIGGERED", "job-old", "old"));
        assertThat(conflict.conflict()).isTrue();
        assertThat(conflict.recorded()).isFalse();
        assertThat(conflict.wakePending()).isTrue();

        ProcessScheduleControl fresh = dispatchControl(supervisorId);
        var applied = intents.recordResult(new PlatformScopeResultCommand("SUPERVISOR",
            fresh.getScopeKey(), "DISPATCH", "TRIGGER_ONCE", fresh.getRevision(), true,
            "TRIGGERED", "job-new", "ok"));
        assertThat(applied.recorded()).isTrue();
        assertThat(applied.wakePending()).isFalse();
    }

    @Test
    void r10OrdinaryLoginWithoutObjectScopeDeniesSupervisorQueryAndControl() {
        long supervisorId = seedSupervisor(1, 2);
        SupervisorFixtureApplication.OBJECT_POLICY.set(target -> false);
        assertThatThrownBy(() -> queryService.detail(supervisorId)).hasMessageContaining("无权限");
        ProcessScheduleControl control = dispatchControl(supervisorId);
        assertThatThrownBy(() -> controlService.pause(supervisorId, control.getRevision(), "deny", "deny"))
            .hasMessageContaining("无权限");
    }

    @Test
    void r10SupervisorListFiltersEachChildScopeByPolicy() {
        long allowed = seedSupervisor(1, 2);
        long denied = runtime.ensureSupervisor(SCHEDULE_ID, PLAN_TYPE, "SCOPE_B").getId();
        SupervisorFixtureApplication.OBJECT_POLICY.set(target -> target.scheduleScope() == null
            || "DEFAULT".equals(target.scheduleScope()));
        PageResult<com.innolux.iws.process.api.SupervisorSummaryVO> page = queryService.pageSupervisors(
            SCHEDULE_ID, null, null, null, null, page());
        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getRows()).extracting(com.innolux.iws.process.api.SupervisorSummaryVO::supervisorInstanceId)
            .containsExactly(String.valueOf(allowed));
        assertThat(page.getRows()).allMatch(item -> !item.supervisorInstanceId().equals(String.valueOf(denied)));
    }

    private long seedSupervisor(int limit, int fairnessWindow) {
        BusinessSchedule schedule = new BusinessSchedule();
        schedule.setId(SCHEDULE_ID);
        schedule.setBusinessCode(BUSINESS_CODE);
        schedule.setBusinessName("监督测试业务");
        schedule.setEnabled(Boolean.TRUE);
        schedule.setFairnessWindowSize(fairnessWindow);
        schedule.setRevision(1L);
        schedule.setDelFlag("0");
        scheduleMapper.insert(schedule);

        BusinessSchedulePlanType type = new BusinessSchedulePlanType();
        type.setId(TYPE_ID);
        type.setScheduleId(SCHEDULE_ID);
        type.setPlanType(PLAN_TYPE);
        type.setPlanTypeName("队列类型");
        type.setPriority(0);
        type.setEnabled(Boolean.TRUE);
        planTypeMapper.insert(type);

        definitionMapper.insert(supervisorDefinition(SUP_DEF, 1, limit, fairnessWindow));
        seedGraph(SUP_DEF, DECIDE_DEF, ADMIT_DEF, START_STATE, END_STATE, EDGE_ID);
        activeSupervisorId = runtime.ensureSupervisor(SCHEDULE_ID, PLAN_TYPE, SCOPE).getId();
        return activeSupervisorId;
    }

    private void seedGraph(long definitionId, long decideId, long admitId, long startId, long endId, long edgeId) {
        insertState(definitionId, startId, "SUP_START", StateKind.START, false, 1);
        insertState(definitionId, endId, "SUP_END", StateKind.END, true, 2);
        eventDefinitionMapper.insert(newEventDefinition(definitionId, decideId, "DECIDE", EventRole.DECISION,
            "SUPERVISOR_CANDIDATE_DECISION", "SUP_DECIDE", 10));
        eventDefinitionMapper.insert(newEventDefinition(definitionId, admitId, "ADMIT", EventRole.LOCAL,
            "SUPERVISOR_ADMIT_QUEUE", "SUP_ADMIT", 5));
        ProcessEdge edge = new ProcessEdge();
        edge.setId(edgeId);
        edge.setDefinitionVersionId(definitionId);
        edge.setPredecessorEventCode("DECIDE");
        edge.setSuccessorEventCode("ADMIT");
        edge.setBranchKey("SEQUENTIAL");
        edge.setBranchKind(BranchKind.SEQUENTIAL);
        edge.setDefaultBranch(Boolean.TRUE);
        edgeMapper.insert(edge);
    }

    private void insertState(long definitionId, long id, String code, StateKind kind, boolean terminal, int order) {
        ProcessState state = new ProcessState();
        state.setId(id);
        state.setDefinitionVersionId(definitionId);
        state.setStateCode(code);
        state.setDisplayName(code);
        state.setStateKind(kind);
        state.setValidatorKey("SUP_VALIDATOR");
        state.setValidatorVersion("1");
        state.setTerminal(terminal);
        state.setSortOrder(order);
        stateMapper.insert(state);
    }

    private PlanDefinition supervisorDefinition(long id, int version, int limit, int fairnessWindow) {
        PlanDefinition definition = new PlanDefinition();
        definition.setId(id);
        definition.setPlanCode("SUP_DEF_" + version);
        definition.setPlanVersion(version);
        definition.setPlanType(PLAN_TYPE);
        definition.setBusinessScheduleCode(BUSINESS_CODE);
        definition.setPlanName("监督定义");
        definition.setPlanRole(PlanRole.SUPERVISOR);
        definition.setStatus(DefinitionStatus.ACTIVE);
        definition.setRevision((long) version);
        definition.setSelfSystemCode("SELF");
        definition.setMode(PlanMode.QUEUE);
        definition.setActivePlanLimit(limit);
        definition.setFairnessWindowSize(fairnessWindow);
        definition.setCompletionPolicy(CompletionPolicy.ALL_SELECTED);
        definition.setRoundBatchLimit(10);
        definition.setRoundMaxDurationMs(5000L);
        definition.setLeaseMs(60000L);
        definition.setScheduleIntervalMs(1000L);
        definition.setJobTimeoutMs(5000L);
        definition.setJobMaxRetryTimes(0);
        definition.setWorkerBatchLimit(10);
        definition.setWorkerMaxDurationMs(5000L);
        definition.setDelFlag("0");
        return definition;
    }

    private ProcessEventDefinition newEventDefinition(long definitionId, long id, String code, EventRole role,
                                                      String action, String effect, int priority) {
        ProcessEventDefinition definition = new ProcessEventDefinition();
        definition.setId(id);
        definition.setDefinitionVersionId(definitionId);
        definition.setEventCode(code);
        definition.setDisplayName(code);
        definition.setEventRole(role);
        definition.setEventDirection(EventDirection.INTERNAL);
        definition.setOperationKind(OperationKind.CONTROL);
        definition.setLocalActionKey(action);
        definition.setLocalActionVersion("1");
        definition.setEffectKey(effect);
        definition.setLocalEffectKey(effect);
        definition.setSystemCode("SELF");
        definition.setSourceStateCode("SUP_START");
        definition.setTargetStateCode("SUP_END");
        definition.setEnabled(Boolean.TRUE);
        definition.setPriority(priority);
        return definition;
    }

    private long seedTargetDefinition() {
        long id = SUP_DEF + 1;
        definitionMapper.insert(supervisorDefinition(id, 2, 3, 2));
        seedGraph(id, 310L, 311L, 320L, 321L, 330L);
        return id;
    }

    private long child(long id, int priority, LocalDateTime createTime) {
        ProcessInstance child = new ProcessInstance();
        child.setId(id);
        child.setPlanRole(PlanRole.BUSINESS);
        child.setDefinitionVersionId(SUP_DEF);
        child.setDefinitionRevision(1L);
        child.setScheduleId(SCHEDULE_ID);
        child.setPlanType(PLAN_TYPE);
        child.setBusinessScheduleCode(BUSINESS_CODE);
        child.setSupervisorInstanceId(activeSupervisorId);
        child.setBusinessTaskKey("B:" + id);
        child.setBusinessType("TEST");
        child.setBusinessId("B" + id);
        child.setTaskKey("T" + id);
        child.setScheduleScope(SCOPE);
        child.setModeSnapshot(PlanMode.QUEUE);
        child.setHoldStart(Boolean.FALSE);
        child.setPlanStatus(PlanStatus.CREATED);
        child.setInstancePriority(priority);
        child.setRoundNo(0L);
        child.setStopRequested(Boolean.FALSE);
        child.setCancelRequested(Boolean.FALSE);
        child.setInstanceVersion(1L);
        child.setCreateTime(createTime);
        child.setUpdateTime(createTime);
        instanceMapper.insert(child);
        return id;
    }

    private long heldSlots(long supervisorId) {
        return controlMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
            .<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.PLAN_SLOT)
            .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisorId)
            .eq(ProcessScheduleControl::getSlotState, SlotState.HELD));
    }

    private ProcessScheduleControl dispatchControl(long supervisorId) {
        return controlMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
            .<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisorId)
            .eq(ProcessScheduleControl::getDuty, "DISPATCH"));
    }

    private static PageQuery page() {
        PageQuery query = new PageQuery();
        query.setPageNum(1);
        query.setPageSize(20);
        return query;
    }

    @Test
    void concurrentSameCommandReturnsOriginalReceiptAndChangesRevisionOnce() throws Exception {
        long supervisorId = seedSupervisor(1, 2);
        long revision = dispatchControl(supervisorId).getRevision();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<SupervisorControlResult> command = () -> {
            loginWithPermissions(1001L, List.of());
            try {
                ready.countDown();
                start.await();
                return controlService.pause(supervisorId, revision, "same-concurrent-command", "并发重投");
            } finally {
                SaTokenContextMockUtil.clearContext();
            }
        };
        try {
            var first = pool.submit(command);
            var second = pool.submit(command);
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var a = first.get(15, java.util.concurrent.TimeUnit.SECONDS);
            var b = second.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(a.revision()).isEqualTo(b.revision());
            assertThat(dispatchControl(supervisorId).getRevision()).isEqualTo(revision + 1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iws_process_action_audit WHERE command_key=?",
                Integer.class, "same-concurrent-command")).isEqualTo(1);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    private void loginWithPermissions(long loginId, List<String> permissions) {
        SaTokenContextMockUtil.setMockContext();
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
        SaManager.setStpInterface(new StpInterface() {
            @Override
            public List<String> getPermissionList(Object id, String loginType) {
                return permissions;
            }

            @Override
            public List<String> getRoleList(Object id, String loginType) {
                return List.of();
            }
        });
        try {
            StpUtil.logout();
        } catch (RuntimeException ignored) {
            // 首次登录无会话。
        }
        StpUtil.login(loginId);
    }
}
