/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配计划
 * @Version      : V1.0
 * @Description  : 验证分配锁位、释放计划可由正式动作和状态校验器发布；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.process;

import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessEdge;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessState;
import com.innolux.iws.process.domain.ProcessSystemPolicy;
import com.innolux.iws.process.enums.BranchKind;
import com.innolux.iws.process.enums.CompletionPolicy;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventRole;
import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.PostingMode;
import com.innolux.iws.process.enums.StateKind;
import com.innolux.iws.process.mapper.BusinessScheduleMapper;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.runtime.CapabilityRegistry;
import com.innolux.iws.process.runtime.LocalAction;
import com.innolux.iws.process.runtime.StateValidator;
import com.innolux.iws.process.runtime.SupervisorAdmitQueueAction;
import com.innolux.iws.process.runtime.SupervisorCandidateDecisionAction;
import com.innolux.iws.process.runtime.SupervisorCandidateSelector;
import com.innolux.iws.process.runtime.SupervisorRules;
import com.innolux.iws.process.runtime.SupervisorRuntimeService;
import com.innolux.iws.process.support.DefinitionGraphBuilder;
import com.innolux.iws.process.support.DefinitionValidator;
import com.innolux.iws.wms.allocation.release.service.WmsAllocationReleaseService;
import com.innolux.iws.wms.allocation.reservation.service.WmsAllocationReservationService;
import com.innolux.iws.wms.process.WmsFrozenActionInputReader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 分配计划发布合同测试。 */
@Tag("local")
class WmsAllocationPlanContractTest {

    private static final String SYSTEM = "IWS";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final WmsInventoryProcessConfiguration configuration =
        new WmsInventoryProcessConfiguration();

    @Test
    void allocationLockPlanPassesPublicationValidationWithExactActionsAndStates() {
        CapabilityRegistry registry = registry();

        var result = validator(registry).validate(
            definition("WMS_ALLOCATION_LOCK", "ALLOCATION_LOCK", "WMS分配锁位"),
            lockStates(),
            lockEvents(),
            lockEdges(),
            policies()
        );

        assertThat(result.errors()).isEmpty();
        assertThat(result.valid()).isTrue();
        assertThat(registry.localActionDescriptor(
            WmsAllocationEvaluateAction.ACTION_KEY, "1").transactionMode()).isEqualTo("SAME_TX");
        assertThat(registry.localActionDescriptor(
            WmsAllocationReserveAction.ACTION_KEY, "1").transactionMode()).isEqualTo("SAME_TX");
        assertThat(registry.localActionDescriptor(
            WmsAllocationVerifyAction.ACTION_KEY, "1").transactionMode()).isEqualTo("SAME_TX");
        assertThat(registry.validator(
            WmsAllocationLockStateValidator.VALIDATOR_KEY,
            WmsAllocationLockStateValidator.VALIDATOR_VERSION
        )).isInstanceOf(WmsAllocationLockStateValidator.class);
    }

    @Test
    void allocationReleasePlanPassesPublicationValidationWithExactActionsAndStates() {
        CapabilityRegistry registry = registry();

        var result = validator(registry).validate(
            definition("WMS_ALLOCATION_RELEASE", "ALLOCATION_RELEASE", "WMS分配释放"),
            releaseStates(),
            releaseEvents(),
            releaseEdges(),
            policies()
        );

        assertThat(result.errors()).isEmpty();
        assertThat(result.valid()).isTrue();
        assertThat(registry.localActionDescriptor(
            WmsAllocationCheckReleaseAction.ACTION_KEY, "1").transactionMode()).isEqualTo("SAME_TX");
        assertThat(registry.localActionDescriptor(
            WmsAllocationReleaseAction.ACTION_KEY, "1").transactionMode()).isEqualTo("SAME_TX");
        assertThat(registry.localActionDescriptor(
            WmsAllocationVerifyReleaseAction.ACTION_KEY, "1").transactionMode()).isEqualTo("SAME_TX");
        assertThat(registry.validator(
            WmsAllocationReleaseStateValidator.VALIDATOR_KEY,
            WmsAllocationReleaseStateValidator.VALIDATOR_VERSION
        )).isInstanceOf(WmsAllocationReleaseStateValidator.class);
    }

