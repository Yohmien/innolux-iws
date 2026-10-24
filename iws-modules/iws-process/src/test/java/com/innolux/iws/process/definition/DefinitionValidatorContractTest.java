/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 定义结构、方向、单动作与跳过合同规则；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.definition;

import com.innolux.iws.process.api.DefinitionValidationVo;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessEdge;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessState;
import com.innolux.iws.process.domain.ProcessSystemPolicy;
import com.innolux.iws.process.enums.BranchKind;
import com.innolux.iws.process.enums.CallPurpose;
import com.innolux.iws.process.enums.CompletionContract;
import com.innolux.iws.process.enums.CompletionPolicy;
import com.innolux.iws.process.enums.EditPolicy;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventRole;
import com.innolux.iws.process.enums.GraphEditPolicy;
import com.innolux.iws.process.enums.OperationKind;
import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.PostingMode;
import com.innolux.iws.process.enums.SkipPolicy;
import com.innolux.iws.process.enums.StateKind;
import com.innolux.iws.process.support.DefinitionGraphBuilder;
import com.innolux.iws.process.support.DefinitionValidator;
import com.innolux.iws.process.runtime.CallContractDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定义校验合同测试，不访问数据库。
 */
@Tag("dev")
class DefinitionValidatorContractTest {

    private final DefinitionValidator validator = new DefinitionValidator(
        DefinitionCapabilityFixture.registry(), new DefinitionGraphBuilder());

    @Test
    void validBusinessGraphPasses() {
        DefinitionValidationVo result = validate(baseDefinition(), baseStates(), baseEvents(), baseEdges(),
            basePolicies(PostingMode.AUTOMATIC));
        assertTrue(result.valid(), String.join(";", result.errors()));
    }

    @Test
    void businessExecutionBudgetIsPublishableButMustBePositive() {
        PlanDefinition definition = baseDefinition();
        definition.setMaxExecutionMs(60000L);
        DefinitionValidationVo valid = validate(definition, baseStates(), baseEvents(), baseEdges(),
            basePolicies(PostingMode.AUTOMATIC));
        assertTrue(valid.valid(), String.join(";", valid.errors()));
        definition.setMaxExecutionMs(0L);
        assertFalse(validate(definition, baseStates(), baseEvents(), baseEdges(),
            basePolicies(PostingMode.AUTOMATIC)).valid());
    }

    @Test
    void callContractCallerCandidatesDefaultToEmpty() {
        CallContractDescriptor contract = new CallContractDescriptor("k", "1", "能力", OperationKind.QUERY,
            EventDirection.OUTBOUND, CallPurpose.QUERY, CompletionContract.BUSINESS_COMPLETION, "GET", "/q",
            "R-Q");
        assertTrue(contract.callerCandidates().isEmpty());
    }

    @Test
    void incrementalStructureAllowsSingleNormalStateButPublishDoesNot() {
        PlanDefinition definition = baseDefinition();
        definition.setExceptionStateCode(null);
        List<ProcessState> states = List.of(state("S_ONLY", StateKind.NORMAL, false, 0));
        DefinitionValidationVo structure = validator.validateStructure(definition, states, List.of(), List.of(),
            List.of());
        assertTrue(structure.valid(), String.join(";", structure.errors()));
        DefinitionValidationVo publication = validator.validate(definition, states, List.of(), List.of(), List.of());
        assertFalse(publication.valid());
        assertTrue(hasError(publication, "至少需要一个起点状态"));
        assertTrue(hasError(publication, "至少需要一个终点状态"));
    }

    @Test
    void missingPolicyIsRejected() {
        DefinitionValidationVo result = validate(baseDefinition(), baseStates(), baseEvents(), baseEdges(),
            List.of(policy("IWS", PostingMode.AUTOMATIC)));
        assertFalse(result.valid());
        assertTrue(hasError(result, "缺少系统模式"));
    }

    @Test
    void internalCannotCarryHttpContractOrEffect() {
        List<ProcessEventDefinition> events = new ArrayList<>(baseEvents());
        ProcessEventDefinition internal = events.stream().filter(item -> item.getEventCode().equals("E_DONE"))
            .findFirst().orElseThrow();
        internal.setCallContractKey(DefinitionCapabilityFixture.CONTRACT_POST);
        internal.setEffectKey("effect-int");
        DefinitionValidationVo result = validate(baseDefinition(), baseStates(), events, baseEdges(),
            basePolicies(PostingMode.AUTOMATIC));
        assertFalse(result.valid());
        assertTrue(hasError(result, "不得绑定调用合同"));
    }

