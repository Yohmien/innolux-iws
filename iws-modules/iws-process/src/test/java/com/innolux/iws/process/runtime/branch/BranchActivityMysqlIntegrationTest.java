/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : R07 活动路径、XOR 关闭、RR 并发 AND、嵌套 scope 与计划聚合；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime.branch;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.domain.ProcessEdge;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.domain.ProcessState;
import com.innolux.iws.process.domain.ProcessToken;
import com.innolux.iws.process.enums.BranchKind;
import com.innolux.iws.process.enums.CheckpointStatus;
import com.innolux.iws.process.enums.CompletionPolicy;
import com.innolux.iws.process.enums.ControlState;
import com.innolux.iws.process.enums.DefinitionStatus;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EditPolicy;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventRole;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.ExecutionPurpose;
import com.innolux.iws.process.enums.GraphEditPolicy;
import com.innolux.iws.process.enums.PermitState;
import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.PlanStatus;
import com.innolux.iws.process.enums.ScheduleControlScope;
import com.innolux.iws.process.enums.SlotState;
import com.innolux.iws.process.enums.StateKind;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessEdgeMapper;
import com.innolux.iws.process.mapper.ProcessEventDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.mapper.ProcessStateMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import com.innolux.iws.process.runtime.BranchContext;
import com.innolux.iws.process.runtime.BranchSelector;
import com.innolux.iws.process.runtime.EventEligibilityService;
import com.innolux.iws.process.runtime.PlanActivationService;
import com.innolux.iws.process.runtime.PlanMaterializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 MySQL DDL 验证 R07 活动路径和汇合协议。
 */
