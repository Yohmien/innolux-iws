/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 即时计划冻结图、输入边界与安全失败门禁；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessEdge;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessState;
import com.innolux.iws.process.domain.ProcessSystemPolicy;
import com.innolux.iws.process.enums.CallPurpose;
import com.innolux.iws.process.enums.CompletionContract;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventRole;
import com.innolux.iws.process.enums.PostingMode;
import com.innolux.iws.process.support.DefinitionGraph;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在准入事务内验证即时图只包含可同步完成的有界路径。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled",
    havingValue = "true")
public class ImmediatePlanPreflight {

    private final RuntimeDao runtimeDao;

    private final CapabilityRegistry capabilityRegistry;

    private final ImmediateFailureContractRegistry failureContracts;

    /**
     * 构造门禁。
     *
     * @param runtimeDao 运行时 DAO
     * @param capabilityRegistry 能力注册表
     * @param failureContracts 安全失败合同
     */
    public ImmediatePlanPreflight(RuntimeDao runtimeDao, CapabilityRegistry capabilityRegistry,
                                  ImmediateFailureContractRegistry failureContracts) {
        this.runtimeDao = runtimeDao;
        this.capabilityRegistry = capabilityRegistry;
        this.failureContracts = failureContracts;
    }

    /**
     * 校验冻结即时图。
     *
     * @param definition BUSINESS 定义
     * @param evidenceRefs 本库证据引用
     * @return 错误集合，空表示通过
     */
    public List<String> validate(PlanDefinition definition, List<ImmediateInputEvidenceRef> evidenceRefs) {
        List<String> errors = new ArrayList<>();
        if (definition.getMaxExecutionMs() == null || definition.getMaxExecutionMs() <= 0) {
            errors.add("即时业务定义缺少 maxExecutionMs");
        }
        if (definition.getLeaseMs() == null || definition.getLeaseMs() <= 0) {
            errors.add("即时业务定义缺少 leaseMs");
        }
        if (definition.getRetryMax() != null && definition.getRetryMax() > 0) {
            errors.add("即时业务定义禁止 retryMax>0");
        }
        DefinitionGraph graph = runtimeDao.graph(definition.getId());
        if (graph.roots().isEmpty()) {
            errors.add("即时业务定义没有可执行起点");
            return errors;
        }
        Set<String> systems = new HashSet<>();
        Map<String, Integer> evidenceByEvent = new HashMap<>();
        Set<Long> evidenceIds = new HashSet<>();
        for (ImmediateInputEvidenceRef ref : evidenceRefs) {
            if (ref == null || ref.eventCode() == null || ref.eventCode().isBlank() || ref.receiptId() <= 0) {
                errors.add("入站证据引用无效");
                continue;
            }
            evidenceByEvent.merge(ref.eventCode(), 1, Integer::sum);
            if (!evidenceIds.add(ref.receiptId())) {
                errors.add(ref.eventCode() + " 复用同一入站回执: " + ref.receiptId());
            }
        }
        Set<String> reachable = graph.reachableFrom(graph.roots());
        for (String eventCode : graph.reachableFrom(graph.roots())) {
            ProcessEventDefinition event = graph.event(eventCode);
            if (event.getEventRole() != EventRole.BUSINESS) {
                errors.add(eventCode + " 不是 BUSINESS 事件");
            }
            if (event.getEventDirection() == EventDirection.INBOUND) {
                if (event.getPurpose() == CallPurpose.RESULT_CALLBACK) {
                    errors.add(eventCode + " 为未来结果回调，即时同步 DAG 不允许等待");
                } else {
                    int evidenceCount = evidenceByEvent.getOrDefault(eventCode, 0);
                    if (evidenceCount == 0) {
                        errors.add(eventCode + " 为入站事件，但未提供本库持久入站证据");
                    } else if (evidenceCount > 1) {
                        errors.add(eventCode + " 入站证据不唯一");
                    }
                }
            }
            if (event.getEventDirection() != EventDirection.INBOUND
                && event.getPurpose() == CallPurpose.RESULT_CALLBACK) {
                errors.add(eventCode + " 使用回调用途但方向不是 INBOUND");
            }
            if (event.getCompletionContract() == CompletionContract.ACCEPTANCE) {
                errors.add(eventCode + " 使用 ACCEPTANCE 异步完成合同");
            }
            if (event.getSystemCode() != null && !event.getSystemCode().isBlank()) {
                systems.add(event.getSystemCode());
            }
            if (!Boolean.FALSE.equals(event.getEnabled())) {
                verifyOperation(eventCode, event, errors);
            }
            verifyState(event.getSourceStateCode(), graph, errors);
            verifyState(event.getTargetStateCode(), graph, errors);
        }
        for (String eventCode : evidenceByEvent.keySet()) {
            if (!reachable.contains(eventCode)) {
                errors.add(eventCode + " 入站证据不属于冻结即时图");
            }
        }
        for (ProcessSystemPolicy policy : runtimeDao.policies(definition.getId())) {
            if (policy.getPostingMode() == PostingMode.MANUAL_CONFIRM && systems.contains(policy.getSystemCode())) {
                errors.add(policy.getSystemCode() + " 为人工首次确认模式");
            }
        }
        for (String system : systems) {
            boolean present = runtimeDao.policies(definition.getId()).stream()
                .anyMatch(policy -> system.equals(policy.getSystemCode()));
            if (!present) {
                errors.add(system + " 缺少冻结系统模式");
            }
        }
        for (String eventCode : graph.eventCodes()) {
            for (ProcessEdge edge : graph.outgoing(eventCode)) {
                if (edge.getConditionKey() == null || edge.getConditionKey().isBlank()) {
                    continue;
                }
                try {
                    capabilityRegistry.condition(edge.getConditionKey(), edge.getConditionVersion());
                } catch (RuntimeException ex) {
                    errors.add(eventCode + " 分支条件不可用: " + edge.getConditionKey());
                }
            }
        }
        return errors;
    }

    private void verifyOperation(String eventCode, ProcessEventDefinition event, List<String> errors) {
        String operationKey = ImmediateFailureContractRegistry.operationKey(event);
        if (operationKey == null) {
            errors.add(eventCode + " 缺少唯一精确动作能力");
            return;
        }
        try {
            if (event.getEventDirection() == EventDirection.INTERNAL) {
                capabilityRegistry.localAction(event.getLocalActionKey(), event.getLocalActionVersion());
            } else {
                capabilityRegistry.operation(event.getCallContractKey(), event.getCallContractVersion());
            }
        } catch (RuntimeException ex) {
            errors.add(eventCode + " 动作能力不可用: " + operationKey);
        }
        ImmediateFailureContract contract = failureContracts.find(event);
        if (contract == null) {
            errors.add(eventCode + " 缺少安全失败合同: " + operationKey);
        } else if (!contract.safeToFinalizeOnDeterministicFailure()) {
            errors.add(eventCode + " 安全失败合同禁止确定失败终结: " + operationKey);
        }
    }

    private void verifyState(String stateCode, DefinitionGraph graph, List<String> errors) {
        ProcessState state = graph.state(stateCode);
        if (state == null) {
            errors.add("状态不存在: " + stateCode);
            return;
        }
        if (state.getValidatorKey() == null || state.getValidatorKey().isBlank()) {
            errors.add("状态缺少校验器: " + stateCode);
            return;
        }
        try {
            capabilityRegistry.validator(state.getValidatorKey(), state.getValidatorVersion());
        } catch (RuntimeException ex) {
            errors.add("状态校验器不可用: " + stateCode);
        }
    }
}
