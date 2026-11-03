/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : INBOUND 同步已提供输入的冻结合同门禁；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime.immediate;

import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessState;
import com.innolux.iws.process.domain.ProcessSystemPolicy;
import com.innolux.iws.process.enums.CallPurpose;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventRole;
import com.innolux.iws.process.enums.PostingMode;
import com.innolux.iws.process.enums.StateKind;
import com.innolux.iws.process.runtime.CapabilityRegistry;
import com.innolux.iws.process.runtime.ImmediateFailureContract;
import com.innolux.iws.process.runtime.ImmediateFailureContractRegistry;
import com.innolux.iws.process.runtime.ImmediateInputEvidenceRef;
import com.innolux.iws.process.runtime.ImmediatePlanPreflight;
import com.innolux.iws.process.runtime.RuntimeDao;
import com.innolux.iws.process.runtime.StateValidator;
import com.innolux.iws.process.runtime.SingleActionOperation;
import com.innolux.iws.process.support.DefinitionGraph;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * INBOUND 不允许被静默当作“永不支持”。
 */
@Tag("dev")
class ImmediateInputContractTest {

    @Test
    void inboundRequiresPersistedEvidenceAndAllowsOneExplicitReference() {
        PlanDefinition definition = definition();
        RuntimeDao runtimeDao = runtimeDao(definition);
        CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
        when(capabilities.operation("in", "1")).thenReturn(mock(SingleActionOperation.class));
        when(capabilities.validator("v", "1")).thenReturn(mock(StateValidator.class));
        ImmediateFailureContractRegistry failures = mock(ImmediateFailureContractRegistry.class);
        when(failures.find(any())).thenReturn(new ImmediateFailureContract("CALL:in:1", true, "安全终结"));

        ImmediatePlanPreflight preflight = new ImmediatePlanPreflight(runtimeDao, capabilities, failures);
        assertTrue(preflight.validate(definition, List.of()).stream()
            .anyMatch(error -> error.contains("未提供本库持久入站证据")));

        assertFalse(preflight.validate(definition, List.of(new ImmediateInputEvidenceRef("E_IN", 10001L)))
            .stream().anyMatch(error -> error.contains("未提供本库持久入站证据")));
    }

    private RuntimeDao runtimeDao(PlanDefinition definition) {
        RuntimeDao runtimeDao = mock(RuntimeDao.class);
        when(runtimeDao.graph(definition.getId())).thenReturn(graph());
        when(runtimeDao.policies(definition.getId())).thenReturn(List.of(policy()));
        return runtimeDao;
    }

    private DefinitionGraph graph() {
        ProcessEventDefinition event = new ProcessEventDefinition();
        event.setEventCode("E_IN");
        event.setEventRole(EventRole.BUSINESS);
        event.setEventDirection(EventDirection.INBOUND);
        event.setPurpose(CallPurpose.TRIGGER_EVENT);
        event.setCallContractKey("in");
        event.setCallContractVersion("1");
        event.setSystemCode("IWS");
        event.setSourceStateCode("S_START");
        event.setTargetStateCode("S_END");
        event.setEnabled(Boolean.TRUE);
        return new DefinitionGraph(List.of(event), List.of(state("S_START", StateKind.START),
            state("S_END", StateKind.END)), List.of());
    }

    private ProcessState state(String code, StateKind kind) {
        ProcessState state = new ProcessState();
        state.setStateCode(code);
        state.setStateKind(kind);
        state.setValidatorKey("v");
        state.setValidatorVersion("1");
        return state;
    }

    private ProcessSystemPolicy policy() {
        ProcessSystemPolicy policy = new ProcessSystemPolicy();
        policy.setSystemCode("IWS");
        policy.setPostingMode(PostingMode.AUTOMATIC);
        return policy;
    }

    private PlanDefinition definition() {
        PlanDefinition definition = new PlanDefinition();
        definition.setId(1L);
        definition.setPlanCode("P_IN");
        definition.setPlanVersion(1);
        definition.setMaxExecutionMs(1000L);
        definition.setLeaseMs(1000L);
        definition.setRetryMax(0);
        return definition;
    }

}
