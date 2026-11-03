/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 完整草稿保存不得绕过代码绑定门禁；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.definition;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.api.DefinitionDraftBo;
import com.innolux.iws.process.api.DefinitionEventBo;
import com.innolux.iws.process.api.DefinitionStateBo;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.enums.CallPurpose;
import com.innolux.iws.process.enums.CompletionContract;
import com.innolux.iws.process.enums.EditPolicy;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventRole;
import com.innolux.iws.process.enums.GraphEditPolicy;
import com.innolux.iws.process.enums.OperationKind;
import com.innolux.iws.process.support.EditCapabilityResolver;
import com.innolux.iws.process.support.DefinitionCommandSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编辑门禁合同测试。
 */
class EditCapabilityResolverContractTest {

    private final EditCapabilityResolver resolver = new EditCapabilityResolver();

    @Test
    void codeFixedContractCannotBeChangedOrUnlocked() {
        ProcessEventDefinition current = codeFixedEvent();
        DefinitionEventBo changed = eventBo(current, "新名称", "other-contract",
            current.getEditPolicy().name(), current.getGraphEditPolicy().name());
        ServiceException contract = assertThrows(ServiceException.class,
            () -> resolver.checkEvent(current, changed));
        assertTrue(contract.getMessage().contains("代码绑定字段不可修改"));

        DefinitionEventBo unlocked = eventBo(current, "新名称", current.getCallContractKey(), "EDITABLE",
            GraphEditPolicy.EDITABLE_NEW_VERSION.name());
        ServiceException policy = assertThrows(ServiceException.class,
            () -> resolver.checkEvent(current, unlocked));
        assertTrue(policy.getMessage().contains("不允许通过请求解除代码锁"));
    }

    @Test
    void fullDraftCannotDeleteCodeFixedEvent() {
        ProcessEventDefinition current = codeFixedEvent();
        ServiceException failure = assertThrows(ServiceException.class,
            () -> resolver.checkChildren(List.of(), List.of(current), List.of(), List.of()));
        assertTrue(failure.getMessage().contains("不能删除"));
    }

    @Test
    void editableEventAllowsDisplayOnlyChange() {
        ProcessEventDefinition current = codeFixedEvent();
        current.setEditPolicy(EditPolicy.EDITABLE);
        current.setGraphEditPolicy(GraphEditPolicy.EDITABLE_NEW_VERSION);
        DefinitionEventBo changed = eventBo(current, "新名称", current.getCallContractKey(), "EDITABLE",
            "EDITABLE_NEW_VERSION");
        assertDoesNotThrow(() -> resolver.checkChildren(List.of(), List.of(current), List.of(), List.of(changed)));
    }

    @Test
    void planIdentityFieldsAreImmutable() {
        PlanDefinition current = new PlanDefinition();
        current.setPlanCode("PLAN_A");
        current.setPlanVersion(2);
        current.setPlanRole(com.innolux.iws.process.enums.PlanRole.BUSINESS);
        current.setPlanType("TYPE_A");
        DefinitionDraftBo changedCode = emptyDraft("PLAN_B", 2, "BUSINESS", "TYPE_A");
        assertTrue(assertThrows(ServiceException.class, () -> resolver.checkBase(current, changedCode))
            .getMessage().contains("计划编码不可修改"));
        DefinitionDraftBo changedVersion = emptyDraft("PLAN_A", 3, "BUSINESS", "TYPE_A");
        assertTrue(assertThrows(ServiceException.class, () -> resolver.checkBase(current, changedVersion))
            .getMessage().contains("业务版本号仅由复制操作分配"));
        DefinitionDraftBo changedRole = emptyDraft("PLAN_A", 2, "SUPERVISOR", "TYPE_A");
        assertTrue(assertThrows(ServiceException.class, () -> resolver.checkBase(current, changedRole))
            .getMessage().contains("计划角色为代码固定字段"));
        DefinitionDraftBo changedType = emptyDraft("PLAN_A", 2, "BUSINESS", "TYPE_B");
        assertTrue(assertThrows(ServiceException.class, () -> resolver.checkBase(current, changedType))
            .getMessage().contains("计划类型为身份字段"));
    }

    @Test
    void receiptTargetIncludesActorToAvoidCrossUserReplay() {
        assertEquals("definition:42:actor:" + DefinitionCommandSupport.actor(),
            DefinitionCommandSupport.target("definition", "42"));
    }

    private static ProcessEventDefinition codeFixedEvent() {
        ProcessEventDefinition event = new ProcessEventDefinition();
        event.setEventCode("E_FIXED");
        event.setDisplayName("固定事件");
        event.setEventRole(EventRole.BUSINESS);
        event.setEventDirection(EventDirection.OUTBOUND);
        event.setEventTypeKey("outbound-type");
        event.setEventTypeVersion("1");
        event.setCallContractKey(DefinitionCapabilityFixture.CONTRACT_POST);
        event.setCallContractVersion("1");
        event.setOperationKind(OperationKind.EXTERNAL_POSTING);
        event.setPurpose(CallPurpose.EXECUTE_ACTION);
        event.setCompletionContract(CompletionContract.ACCEPTANCE);
        event.setSystemCode("MID_WMS");
        event.setTargetSystemCode("MID_WMS");
        event.setEffectKey("effect-fixed");
        event.setEditPolicy(EditPolicy.CODE_FIXED);
        event.setGraphEditPolicy(GraphEditPolicy.CODE_FIXED);
        event.setLockedFields("callContractKey,callContractVersion");
        return event;
    }

    private static DefinitionEventBo eventBo(ProcessEventDefinition event, String displayName, String contract,
                                             String editPolicy, String graphEditPolicy) {
        return new DefinitionEventBo(event.getEventCode(), displayName, event.getEventRole().name(),
            event.getEventDirection().name(), event.getEventTypeKey(), event.getEventTypeVersion(), contract,
            event.getCallContractVersion(), event.getOperationKind().name(), null, null, event.getEffectKey(), null,
            event.getSystemCode(), event.getTargetSystemCode(), null, event.getPurpose().name(),
            event.getCompletionContract().name(), "S_START", "S_END", true, null, null, null, 0, editPolicy,
            graphEditPolicy, event.getLockedFields(), null);
    }

    private static DefinitionDraftBo emptyDraft(String planCode, Integer version, String role, String type) {
        List<DefinitionStateBo> states = null;
        List<DefinitionEventBo> events = null;
        List<com.innolux.iws.process.api.DefinitionEdgeBo> edges = null;
        List<com.innolux.iws.process.api.SystemPolicyBo> policies = null;
        Long expectedVersion = null;
        String catalogRevision = null;
        return new DefinitionDraftBo(planCode, "名称", version, type, null, null, "IWS", role, null, null, null, null,
            "ALL_SELECTED", null, null, null, null, null, null, null, null, null, null, null, null, null,
            expectedVersion, catalogRevision, states, events, edges, policies);
    }
}