    @Test
    void outboundMismatchedOperationAndCompletionRejected() {
        List<ProcessEventDefinition> events = new ArrayList<>(baseEvents());
        ProcessEventDefinition outbound = events.stream().filter(item -> item.getEventCode().equals("E_OUT"))
            .findFirst().orElseThrow();
        outbound.setOperationKind(OperationKind.CONTROL);
        outbound.setCompletionContract(CompletionContract.BUSINESS_COMPLETION);
        DefinitionValidationVo result = validate(baseDefinition(), baseStates(), events, baseEdges(),
            basePolicies(PostingMode.AUTOMATIC));
        assertFalse(result.valid());
        assertTrue(hasError(result, "动作类型与调用合同不一致"));
        assertTrue(hasError(result, "完成合同与能力不一致"));
    }

    @Test
    void disabledEventNeedsAllowAndSkipContract() {
        List<ProcessEventDefinition> events = new ArrayList<>(baseEvents());
        ProcessEventDefinition outbound = events.stream().filter(item -> item.getEventCode().equals("E_OUT"))
            .findFirst().orElseThrow();
        outbound.setEnabled(false);
        outbound.setSkipPolicy(SkipPolicy.FORBID);
        outbound.setSkipContractKey(null);
        DefinitionValidationVo result = validate(baseDefinition(), baseStates(), events, baseEdges(),
            basePolicies(PostingMode.AUTOMATIC));
        assertFalse(result.valid());
        assertTrue(hasError(result, "整事件禁用必须使用 ALLOW 跳过策略"));
    }

    @Test
    void bypassAcceptanceNeedsExplicitResultSkip() {
        List<ProcessEventDefinition> events = new ArrayList<>(baseEvents());
        ProcessEventDefinition result = events.stream()
            .filter(item -> item.getEventCode().equals("E_RESULT")).findFirst().orElseThrow();
        result.setSkipPolicy(SkipPolicy.FORBID);
        result.setSkipContractKey(null);
        DefinitionValidationVo validation = validate(baseDefinition(), baseStates(), events, baseEdges(),
            basePolicies(PostingMode.BYPASS));
        assertFalse(validation.valid());
        assertTrue(hasError(validation, "缺少显式结果事件跳过替代"));
    }

    @Test
    void bypassOnSystemWithoutPostingRejected() {
        DefinitionValidationVo result = validate(baseDefinition(), baseStates(), baseEvents(), baseEdges(),
            List.of(policy("IWS", PostingMode.BYPASS), policy("MID_WMS", PostingMode.AUTOMATIC)));
        assertFalse(result.valid());
        assertTrue(hasError(result, "没有可屏蔽的外部出站抛账事件"));
    }

    @Test
    void xorRequiresConditionOrDefaultAndSingleDefault() {
        List<ProcessEventDefinition> events = new ArrayList<>(baseEvents());
        events.add(internalEvent("E_ALT", "S_2", "S_3"));
        List<ProcessEdge> edges = new ArrayList<>(baseEdges());
        edges.add(edge("E_OUT", "E_ALT", "alt", BranchKind.XOR));
        edges.add(edge("E_OUT", "E_RESULT", "dup", BranchKind.XOR));
        DefinitionValidationVo result = validate(baseDefinition(), baseStates(), events, edges,
            basePolicies(PostingMode.AUTOMATIC));
        assertFalse(result.valid());
        assertTrue(hasError(result, "XOR 节点最多一个默认分支"));
    }

    @Test
    void orphanRootMustStartFromStartState() {
        List<ProcessEventDefinition> events = new ArrayList<>(baseEvents());
        ProcessEventDefinition orphan = internalEvent("E_ORPHAN", "S_3", "S_END");
        events.add(orphan);
        DefinitionValidationVo result = validate(baseDefinition(), baseStates(), events, baseEdges(),
            basePolicies(PostingMode.AUTOMATIC));
        assertFalse(result.valid());
        assertTrue(hasError(result, "根事件必须从起点状态出发"));
    }

    @Test
    void businessPlanRejectsSupervisionModeAndNonBusinessRole() {
        PlanDefinition definition = baseDefinition();
        definition.setMode(PlanMode.QUEUE);
        definition.setActivePlanLimit(2);
        definition.setFairnessWindowSize(2);
        List<ProcessEventDefinition> events = new ArrayList<>(baseEvents());
        events.get(0).setEventRole(EventRole.LOCAL);
        DefinitionValidationVo result = validate(definition, baseStates(), events, baseEdges(),
            basePolicies(PostingMode.AUTOMATIC));
        assertFalse(result.valid());
        assertTrue(hasError(result, "BUSINESS 计划不得配置监督调度模式"));
        assertTrue(hasError(result, "业务计划只能使用 BUSINESS 角色"));
    }