    @Test
    void allocationSupervisorPlanUsesPlatformDecisionAndAdmissionActions() {
        CapabilityRegistry registry = registry();

        var result = validator(registry).validate(
            supervisorDefinition("WMS_ALLOCATION_LOCK_SUPERVISOR", "ALLOCATION_LOCK", "WMS分配锁位监督"),
            supervisorStates(),
            supervisorEvents(),
            supervisorEdges(),
            policies()
        );

        assertThat(result.errors()).isEmpty();
        assertThat(result.valid()).isTrue();
        assertThat(registry.localActionDescriptor(
            SupervisorRules.ACTION_DECISION, "1").transactionMode()).isEqualTo("SAME_TX");
        assertThat(registry.localActionDescriptor(
            SupervisorRules.ACTION_ADMIT, "1").transactionMode()).isEqualTo("SAME_TX");
        assertThat(registry.validator(
            WmsAllocationSupervisorStateValidator.VALIDATOR_KEY,
            WmsAllocationSupervisorStateValidator.VALIDATOR_VERSION
        )).isInstanceOf(WmsAllocationSupervisorStateValidator.class);
    }

    private CapabilityRegistry registry() {
        WmsAllocationReservationService reservationService =
            mock(WmsAllocationReservationService.class);
        WmsAllocationReleaseService releaseService =
            mock(WmsAllocationReleaseService.class);
        ProcessInstanceMapper instanceMapper = mock(ProcessInstanceMapper.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        WmsFrozenActionInputReader inputReader = mock(WmsFrozenActionInputReader.class);
        List<LocalAction> actions = List.of(
            configuration.wmsAllocationEvaluateAction(
                reservationService, instanceMapper, jsonMapper, inputReader),
            configuration.wmsAllocationReserveAction(
                reservationService, instanceMapper, jsonMapper, inputReader),
            configuration.wmsAllocationVerifyAction(
                reservationService, jsonMapper, inputReader),
            configuration.wmsAllocationCheckReleaseAction(
                releaseService, instanceMapper, jsonMapper, inputReader),
            configuration.wmsAllocationReleaseAction(
                releaseService, instanceMapper, jsonMapper, inputReader),
            configuration.wmsAllocationVerifyReleaseAction(
                releaseService, instanceMapper, jsonMapper, inputReader),
            new SupervisorCandidateDecisionAction(
                instanceMapper,
                mock(ProcessScheduleControlMapper.class),
                mock(PlanDefinitionMapper.class),
                mock(BusinessScheduleMapper.class),
                mock(ProcessEventMapper.class),
                mock(SupervisorCandidateSelector.class)
            ),
            new SupervisorAdmitQueueAction(
                mock(ProcessEventMapper.class),
                mock(SupervisorRuntimeService.class)
            )
        );
        List<StateValidator> validators = List.of(
            configuration.wmsAllocationLockStateValidator(jdbcTemplate),
            configuration.wmsAllocationReleaseStateValidator(jdbcTemplate),
            configuration.wmsAllocationSupervisorStateValidator(jdbcTemplate)
        );
        return new CapabilityRegistry(
            List.of(configuration.wmsInventoryEventTypeProvider()),
            List.of(),
            actions,
            validators,
            List.of()
        );
    }

    private DefinitionValidator validator(CapabilityRegistry registry) {
        return new DefinitionValidator(registry, new DefinitionGraphBuilder());
    }

    private PlanDefinition definition(
        String planCode,
        String planType,
        String planName
    ) {
        PlanDefinition definition = new PlanDefinition();
        definition.setPlanCode(planCode);
        definition.setPlanVersion(1);
        definition.setPlanType(planType);
        definition.setPlanName(planName);
        definition.setPlanRole(PlanRole.BUSINESS);
        definition.setSelfSystemCode(SYSTEM);
        definition.setCompletionPolicy(CompletionPolicy.ALL_SELECTED);
        definition.setMaxExecutionMs(30_000L);
        definition.setDescription(planName);
        return definition;
    }

    private PlanDefinition supervisorDefinition(
        String planCode,
        String planType,
        String planName
    ) {
        PlanDefinition definition = definition(planCode, planType, planName);
        definition.setPlanRole(PlanRole.SUPERVISOR);
        definition.setMode(PlanMode.QUEUE);
        definition.setActivePlanLimit(8);
        definition.setFairnessWindowSize(32);
        definition.setRoundBatchLimit(32);
        definition.setRoundMaxDurationMs(5_000L);
        definition.setWorkerBatchLimit(32);
        definition.setWorkerMaxDurationMs(5_000L);
        definition.setLeaseMs(30_000L);
        definition.setRetryMax(2);
        definition.setRetryBackoffMs(1_000L);
        definition.setJobMaxRetryTimes(0);
        return definition;
    }

    private List<ProcessState> lockStates() {
        return List.of(
            state(
                WmsAllocationLockStateValidator.STATE_START,
                "待评估",
                StateKind.START,
                false,
                10,
                WmsAllocationLockStateValidator.VALIDATOR_KEY
            ),
            state(
                WmsAllocationLockStateValidator.STATE_EVALUATED,
                "已评估",
                StateKind.NORMAL,
                false,
                20,
                WmsAllocationLockStateValidator.VALIDATOR_KEY
            ),
            state(
                WmsAllocationLockStateValidator.STATE_RESERVED,
                "已锁位",
                StateKind.NORMAL,
                false,
                30,
                WmsAllocationLockStateValidator.VALIDATOR_KEY
            ),
            state(
                WmsAllocationLockStateValidator.STATE_END,
                "已完成",
                StateKind.END,
                true,
                40,
                WmsAllocationLockStateValidator.VALIDATOR_KEY
            )
        );
    }

    private List<ProcessState> releaseStates() {
        return List.of(
            state(
                WmsAllocationReleaseStateValidator.STATE_START,
                "待检查",
                StateKind.START,
                false,
                10,
                WmsAllocationReleaseStateValidator.VALIDATOR_KEY
            ),
            state(
                WmsAllocationReleaseStateValidator.STATE_EVIDENCE_CHECKED,
                "证据已检查",
                StateKind.NORMAL,
                false,
                20,
                WmsAllocationReleaseStateValidator.VALIDATOR_KEY
            ),
            state(
                WmsAllocationReleaseStateValidator.STATE_RELEASED,
                "已释放",
                StateKind.NORMAL,
                false,
                30,
                WmsAllocationReleaseStateValidator.VALIDATOR_KEY
            ),
            state(
                WmsAllocationReleaseStateValidator.STATE_END,
                "已完成",
                StateKind.END,
                true,
                40,
                WmsAllocationReleaseStateValidator.VALIDATOR_KEY
            )
        );
    }

    private ProcessState state(
        String code,
        String displayName,
        StateKind kind,
        boolean terminal,
        int sortOrder,
        String validatorKey
    ) {
        ProcessState state = new ProcessState();
        state.setStateCode(code);
        state.setDisplayName(displayName);
        state.setStateKind(kind);
        state.setValidatorKey(validatorKey);
        state.setValidatorVersion("1");
        state.setTerminal(terminal);
        state.setSortOrder(sortOrder);
        return state;
    }

    private List<ProcessEventDefinition> lockEvents() {
        return List.of(
            event(
                WmsAllocationEvaluateAction.ACTION_KEY,
                "分配评估",
                WmsAllocationEvaluateAction.LOCAL_EFFECT_KEY,
                WmsAllocationLockStateValidator.STATE_START,
                WmsAllocationLockStateValidator.STATE_EVALUATED,
                10
            ),
            event(
                WmsAllocationReserveAction.ACTION_KEY,
                "分配储位锁位",
                WmsAllocationReserveAction.LOCAL_EFFECT_KEY,
                WmsAllocationLockStateValidator.STATE_EVALUATED,
                WmsAllocationLockStateValidator.STATE_RESERVED,
                20
            ),
            event(
                WmsAllocationVerifyAction.ACTION_KEY,
                "分配锁位核验",
                WmsAllocationVerifyAction.LOCAL_EFFECT_KEY,
                WmsAllocationLockStateValidator.STATE_RESERVED,
                WmsAllocationLockStateValidator.STATE_END,
                30
            )
        );
    }

    private List<ProcessEventDefinition> releaseEvents() {
        return List.of(
            event(
                WmsAllocationCheckReleaseAction.ACTION_KEY,
                "释放证据检查",
                WmsAllocationCheckReleaseAction.LOCAL_EFFECT_KEY,
                WmsAllocationReleaseStateValidator.STATE_START,
                WmsAllocationReleaseStateValidator.STATE_EVIDENCE_CHECKED,
                10
            ),
            event(
                WmsAllocationReleaseAction.ACTION_KEY,
                "释放分配锁",
                WmsAllocationReleaseAction.LOCAL_EFFECT_KEY,
                WmsAllocationReleaseStateValidator.STATE_EVIDENCE_CHECKED,
                WmsAllocationReleaseStateValidator.STATE_RELEASED,
                20
            ),
            event(
                WmsAllocationVerifyReleaseAction.ACTION_KEY,
                "释放终态核验",
                WmsAllocationVerifyReleaseAction.LOCAL_EFFECT_KEY,
                WmsAllocationReleaseStateValidator.STATE_RELEASED,
                WmsAllocationReleaseStateValidator.STATE_END,
                30
            )
        );
    }

    private List<ProcessState> supervisorStates() {
        return List.of(
            state(
                WmsAllocationSupervisorStateValidator.STATE_START,
                "待决策",
                StateKind.START,
                false,
                10,
                WmsAllocationSupervisorStateValidator.VALIDATOR_KEY
            ),
            state(
                WmsAllocationSupervisorStateValidator.STATE_DECIDED,
                "已决策",
                StateKind.NORMAL,
                false,
                20,
                WmsAllocationSupervisorStateValidator.VALIDATOR_KEY
            ),
            state(
                WmsAllocationSupervisorStateValidator.STATE_END,
                "本轮已准入",
                StateKind.END,
                true,
                30,
                WmsAllocationSupervisorStateValidator.VALIDATOR_KEY
            )
        );
    }

    private List<ProcessEventDefinition> supervisorEvents() {
        return List.of(
            event(
                SupervisorRules.ACTION_DECISION,
                "监督候选决策",
                EventRole.DECISION,
                "WMS:" + SupervisorRules.ACTION_DECISION,
                WmsAllocationSupervisorStateValidator.STATE_START,
                WmsAllocationSupervisorStateValidator.STATE_DECIDED,
                10
            ),
            event(
                SupervisorRules.ACTION_ADMIT,
                "监督逐候选准入",
                EventRole.LOCAL,
                "WMS:" + SupervisorRules.ACTION_ADMIT,
                WmsAllocationSupervisorStateValidator.STATE_DECIDED,
                WmsAllocationSupervisorStateValidator.STATE_END,
                20
            )
        );
    }

    private List<ProcessEdge> supervisorEdges() {
        return List.of(edge(SupervisorRules.ACTION_DECISION, SupervisorRules.ACTION_ADMIT));
    }

    private ProcessEventDefinition event(
        String code,
        String displayName,
        String localEffectKey,
        String sourceState,
        String targetState,
        int priority
    ) {
        return event(
            code,
            displayName,
            EventRole.BUSINESS,
            localEffectKey,
            sourceState,
            targetState,
            priority
        );
    }

    private ProcessEventDefinition event(
        String code,
        String displayName,
        EventRole eventRole,
        String localEffectKey,
        String sourceState,
        String targetState,
        int priority
    ) {
        ProcessEventDefinition event = new ProcessEventDefinition();
        event.setEventCode(code);
        event.setDisplayName(displayName);
        event.setEventRole(eventRole);
        event.setEventDirection(EventDirection.INTERNAL);
        event.setEventTypeKey(WmsInventoryProcessConfiguration.EVENT_TYPE_KEY);
        event.setEventTypeVersion(WmsInventoryProcessConfiguration.EVENT_TYPE_VERSION);
        event.setLocalActionKey(code);
        event.setLocalActionVersion("1");
        event.setLocalEffectKey(localEffectKey);
        event.setSystemCode(SYSTEM);
        event.setTargetSystemCode(SYSTEM);
        event.setSourceStateCode(sourceState);
        event.setTargetStateCode(targetState);
        event.setEnabled(true);
        event.setPriority(priority);
        return event;
    }

    private List<ProcessEdge> lockEdges() {
        return edges(
            WmsAllocationEvaluateAction.ACTION_KEY,
            WmsAllocationReserveAction.ACTION_KEY,
            WmsAllocationVerifyAction.ACTION_KEY
        );
    }

    private List<ProcessEdge> releaseEdges() {
        return edges(
            WmsAllocationCheckReleaseAction.ACTION_KEY,
            WmsAllocationReleaseAction.ACTION_KEY,
            WmsAllocationVerifyReleaseAction.ACTION_KEY
        );
    }

    private List<ProcessEdge> edges(String first, String second, String third) {
        return List.of(edge(first, second), edge(second, third));
    }

    private ProcessEdge edge(String predecessor, String successor) {
        ProcessEdge edge = new ProcessEdge();
        edge.setPredecessorEventCode(predecessor);
        edge.setSuccessorEventCode(successor);
        edge.setBranchKind(BranchKind.SEQUENTIAL);
        return edge;
    }

    private List<ProcessSystemPolicy> policies() {
        ProcessSystemPolicy policy = new ProcessSystemPolicy();
        policy.setSystemCode(SYSTEM);
        policy.setPostingMode(PostingMode.AUTOMATIC);
        return List.of(policy);
    }
}