@Tag("dev")
@SpringBootTest(classes = BranchFixtureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "iws.process.enabled=true")
class BranchActivityMysqlIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(10_000L);

    @Autowired PlanDefinitionMapper definitionMapper;

    @Autowired ProcessStateMapper stateMapper;

    @Autowired ProcessEventDefinitionMapper eventDefinitionMapper;

    @Autowired ProcessEdgeMapper edgeMapper;

    @Autowired ProcessInstanceMapper instanceMapper;

    @Autowired ProcessEventMapper eventMapper;

    @Autowired ProcessTokenMapper tokenMapper;

    @Autowired ProcessAttemptMapper attemptMapper;

    @Autowired ProcessScheduleControlMapper controlMapper;

    @Autowired PlanMaterializer materializer;

    @Autowired PlanActivationService activationService;

    @Autowired BranchSelector branchSelector;

    @Autowired EventEligibilityService eligibilityService;

    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM iws_process_attempt");
        jdbcTemplate.execute("DELETE FROM iws_process_event");
        jdbcTemplate.execute("DELETE FROM iws_process_token");
        jdbcTemplate.execute("DELETE FROM iws_process_schedule_control");
        jdbcTemplate.execute("DELETE FROM iws_process_action_audit");
        jdbcTemplate.execute("DELETE FROM iws_process_system_context");
        jdbcTemplate.execute("DELETE FROM iws_process_instance");
        jdbcTemplate.execute("DELETE FROM iws_process_edge");
        jdbcTemplate.execute("DELETE FROM iws_process_event_definition");
        jdbcTemplate.execute("DELETE FROM iws_process_state");
        jdbcTemplate.execute("DELETE FROM iws_process_system_policy");
        jdbcTemplate.execute("DELETE FROM iws_process_definition");
    }

    @Test
    void xorClosesExclusiveDescendantsAndKeepsSharedJoin() {
        PlanDefinition definition = definition(PlanRole.BUSINESS, CompletionPolicy.ALL_SELECTED);
        states(definition, 5);
        events(definition, Map.of(
            "F", new String[]{"S0", "S1"},
            "A", new String[]{"S1", "S3"},
            "B", new String[]{"S1", "S2"},
            "C", new String[]{"S2", "S3"},
            "J", new String[]{"S3", "S4"}));
        edge(definition, "F", "A", BranchKind.XOR, "A", null, true, "ROUTE_A");
        edge(definition, "F", "B", BranchKind.XOR, "B", null, true, null);
        edge(definition, "A", "J", BranchKind.SEQUENTIAL, "", null, false, null);
        edge(definition, "B", "C", BranchKind.SEQUENTIAL, "", null, false, null);
        edge(definition, "C", "J", BranchKind.SEQUENTIAL, "", null, false, null);

        ProcessInstance instance = instance(definition, null);
        materializer.materialize(instance);

        finish(event(instance, "F").getId(), "{\"route\":\"A\"}");

        assertStatus(instance, "B", EventStatus.NOT_SELECTED);
        assertStatus(instance, "C", EventStatus.NOT_SELECTED);
        assertStatus(instance, "A", EventStatus.PENDING);
        assertStatus(instance, "J", EventStatus.WAIT_PREDECESSOR);
        ProcessToken fork = scope(instance, "FORK:F");
        assertThat(fork.getSelectedKeys()).isEqualTo("A");
        assertThat(fork.getDecisionInputHash()).isNotBlank();
        assertThat(fork.getRuleVersion()).isNotBlank();

        finish(event(instance, "A").getId(), null);
        assertStatus(instance, "J", EventStatus.PENDING);
        ProcessToken join = scope(instance, "JOIN:J");
        assertThat(join.getBarrierClosed()).isTrue();
        assertThat(join.getJoinedKeys()).isEqualTo("A|");
        assertThat(join.getJoinedKeys()).doesNotContain("C|");

        finish(event(instance, "J").getId(), null);
        assertThat(instanceMapper.selectById(instance.getId()).getPlanStatus()).isEqualTo(PlanStatus.COMPLETED);
        assertStatus(instance, "C", EventStatus.NOT_SELECTED);
    }

    @Test
    void multipleRootsRespectSelectedRootSetAndKeepSharedJoin() {
        PlanDefinition definition = definition(PlanRole.BUSINESS, CompletionPolicy.ALL_SELECTED);
        states(definition, 3);
        events(definition, Map.of(
            "R1", new String[]{"S0", "S1"},
            "R2", new String[]{"S0", "S1"},
            "X", new String[]{"S1", "S1"},
            "J", new String[]{"S1", "S2"}));
        edge(definition, "R1", "J", BranchKind.SEQUENTIAL, "", null, false, null);
        edge(definition, "R2", "X", BranchKind.SEQUENTIAL, "", null, false, null);
        edge(definition, "X", "J", BranchKind.SEQUENTIAL, "", null, false, null);

        ProcessInstance single = instance(definition, "R1");
        materializer.materialize(single);
        assertStatus(single, "R2", EventStatus.NOT_SELECTED);
        assertStatus(single, "X", EventStatus.NOT_SELECTED);
        assertStatus(single, "R1", EventStatus.PENDING);
        assertStatus(single, "J", EventStatus.WAIT_PREDECESSOR);

        finish(event(single, "R1").getId(), null);
        assertStatus(single, "J", EventStatus.PENDING);
        finish(event(single, "J").getId(), null);
        assertThat(instanceMapper.selectById(single.getId()).getPlanStatus()).isEqualTo(PlanStatus.COMPLETED);

        ProcessInstance both = instance(definition, "R1,R2");
        materializer.materialize(both);
        assertStatus(both, "R1", EventStatus.PENDING);
        assertStatus(both, "R2", EventStatus.PENDING);
        assertStatus(both, "X", EventStatus.WAIT_PREDECESSOR);
        finish(event(both, "R2").getId(), null);
        assertStatus(both, "X", EventStatus.PENDING);
        finish(event(both, "X").getId(), null);
        assertStatus(both, "J", EventStatus.WAIT_PREDECESSOR);
        finish(event(both, "R1").getId(), null);
        assertStatus(both, "J", EventStatus.PENDING);
    }

    @Test
    void nestedXorAndJoinScopesAreIsolated() {
        PlanDefinition definition = definition(PlanRole.BUSINESS, CompletionPolicy.ALL_SELECTED);
        states(definition, 6);
        events(definition, Map.of(
            "F1", new String[]{"S0", "S1"},
            "A", new String[]{"S1", "S2"},
            "B", new String[]{"S1", "S2"},
            "F2", new String[]{"S2", "S3"},
            "C", new String[]{"S3", "S4"},
            "D", new String[]{"S3", "S4"},
            "J2", new String[]{"S4", "S2"},
            "J1", new String[]{"S2", "S5"}));
        edge(definition, "F1", "A", BranchKind.XOR, "A", null, true, "ROUTE_A");
        edge(definition, "F1", "B", BranchKind.XOR, "B", null, true, null);
        edge(definition, "A", "F2", BranchKind.SEQUENTIAL, "", null, false, null);
        edge(definition, "F2", "C", BranchKind.XOR, "C", null, true, "ROUTE_C");
        edge(definition, "F2", "D", BranchKind.XOR, "D", null, true, null);
        edge(definition, "C", "J2", BranchKind.SEQUENTIAL, "", null, false, null);
        edge(definition, "D", "J2", BranchKind.SEQUENTIAL, "", null, false, null);
        edge(definition, "J2", "J1", BranchKind.SEQUENTIAL, "", null, false, null);
        edge(definition, "B", "J1", BranchKind.SEQUENTIAL, "", null, false, null);

        ProcessInstance instance = instance(definition, null);
        materializer.materialize(instance);
        finish(event(instance, "F1").getId(), "{\"route\":\"A\"}");
        assertStatus(instance, "B", EventStatus.NOT_SELECTED);
        assertStatus(instance, "A", EventStatus.PENDING);
        finish(event(instance, "A").getId(), null);
        assertStatus(instance, "F2", EventStatus.PENDING);

        finish(event(instance, "F2").getId(), "{\"route\":\"C\"}");
        assertStatus(instance, "D", EventStatus.NOT_SELECTED);
        assertStatus(instance, "C", EventStatus.PENDING);
        finish(event(instance, "C").getId(), null);
        assertStatus(instance, "J2", EventStatus.PENDING);
        assertStatus(instance, "J1", EventStatus.WAIT_PREDECESSOR);
        finish(event(instance, "J2").getId(), null);
        assertStatus(instance, "J1", EventStatus.PENDING);
        finish(event(instance, "J1").getId(), null);

        assertThat(scope(instance, "FORK:F1").getParentScopeKey()).isEqualTo("ROOT");
        assertThat(scope(instance, "FORK:F2").getParentScopeKey()).isEqualTo("FORK:F1");
        assertThat(scope(instance, "JOIN:J2").getParentScopeKey()).isEqualTo("FORK:F2");
        assertThat(scope(instance, "JOIN:J1").getParentScopeKey()).isEqualTo("JOIN:J2");
    }

    @Test
    void decisionInputHashConflictDoesNotRerouteAndConcurrentFirstDecisionHasSingleWinner() throws Exception {
        PlanDefinition definition = definition(PlanRole.BUSINESS, CompletionPolicy.ALL_SELECTED);
        states(definition, 3);
        events(definition, Map.of(
            "F", new String[]{"S0", "S1"},
            "A", new String[]{"S1", "S2"},
            "B", new String[]{"S1", "S2"}));
        edge(definition, "F", "A", BranchKind.XOR, "A", null, true, "ROUTE_A");
        edge(definition, "F", "B", BranchKind.XOR, "B", null, true, null);
        List<ProcessEdge> edges = edgeMapper.selectList(Wrappers.<ProcessEdge>lambdaQuery()
            .eq(ProcessEdge::getDefinitionVersionId, definition.getId())
            .eq(ProcessEdge::getPredecessorEventCode, "F"));

        ProcessInstance first = instance(definition, null);
        materializer.materialize(first);
        BranchContext context = new BranchContext(first.getId(), "F", "FORK:F", "H1",
            Map.of("checkpoint.route", "A"));
        BranchSelector.Selection selected = branchSelector.select(context, edges);
        assertThat(selected.selectedBranchKeys()).containsExactly("A");
        assertThatThrownBy(() -> branchSelector.select(new BranchContext(first.getId(), "F", "FORK:F", "H2",
            Map.of("checkpoint.route", "A")), edges))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("输入已变化");

        ProcessInstance second = instance(definition, null);
        materializer.materialize(second);
        BranchContext same = new BranchContext(second.getId(), "F", "FORK:F", "H1",
            Map.of("checkpoint.route", "A"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<BranchSelector.Selection> call = () -> {
                start.await();
                return branchSelector.select(same, edges);
            };
            Future<BranchSelector.Selection> left = pool.submit(call);
            Future<BranchSelector.Selection> right = pool.submit(call);
            start.countDown();
            assertThat(left.get(20, TimeUnit.SECONDS).selectedBranchKeys()).isEqualTo(Set.of("A"));
            assertThat(right.get(20, TimeUnit.SECONDS).selectedBranchKeys()).isEqualTo(Set.of("A"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(tokenMapper.selectCount(Wrappers.<ProcessToken>lambdaQuery()
            .eq(ProcessToken::getInstanceId, second.getId())
            .eq(ProcessToken::getScopeKey, "FORK:F")
            .eq(ProcessToken::getBranchKey, ""))).isEqualTo(1L);
    }

    @Test
    void concurrentAndLastBranchClosesBarrierOnce() throws Exception {
        PlanDefinition definition = definition(PlanRole.BUSINESS, CompletionPolicy.ALL_SELECTED);
        states(definition, 4);
        events(definition, Map.of(
            "F", new String[]{"S0", "S1"},
            "A", new String[]{"S1", "S2"},
            "B", new String[]{"S1", "S2"},
            "J", new String[]{"S2", "S3"}));
        edge(definition, "F", "A", BranchKind.AND, "A", "J", false, null);
        edge(definition, "F", "B", BranchKind.AND, "B", "J", false, null);
        edge(definition, "A", "J", BranchKind.AND, "A", "J", false, null);
        edge(definition, "B", "J", BranchKind.AND, "B", "J", false, null);

        ProcessInstance instance = instance(definition, null);
        materializer.materialize(instance);
        finish(event(instance, "F").getId(), null);
        assertStatus(instance, "A", EventStatus.PENDING);
        assertStatus(instance, "B", EventStatus.PENDING);

        long aId = event(instance, "A").getId();
        long bId = event(instance, "B").getId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Void> a = () -> {
                start.await();
                activationService.activateSuccessors(markSuccess(aId, null));
                return null;
            };
            Callable<Void> b = () -> {
                start.await();
                activationService.activateSuccessors(markSuccess(bId, null));
                return null;
            };
            Future<Void> left = pool.submit(a);
            Future<Void> right = pool.submit(b);
            start.countDown();
            left.get(30, TimeUnit.SECONDS);
            right.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertStatus(instance, "J", EventStatus.PENDING);
        ProcessToken join = scope(instance, "JOIN:J");
        assertThat(join.getBarrierClosed()).isTrue();
        assertThat(join.getJoinedKeys()).contains("A|A", "B|B");
        assertThat(tokenMapper.selectCount(Wrappers.<ProcessToken>lambdaQuery()
            .eq(ProcessToken::getInstanceId, instance.getId())
            .eq(ProcessToken::getScopeKey, "JOIN:J")
            .eq(ProcessToken::getBranchKey, ""))).isEqualTo(1L);

        finish(event(instance, "J").getId(), null);
        assertThat(instanceMapper.selectById(instance.getId()).getPlanStatus()).isEqualTo(PlanStatus.COMPLETED);
    }

    @Test
    void materializerPrecreatesEveryForkAndJoinScopeBeforeExecution() {
        PlanDefinition definition = definition(PlanRole.BUSINESS, CompletionPolicy.ALL_SELECTED);
        states(definition, 4);
        events(definition, Map.of(
            "F", new String[]{"S0", "S1"},
            "A", new String[]{"S1", "S2"},
            "B", new String[]{"S1", "S2"},
            "J", new String[]{"S2", "S3"}));
        edge(definition, "F", "A", BranchKind.AND, "A", "J", false, null);
        edge(definition, "F", "B", BranchKind.AND, "B", "J", false, null);
        edge(definition, "A", "J", BranchKind.AND, "A", "J", false, null);
        edge(definition, "B", "J", BranchKind.AND, "B", "J", false, null);

        ProcessInstance instance = instance(definition, null);
        materializer.materialize(instance);

        assertThat(scope(instance, "FORK:F").getSelectedKeys()).isNull();
        assertThat(scope(instance, "JOIN:J").getSelectedKeys()).isNull();
        assertThat(tokenMapper.selectCount(Wrappers.<ProcessToken>lambdaQuery()
            .eq(ProcessToken::getInstanceId, instance.getId())
            .eq(ProcessToken::getScopeKey, "FORK:F")
            .eq(ProcessToken::getBranchKey, ""))).isEqualTo(1L);
        assertThat(tokenMapper.selectCount(Wrappers.<ProcessToken>lambdaQuery()
            .eq(ProcessToken::getInstanceId, instance.getId())
            .eq(ProcessToken::getScopeKey, "JOIN:J")
            .eq(ProcessToken::getBranchKey, ""))).isEqualTo(1L);

        finish(event(instance, "F").getId(), null);
        assertThat(scope(instance, "FORK:F").getSelectedKeys()).contains("A", "B");
        assertThat(scope(instance, "JOIN:J").getJoinedKeys()).isNullOrEmpty();
    }

    @Test
    void supervisorAggregationNeverCompletes() {
        PlanDefinition definition = definition(PlanRole.SUPERVISOR, CompletionPolicy.ALL_SELECTED);
        states(definition, 3);
        eventDefinition(definition, "D", "S0", "S1", EventRole.DECISION, EventDirection.INTERNAL);
        eventDefinition(definition, "L", "S1", "S2", EventRole.LOCAL, EventDirection.INTERNAL);
        edge(definition, "D", "L", BranchKind.SEQUENTIAL, "", null, false, null);

        ProcessInstance supervisor = instance(definition, null);
        materializer.materialize(supervisor);
        ProcessScheduleControl slot = heldSlot(supervisor.getId());

        finish(event(supervisor, "D").getId(), null);
        assertStatus(supervisor, "L", EventStatus.PENDING);
        finish(event(supervisor, "L").getId(), null);

        ProcessInstance reloaded = instanceMapper.selectById(supervisor.getId());
        assertThat(reloaded.getPlanStatus()).isEqualTo(PlanStatus.RUNNING);
        assertThat(reloaded.getEndedAt()).isNull();
        assertThat(controlMapper.selectById(slot.getId()).getSlotState()).isEqualTo(SlotState.HELD);
    }

    @Test
    void businessCompletionWaitsForOpenCallAndReleasesSlotOnce() {
        PlanDefinition definition = definition(PlanRole.BUSINESS, CompletionPolicy.ALL_SELECTED);
        states(definition, 2);
        eventDefinition(definition, "T", "S0", "S1", EventRole.BUSINESS, EventDirection.INTERNAL);

        ProcessInstance instance = instance(definition, null);
        materializer.materialize(instance);
        ProcessEvent event = event(instance, "T");
        ProcessScheduleControl slot = heldSlot(instance.getId());
        ProcessScheduleControl call = callPermit(event.getId(), PermitState.WAIT_RESULT);

        finish(event.getId(), null);
        assertThat(instanceMapper.selectById(instance.getId()).getPlanStatus()).isEqualTo(PlanStatus.RUNNING);
        assertThat(controlMapper.selectById(slot.getId()).getSlotState()).isEqualTo(SlotState.HELD);

        release(call);
        activationService.updatePlanStatus(instance.getId());
        assertThat(instanceMapper.selectById(instance.getId()).getPlanStatus()).isEqualTo(PlanStatus.COMPLETED);
        ProcessScheduleControl released = controlMapper.selectById(slot.getId());
        assertThat(released.getSlotState()).isEqualTo(SlotState.RELEASED);
        assertThat(released.getRevision()).isEqualTo(2L);

        activationService.updatePlanStatus(instance.getId());
        assertThat(controlMapper.selectById(slot.getId()).getRevision()).isEqualTo(2L);
    }

    @Test
    void skipKeepsEvidenceAndDoesNotCreateCallOrRunAction() {
        PlanDefinition definition = definition(PlanRole.BUSINESS, CompletionPolicy.ALL_SELECTED);
        states(definition, 3);
        events(definition, Map.of(
            "A", new String[]{"S0", "S1"},
            "B", new String[]{"S1", "S2"}));
        edge(definition, "A", "B", BranchKind.SEQUENTIAL, "", null, false, null);

        ProcessInstance instance = instance(definition, null);
        materializer.materialize(instance);
        ProcessEvent event = markSkipped(event(instance, "A").getId(), "{\"reason\":\"disabled\"}");
        activationService.activateSuccessors(event);

        ProcessEvent reloaded = eventMapper.selectById(event.getId());
        assertThat(reloaded.getEventStatus()).isEqualTo(EventStatus.SKIPPED);
        assertThat(reloaded.getEffectResult()).isEqualTo(EffectResult.SKIPPED);
        assertThat(attemptMapper.selectCount(Wrappers.<ProcessAttempt>lambdaQuery()
            .eq(ProcessAttempt::getEventId, event.getId()))).isEqualTo(1L);
        assertThat(attemptMapper.selectOne(Wrappers.<ProcessAttempt>lambdaQuery()
            .eq(ProcessAttempt::getEventId, event.getId())).getCheckpointStatus())
            .isEqualTo(CheckpointStatus.SKIPPED);
        assertThat(controlMapper.selectCount(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.CALL))).isZero();
        assertStatus(instance, "B", EventStatus.PENDING);
    }

    private PlanDefinition definition(PlanRole role, CompletionPolicy policy) {
        long id = IDS.incrementAndGet();
        PlanDefinition definition = new PlanDefinition();
        definition.setId(id);
        definition.setPlanCode("BRANCH_" + id);
        definition.setPlanVersion(1);
        definition.setPlanName("分支测试" + id);
        definition.setPlanRole(role);
        definition.setStatus(DefinitionStatus.ACTIVE);
        definition.setRevision(1L);
        definition.setSelfSystemCode("SELF");
        definition.setCompletionPolicy(policy);
        definition.setLeaseMs(60_000L);
        definition.setDelFlag("0");
        definitionMapper.insert(definition);
        return definition;
    }

    private void states(PlanDefinition definition, int count) {
        for (int index = 0; index < count; index++) {
            ProcessState state = new ProcessState();
            state.setId(IDS.incrementAndGet());
            state.setDefinitionVersionId(definition.getId());
            state.setStateCode("S" + index);
            state.setDisplayName("状态" + index);
            state.setStateKind(index == 0 ? StateKind.START : (index == count - 1 ? StateKind.END : StateKind.NORMAL));
            state.setValidatorKey("BRANCH_VALIDATOR");
            state.setValidatorVersion("1");
            state.setTerminal(index == count - 1);
            state.setSortOrder(index);
            stateMapper.insert(state);
        }
    }

    private void events(PlanDefinition definition, Map<String, String[]> graph) {
        graph.forEach((code, states) -> eventDefinition(definition, code, states[0], states[1],
            EventRole.BUSINESS, EventDirection.INTERNAL));
    }

    private ProcessEventDefinition eventDefinition(PlanDefinition definition, String code, String source,
                                                   String target, EventRole role, EventDirection direction) {
        ProcessEventDefinition event = new ProcessEventDefinition();
        event.setId(IDS.incrementAndGet());
        event.setDefinitionVersionId(definition.getId());
        event.setEventCode(code);
        event.setDisplayName(code);
        event.setEventRole(role);
        event.setEventDirection(direction);
        event.setSystemCode("SELF");
        event.setSourceStateCode(source);
        event.setTargetStateCode(target);
        event.setEnabled(Boolean.TRUE);
        event.setPriority(0);
        event.setEditPolicy(EditPolicy.EDITABLE);
        event.setGraphEditPolicy(GraphEditPolicy.EDITABLE_NEW_VERSION);
        event.setLocalActionKey("LOCAL_" + code);
        event.setLocalActionVersion("1");
        event.setLocalEffectKey("EFFECT_" + code);
        event.setEffectKey("EFFECT_" + code);
        eventDefinitionMapper.insert(event);
        return event;
    }

    private void edge(PlanDefinition definition, String from, String to, BranchKind kind, String branchKey,
                      String joinCode, boolean xorCondition, String conditionKey) {
        ProcessEdge edge = new ProcessEdge();
        edge.setId(IDS.incrementAndGet());
        edge.setDefinitionVersionId(definition.getId());
        edge.setPredecessorEventCode(from);
        edge.setSuccessorEventCode(to);
        edge.setBranchKey(branchKey);
        edge.setBranchKind(kind);
        edge.setConditionKey(conditionKey);
        edge.setConditionVersion(conditionKey == null ? null : "1");
        edge.setDefaultBranch(kind == BranchKind.XOR && conditionKey == null && xorCondition);
        edge.setJoinCode(joinCode);
        edge.setPriority(0);
        edgeMapper.insert(edge);
    }

    private ProcessInstance instance(PlanDefinition definition, String selectedRoots) {
        long id = IDS.incrementAndGet();
        ProcessInstance instance = new ProcessInstance();
        instance.setId(id);
        instance.setPlanRole(definition.getPlanRole());
        instance.setDefinitionVersionId(definition.getId());
        instance.setDefinitionRevision(definition.getRevision());
        instance.setScheduleId(8001L);
        instance.setPlanType("TEST");
        instance.setBusinessScheduleCode("BS_TEST");
        instance.setScheduleScope("BRANCH_SCOPE");
        instance.setModeSnapshot(PlanMode.QUEUE);
        instance.setHoldStart(Boolean.FALSE);
        instance.setPlanStatus(PlanStatus.RUNNING);
        instance.setInstancePriority(0);
        instance.setSelectedRootSet(selectedRoots);
        instance.setInputHash("INPUT:" + id);
        instance.setRoundNo(0L);
        instance.setStopRequested(Boolean.FALSE);
        instance.setCancelRequested(Boolean.FALSE);
        instance.setStartedAt(LocalDateTime.now());
        instance.setInstanceVersion(1L);
        if (definition.getPlanRole() == PlanRole.SUPERVISOR) {
            instance.setSupervisorKey("SUP:" + id);
        } else {
            instance.setBusinessTaskKey("BT:" + id);
            instance.setBusinessType("TEST");
            instance.setBusinessId(String.valueOf(id));
            instance.setTaskKey("TASK:" + id);
        }
        instanceMapper.insert(instance);
        return instance;
    }

    private ProcessEvent event(ProcessInstance instance, String code) {
        return eventMapper.selectOne(Wrappers.<ProcessEvent>lambdaQuery()
            .eq(ProcessEvent::getInstanceId, instance.getId())
            .eq(ProcessEvent::getEventCode, code)
            .orderByAsc(ProcessEvent::getId)
            .last("LIMIT 1"));
    }

    private ProcessToken scope(ProcessInstance instance, String scopeKey) {
        return tokenMapper.selectOne(Wrappers.<ProcessToken>lambdaQuery()
            .eq(ProcessToken::getInstanceId, instance.getId())
            .eq(ProcessToken::getScopeKey, scopeKey)
            .eq(ProcessToken::getBranchKey, ""));
    }

    private void finish(long eventId, String checkpointJson) {
        activationService.activateSuccessors(markSuccess(eventId, checkpointJson));
    }

    private ProcessEvent markSuccess(long eventId, String checkpointJson) {
        return markTerminal(eventId, EventStatus.SUCCESS, EffectResult.SUCCEEDED, CheckpointStatus.SUCCEEDED,
            checkpointJson);
    }

    private ProcessEvent markSkipped(long eventId, String checkpointJson) {
        return markTerminal(eventId, EventStatus.SKIPPED, EffectResult.SKIPPED, CheckpointStatus.SKIPPED,
            checkpointJson);
    }

    private ProcessEvent markTerminal(long eventId, EventStatus status, EffectResult effect,
                                      CheckpointStatus checkpointStatus, String checkpointJson) {
        ProcessEvent event = eventMapper.selectById(eventId);
        LocalDateTime now = LocalDateTime.now();
        ProcessAttempt attempt = new ProcessAttempt();
        attempt.setEventId(eventId);
        attempt.setAttemptNo(1);
        attempt.setCheckpointStatus(checkpointStatus);
        attempt.setSent(Boolean.FALSE);
        attempt.setRequestId("REQ:" + eventId);
        attempt.setInputHash(event.getInputHash());
        attempt.setIdempotencyKey(event.getIdempotencyKey());
        attempt.setOperationVersion("1");
        attempt.setLocalActionVersion(event.getLocalActionVersion());
        attempt.setLocalEffectKey(event.getLocalEffectKey());
        attempt.setEvidenceRef("EVID:" + eventId);
        attempt.setEffectResult(effect);
        attempt.setSourceStateCode(event.getSourceStateCode());
        attempt.setTargetStateCode(event.getTargetStateCode());
        attempt.setOwner("test");
        attempt.setLeaseEpoch(event.getLeaseEpoch());
        attempt.setExecutionPurpose(ExecutionPurpose.NORMAL);
        attempt.setCheckpointJson(checkpointJson);
        attempt.setStartedAt(now);
        attempt.setEndedAt(now);
        attempt.setRecordedAt(now);
        attemptMapper.insert(attempt);

        event.setLastAttemptId(attempt.getId());
        event.setEventStatus(status);
        event.setEffectResult(effect);
        event.setOutcome(effect.name());
        event.setEndedAt(now);
        event.setEventVersion(event.getEventVersion() + 1);
        eventMapper.updateById(event);
        return event;
    }

    private ProcessScheduleControl heldSlot(long instanceId) {
        ProcessScheduleControl slot = control();
        slot.setScopeType(ScheduleControlScope.PLAN_SLOT);
        slot.setScopeKey(String.valueOf(instanceId));
        slot.setDuty("PLAN_SLOT");
        slot.setSupervisorInstanceId(9001L);
        slot.setChildInstanceId(instanceId);
        slot.setSlotState(SlotState.HELD);
        slot.setDesiredState("HELD");
        slot.setObservedState("HELD");
        controlMapper.insert(slot);
        return slot;
    }

    private ProcessScheduleControl callPermit(long eventId, PermitState permitState) {
        ProcessScheduleControl call = control();
        call.setScopeType(ScheduleControlScope.CALL);
        call.setScopeKey(String.valueOf(eventId));
        call.setDuty("CALL_PERMIT");
        call.setPermitState(permitState);
        call.setResourceKey("RES");
        controlMapper.insert(call);
        return call;
    }

    private ProcessScheduleControl control() {
        ProcessScheduleControl control = new ProcessScheduleControl();
        control.setId(IDS.incrementAndGet());
        control.setControlState(ControlState.AUTO_RUNNING);
        control.setRevision(1L);
        control.setEpoch(1L);
        control.setWakePending(Boolean.FALSE);
        control.setRoundNo(0L);
        control.setFairnessCount(0);
        control.setLastActivityAt(LocalDateTime.now());
        return control;
    }

    private void release(ProcessScheduleControl call) {
        int rows = controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
            .eq(ProcessScheduleControl::getId, call.getId())
            .eq(ProcessScheduleControl::getRevision, call.getRevision())
            .set(ProcessScheduleControl::getPermitState, PermitState.RELEASED)
            .set(ProcessScheduleControl::getRevision, call.getRevision() + 1)
            .set(ProcessScheduleControl::getLastActivityAt, LocalDateTime.now()));
        assertThat(rows).isEqualTo(1);
    }

    private void assertStatus(ProcessInstance instance, String code, EventStatus status) {
        assertThat(event(instance, code).getEventStatus()).isEqualTo(status);
    }
}