    @Test
    void recoveryBranchMustBeOutboundQuery() {
        List<ProcessEventDefinition> events = new ArrayList<>(baseEvents());
        ProcessEventDefinition query = events.stream().filter(item -> item.getEventCode().equals("E_RESULT"))
            .findFirst().orElseThrow();
        query.setRecoveryForEventCode("E_OUT");
        DefinitionValidationVo result = validate(baseDefinition(), baseStates(), events, baseEdges(),
            basePolicies(PostingMode.AUTOMATIC));
        assertFalse(result.valid());
        assertTrue(hasError(result, "恢复分支必须是 OUTBOUND QUERY"));
    }

    @Test
    void supervisorRejectsBusinessEventAndMissingNumericConfig() {
        PlanDefinition definition = supervisorDefinition();
        definition.setActivePlanLimit(null);
        definition.setFairnessWindowSize(null);
        ProcessEventDefinition decision = internalEvent("E_DECISION", "S_START", "S_NEXT");
        decision.setEventRole(EventRole.BUSINESS);
        ProcessEventDefinition local = internalEvent("E_LOCAL", "S_NEXT", "S_END");
        local.setEventRole(EventRole.LOCAL);
        DefinitionValidationVo result = validate(definition, supervisorStates(), List.of(decision, local),
            List.of(edge("E_DECISION", "E_LOCAL", "DEFAULT", BranchKind.SEQUENTIAL)),
            List.of(policy("IWS", PostingMode.AUTOMATIC)));
        assertFalse(result.valid());
        assertTrue(hasError(result, "activePlanLimit"));
        assertTrue(hasError(result, "QUEUE fairnessWindowSize"));
        assertTrue(hasError(result, "监督事件只能是 DECISION/LOCAL"));
    }

    private DefinitionValidationVo validate(PlanDefinition definition, List<ProcessState> states,
                                            List<ProcessEventDefinition> events, List<ProcessEdge> edges,
                                            List<ProcessSystemPolicy> policies) {
        return validator.validate(definition, states, events, edges, policies);
    }

    private static boolean hasError(DefinitionValidationVo result, String fragment) {
        return result.errors().stream().anyMatch(item -> item.contains(fragment));
    }

    private static PlanDefinition baseDefinition() {
        PlanDefinition definition = new PlanDefinition();
        definition.setId(1L);
        definition.setPlanCode("UNIT_PLAN");
        definition.setPlanName("单元计划");
        definition.setPlanType("UNIT");
        definition.setPlanRole(PlanRole.BUSINESS);
        definition.setSelfSystemCode("IWS");
        definition.setCompletionPolicy(CompletionPolicy.ALL_SELECTED);
        definition.setExceptionStateCode("S_EXC");
        definition.setRevision(1L);
        return definition;
    }

    private static PlanDefinition supervisorDefinition() {
        PlanDefinition definition = baseDefinition();
        definition.setPlanRole(PlanRole.SUPERVISOR);
        definition.setMode(PlanMode.QUEUE);
        definition.setActivePlanLimit(2);
        definition.setFairnessWindowSize(5);
        definition.setRoundBatchLimit(10);
        return definition;
    }

    private static List<ProcessState> baseStates() {
        return List.of(state("S_START", StateKind.START, false, 0), state("S_1", StateKind.NORMAL, false, 1),
            state("S_2", StateKind.NORMAL, false, 2), state("S_3", StateKind.NORMAL, false, 3),
            state("S_END", StateKind.END, true, 4), state("S_EXC", StateKind.EXCEPTION, false, 5));
    }

    private static List<ProcessState> supervisorStates() {
        return List.of(state("S_START", StateKind.START, false, 0), state("S_NEXT", StateKind.NORMAL, false, 1),
            state("S_END", StateKind.END, true, 2), state("S_EXC", StateKind.EXCEPTION, false, 3));
    }

    private static List<ProcessEventDefinition> baseEvents() {
        return List.of(
            inboundEvent("E_IN", "S_START", "S_1", SkipPolicy.REQUIRE_REAL_INBOUND, null),
            outboundEvent("E_OUT", "S_1", "S_2", "MID_WMS", CompletionContract.ACCEPTANCE),
            inboundEvent("E_RESULT", "S_2", "S_3", SkipPolicy.ALLOW, "skip-result"),
            internalEvent("E_DONE", "S_3", "S_END"));
    }

    private static List<ProcessEdge> baseEdges() {
        return List.of(edge("E_IN", "E_OUT", "DEFAULT", BranchKind.SEQUENTIAL),
            edge("E_OUT", "E_RESULT", "DEFAULT", BranchKind.SEQUENTIAL),
            edge("E_RESULT", "E_DONE", "DEFAULT", BranchKind.SEQUENTIAL));
    }

    private static List<ProcessEdge> append(List<ProcessEdge> edges, ProcessEdge extra) {
        List<ProcessEdge> result = new ArrayList<>(edges);
        result.add(extra);
        return result;
    }

    private static List<ProcessSystemPolicy> basePolicies(PostingMode midMode) {
        return List.of(policy("MID_WMS", midMode), policy("IWS", PostingMode.AUTOMATIC));
    }

    private static ProcessSystemPolicy policy(String systemCode, PostingMode mode) {
        ProcessSystemPolicy policy = new ProcessSystemPolicy();
        policy.setSystemCode(systemCode);
        policy.setPostingMode(mode);
        return policy;
    }

    private static ProcessState state(String code, StateKind kind, boolean terminal, int order) {
        ProcessState state = new ProcessState();
        state.setStateCode(code);
        state.setDisplayName(code);
        state.setStateKind(kind);
        state.setValidatorKey(DefinitionCapabilityFixture.VALIDATOR);
        state.setValidatorVersion("1");
        state.setTerminal(terminal);
        state.setSortOrder(order);
        return state;
    }

    private static ProcessEventDefinition inboundEvent(String code, String source, String dest, SkipPolicy skip,
                                                       String skipContract) {
        ProcessEventDefinition event = new ProcessEventDefinition();
        event.setEventCode(code);
        event.setDisplayName(code);
        event.setEventRole(EventRole.BUSINESS);
        event.setEventDirection(EventDirection.INBOUND);
        event.setEventTypeKey("inbound-type");
        event.setEventTypeVersion("1");
        event.setCallContractKey(DefinitionCapabilityFixture.CONTRACT_RESULT);
        event.setCallContractVersion("1");
        event.setOperationKind(OperationKind.RESULT_CALLBACK);
        event.setPurpose(CallPurpose.RESULT_CALLBACK);
        event.setCompletionContract(CompletionContract.BUSINESS_COMPLETION);
        event.setEffectKey("effect-" + code);
        event.setSystemCode("MID_WMS");
        event.setTargetSystemCode("IWS");
        event.setSourceStateCode(source);
        event.setTargetStateCode(dest);
        event.setEnabled(true);
        event.setSkipPolicy(skip);
        event.setSkipContractKey(skipContract);
        event.setPriority(0);
        event.setEditPolicy(EditPolicy.EDITABLE);
        event.setGraphEditPolicy(GraphEditPolicy.EDITABLE_NEW_VERSION);
        return event;
    }

    private static ProcessEventDefinition outboundEvent(String code, String source, String dest, String system,
                                                        CompletionContract completion) {
        ProcessEventDefinition event = new ProcessEventDefinition();
        event.setEventCode(code);
        event.setDisplayName(code);
        event.setEventRole(EventRole.BUSINESS);
        event.setEventDirection(EventDirection.OUTBOUND);
        event.setEventTypeKey("outbound-type");
        event.setEventTypeVersion("1");
        event.setCallContractKey(DefinitionCapabilityFixture.CONTRACT_POST);
        event.setCallContractVersion("1");
        event.setOperationKind(OperationKind.EXTERNAL_POSTING);
        event.setPurpose(CallPurpose.EXECUTE_ACTION);
        event.setCompletionContract(completion);
        event.setEffectKey("effect-" + code);
        event.setSystemCode(system);
        event.setTargetSystemCode(system);
        event.setEndpointRef("ref-" + system.toLowerCase());
        event.setSourceStateCode(source);
        event.setTargetStateCode(dest);
        event.setEnabled(true);
        event.setPriority(0);
        event.setEditPolicy(EditPolicy.EDITABLE);
        event.setGraphEditPolicy(GraphEditPolicy.EDITABLE_NEW_VERSION);
        return event;
    }

    private static ProcessEventDefinition internalEvent(String code, String source, String dest) {
        ProcessEventDefinition event = new ProcessEventDefinition();
        event.setEventCode(code);
        event.setDisplayName(code);
        event.setEventRole(EventRole.BUSINESS);
        event.setEventDirection(EventDirection.INTERNAL);
        event.setEventTypeKey("internal-type");
        event.setEventTypeVersion("1");
        event.setLocalActionKey(DefinitionCapabilityFixture.LOCAL_ACTION);
        event.setLocalActionVersion("1");
        event.setLocalEffectKey("local-" + code);
        event.setSystemCode("IWS");
        event.setTargetSystemCode("IWS");
        event.setSourceStateCode(source);
        event.setTargetStateCode(dest);
        event.setEnabled(true);
        event.setPriority(0);
        event.setEditPolicy(EditPolicy.EDITABLE);
        event.setGraphEditPolicy(GraphEditPolicy.EDITABLE_NEW_VERSION);
        return event;
    }

    private static ProcessEdge edge(String from, String to, String key, BranchKind kind) {
        ProcessEdge edge = new ProcessEdge();
        edge.setPredecessorEventCode(from);
        edge.setSuccessorEventCode(to);
        edge.setBranchKey(key);
        edge.setBranchKind(kind);
        edge.setDefaultBranch(kind == BranchKind.XOR);
        edge.setPriority(0);
        return edge;
    }
}
