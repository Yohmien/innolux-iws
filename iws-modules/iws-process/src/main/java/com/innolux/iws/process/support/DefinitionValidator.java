/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划版本发布前结构与能力校验；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.support;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.api.DefinitionValidationVo;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessEdge;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessState;
import com.innolux.iws.process.domain.ProcessSystemPolicy;
import com.innolux.iws.process.enums.BranchKind;
import com.innolux.iws.process.enums.CallPurpose;
import com.innolux.iws.process.enums.CompletionContract;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventRole;
import com.innolux.iws.process.enums.OperationKind;
import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.PostingMode;
import com.innolux.iws.process.enums.SkipPolicy;
import com.innolux.iws.process.enums.StateKind;
import com.innolux.iws.process.runtime.CallContractDescriptor;
import com.innolux.iws.process.runtime.CapabilityRegistry;
import com.innolux.iws.process.runtime.LocalActionDescriptor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 有序发布校验。只读取当前草稿和图结构，不发送 HTTP、不执行业务动作。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class DefinitionValidator {

    private final CapabilityRegistry capabilityRegistry;

    private final DefinitionGraphBuilder graphBuilder;

    /**
     * 构造校验器。
     *
     * @param capabilityRegistry 能力注册表
     * @param graphBuilder 图构建器
     */
    public DefinitionValidator(CapabilityRegistry capabilityRegistry, DefinitionGraphBuilder graphBuilder) {
        this.capabilityRegistry = capabilityRegistry;
        this.graphBuilder = graphBuilder;
    }

    /**
     * 校验完整计划定义。
     *
     * @param definition 定义根
     * @param states 状态
     * @param events 事件
     * @param edges 边
     * @param policies 系统模式
     * @return 校验结果
     */
    public DefinitionValidationVo validate(PlanDefinition definition, List<ProcessState> states,
                                           List<ProcessEventDefinition> events, List<ProcessEdge> edges,
                                           List<ProcessSystemPolicy> policies) {
        return doValidate(definition, states, events, edges, policies, true);
    }

    /**
     * 校验草稿当前结构引用，不要求图已完整。
     *
     * <p>空草稿可以逐状态、逐事件建立；START/END、可达性、孤立节点和完整系统覆盖只在发布校验时要求。</p>
     *
     * @param definition 定义根
     * @param states 状态
     * @param events 事件
     * @param edges 边
     * @param policies 系统模式
     * @return 校验结果
     */
    public DefinitionValidationVo validateStructure(PlanDefinition definition, List<ProcessState> states,
                                                    List<ProcessEventDefinition> events, List<ProcessEdge> edges,
                                                    List<ProcessSystemPolicy> policies) {
        return doValidate(definition, states, events, edges, policies, false);
    }

    /**
     * 读取注册合同声明的可信入站来源候选。
     *
     * @param contractKey 合同键
     * @param contractVersion 合同版本
     * @return 来源系统编码
     */
    public List<String> callerCandidates(String contractKey, String contractVersion) {
        return capabilityRegistry.callContract(contractKey, contractVersion).callerCandidates();
    }

    private DefinitionValidationVo doValidate(PlanDefinition definition, List<ProcessState> states,
                                              List<ProcessEventDefinition> events, List<ProcessEdge> edges,
                                              List<ProcessSystemPolicy> policies, boolean publication) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        validateHeader(definition, errors, warnings, publication);
        Map<String, ProcessState> stateMap = validateStates(states, errors, warnings, publication);
        Map<String, PostingMode> policyMap = validatePolicies(definition, events, policies, errors, publication);
        Map<String, ProcessEventDefinition> eventMap = new LinkedHashMap<>();
        for (ProcessEventDefinition event : events) {
            if (event.getEventCode() == null || event.getEventCode().isBlank()) {
                errors.add("事件编码不能为空");
                continue;
            }
            if (eventMap.putIfAbsent(event.getEventCode(), event) != null) {
                errors.add("事件编码重复: " + event.getEventCode());
            }
            validateEvent(definition, event, stateMap, errors);
        }
        validateEventReferences(eventMap, errors);
        validateEdges(edges, eventMap, errors);
        if (publication) {
            DefinitionGraph graph = graphBuilder.build(events, states, edges);
            validateGraph(definition, graph, stateMap, eventMap, errors);
        }
        errors.addAll(DefinitionPostingRules.validateBypassContracts(definition, events, edges, policyMap));
        if (publication && definition.getPlanRole() == PlanRole.SUPERVISOR) {
            validateSupervisor(definition, events, policies, eventMap, errors);
        }
        return new DefinitionValidationVo(errors.isEmpty(), ProcessText.id(definition.getId()),
            ProcessText.id(definition.getRevision()), List.copyOf(errors), List.copyOf(warnings));
    }

    private void validateHeader(PlanDefinition definition, List<String> errors, List<String> warnings,
                                boolean publication) {
        if (definition.getPlanCode() == null || definition.getPlanCode().isBlank()) {
            errors.add("计划编码不能为空");
        }
        if (definition.getPlanName() == null || definition.getPlanName().isBlank()) {
            errors.add("计划名称不能为空");
        }
        if (definition.getPlanType() == null || definition.getPlanType().isBlank()) {
            errors.add("计划类型不能为空");
        }
        if (definition.getSelfSystemCode() == null || definition.getSelfSystemCode().isBlank()) {
            errors.add("本系统编码不能为空");
        }
        if (definition.getPlanRole() == null) {
            errors.add("计划角色不能为空");
            return;
        }
        if (definition.getCompletionPolicy() == null) {
            errors.add("完成策略不能为空");
        }
        if (definition.getPlanRole() == PlanRole.BUSINESS) {
            if (definition.getMode() != null) {
                errors.add("BUSINESS 计划不得配置监督调度模式");
            }
            if (definition.getActivePlanLimit() != null || definition.getFairnessWindowSize() != null) {
                errors.add("BUSINESS 计划不得配置监督名额或公平窗口");
            }
            if (definition.getMaxExecutionMs() != null && definition.getMaxExecutionMs() <= 0) {
                errors.add("执行与恢复预算 maxExecutionMs 必须为正数");
            }
        } else if (publication) {
            if (definition.getMode() == null) {
                errors.add("监督计划必须配置 QUEUE/IMMEDIATE 模式");
            }
            positive(definition.getActivePlanLimit(), "activePlanLimit", errors);
            if (definition.getMode() == PlanMode.QUEUE) {
                positive(definition.getFairnessWindowSize(), "QUEUE fairnessWindowSize", errors);
            }
            if (definition.getMode() == PlanMode.IMMEDIATE) {
                if (definition.getActivePlanLimit() != null && definition.getActivePlanLimit() != 1) {
                    errors.add("IMMEDIATE activePlanLimit 必须为 1");
                }
                if (definition.getMaxExecutionMs() == null || definition.getMaxExecutionMs() <= 0) {
                    errors.add("IMMEDIATE 必须配置正数 maxExecutionMs");
                }
            }
            if (definition.getJobMaxRetryTimes() != null && definition.getJobMaxRetryTimes() > 0) {
                warnings.add("监督平台任务自动重试应为 0，避免外呼被平台重演");
            }
        }
        positiveIfPresent(definition.getRoundBatchLimit(), "roundBatchLimit", errors);
        positiveIfPresent(definition.getWorkerBatchLimit(), "workerBatchLimit", errors);
        nonNegative(definition.getRetryMax(), "retryMax", errors);
        nonNegative(definition.getJobMaxRetryTimes(), "jobMaxRetryTimes", errors);
        if (definition.getRoundMaxDurationMs() != null && definition.getRoundMaxDurationMs() <= 0) {
            errors.add("roundMaxDurationMs 必须为正数");
        }
        if (definition.getWorkerMaxDurationMs() != null && definition.getWorkerMaxDurationMs() <= 0) {
            errors.add("workerMaxDurationMs 必须为正数");
        }
        if (definition.getLeaseMs() != null && definition.getLeaseMs() <= 0) {
            errors.add("leaseMs 必须为正数");
        }
        if (definition.getScheduleIntervalMs() != null && definition.getScheduleIntervalMs() <= 0) {
            errors.add("scheduleIntervalMs 必须为正数");
        }
    }

    private Map<String, ProcessState> validateStates(List<ProcessState> states, List<String> errors,
                                                     List<String> warnings, boolean publication) {
        Map<String, ProcessState> stateMap = new HashMap<>();
        for (ProcessState state : states) {
            if (state.getStateCode() == null || state.getStateCode().isBlank()) {
                errors.add("状态编码不能为空");
                continue;
            }
            if (stateMap.putIfAbsent(state.getStateCode(), state) != null) {
                errors.add("状态编码重复: " + state.getStateCode());
            }
            if (state.getDisplayName() == null || state.getDisplayName().isBlank()) {
                warnings.add("状态缺少中文名: " + state.getStateCode());
            }
            if (state.getStateKind() == null) {
                errors.add("状态类别不能为空: " + state.getStateCode());
                continue;
            }
            boolean terminal = Boolean.TRUE.equals(state.getTerminal());
            if (state.getStateKind() == StateKind.END && !terminal) {
                errors.add("终点状态必须标记 terminal: " + state.getStateCode());
            }
            if (state.getStateKind() != StateKind.END && terminal) {
                errors.add("非终点状态不能标记 terminal: " + state.getStateCode());
            }
            if (state.getValidatorKey() == null || state.getValidatorKey().isBlank()
                || state.getValidatorVersion() == null || state.getValidatorVersion().isBlank()) {
                errors.add("状态缺少完整校验器键/版本: " + state.getStateCode());
            } else {
                try {
                    capabilityRegistry.validator(state.getValidatorKey(), state.getValidatorVersion());
                } catch (ServiceException ex) {
                    errors.add(ex.getMessage());
                }
            }
        }
        long startCount = stateMap.values().stream().filter(item -> item.getStateKind() == StateKind.START).count();
        long endCount = stateMap.values().stream().filter(item -> item.getStateKind() == StateKind.END).count();
        if (publication && startCount == 0) {
            errors.add("至少需要一个起点状态");
        }
        if (publication && endCount == 0) {
            errors.add("至少需要一个终点状态");
        }
        return stateMap;
    }

    private Map<String, PostingMode> validatePolicies(PlanDefinition definition,
                                                      List<ProcessEventDefinition> events,
                                                      List<ProcessSystemPolicy> policies,
                                                      List<String> errors, boolean publication) {
        Map<String, PostingMode> result = new LinkedHashMap<>();
        for (ProcessSystemPolicy policy : policies) {
            if (policy.getSystemCode() == null || policy.getSystemCode().isBlank()) {
                errors.add("系统模式缺少系统编码");
                continue;
            }
            if (policy.getPostingMode() == null) {
                errors.add("系统模式缺失: " + policy.getSystemCode());
                continue;
            }
            if (result.putIfAbsent(policy.getSystemCode(), policy.getPostingMode()) != null) {
                errors.add("同一系统存在多个模式: " + policy.getSystemCode());
            }
            if (policy.getFirstConfirmPolicy() != null && policy.getFirstConfirmPolicy().length() > 32) {
                errors.add("首次确认策略过长: " + policy.getSystemCode());
            }
            if (policy.getFailurePolicy() != null && policy.getFailurePolicy().length() > 32) {
                errors.add("失败策略过长: " + policy.getSystemCode());
            }
        }
        if (publication) {
            for (ProcessEventDefinition event : events) {
                if (event.getSystemCode() != null && !event.getSystemCode().isBlank()
                    && !result.containsKey(event.getSystemCode())) {
                    errors.add("事件[" + event.getEventCode() + "]缺少系统模式: " + event.getSystemCode());
                }
            }
            Set<String> declared = new HashSet<>(DefinitionPostingRules.policySystems(events));
            for (String code : result.keySet()) {
                if (!declared.contains(code)) {
                    errors.add("系统模式没有对应事件归属: " + code);
                }
            }
        }
        if (definition.getPlanRole() == PlanRole.SUPERVISOR) {
            for (Map.Entry<String, PostingMode> entry : result.entrySet()) {
                if (entry.getValue() != PostingMode.AUTOMATIC) {
                    errors.add("监督计划只能使用本系统 AUTOMATIC 控制策略: " + entry.getKey());
                }
            }
        }
        return result;
    }

    private void validateEvent(PlanDefinition definition, ProcessEventDefinition event,
                               Map<String, ProcessState> states, List<String> errors) {
        String prefix = "事件[" + event.getEventCode() + "]";
        if (event.getDisplayName() == null || event.getDisplayName().isBlank()) {
            errors.add(prefix + "缺少中文名");
        }
        if (event.getEventRole() == null) {
            errors.add(prefix + "角色不能为空");
        }
        if (event.getEventDirection() == null) {
            errors.add(prefix + "方向不能为空");
            return;
        }
        if (definition.getPlanRole() == PlanRole.BUSINESS && event.getEventRole() != null
            && event.getEventRole() != EventRole.BUSINESS) {
            errors.add(prefix + "业务计划只能使用 BUSINESS 角色");
        }
        ProcessState source = states.get(event.getSourceStateCode());
        ProcessState target = states.get(event.getTargetStateCode());
        if (source == null) {
            errors.add(prefix + "源状态不存在: " + event.getSourceStateCode());
        } else if (source.getStateKind() == StateKind.END) {
            errors.add(prefix + "不能从终点状态出发");
        }
        if (target == null) {
            errors.add(prefix + "目标状态不存在: " + event.getTargetStateCode());
        } else if (target.getStateKind() == StateKind.START) {
            errors.add(prefix + "不能回到起点状态");
        }
        if (event.getSystemCode() == null || event.getSystemCode().isBlank()) {
            errors.add(prefix + "系统编码不能为空");
        }
        validateEventType(event, prefix, errors);
        if (event.getEventDirection() == EventDirection.INTERNAL) {
            validateInternalEvent(definition, event, prefix, errors);
        } else {
            validateHttpEvent(definition, event, prefix, errors);
        }
        validateSkipContract(event, prefix, errors);
        if (definition.getPlanRole() == PlanRole.SUPERVISOR) {
            if (event.getEventRole() != EventRole.DECISION && event.getEventRole() != EventRole.LOCAL) {
                errors.add(prefix + "监督事件只能是 DECISION/LOCAL");
            }
            if (event.getEventDirection() != EventDirection.INTERNAL) {
                errors.add(prefix + "监督事件只能是 INTERNAL");
            }
            if (event.getSystemCode() != null
                && !event.getSystemCode().equals(definition.getSelfSystemCode())) {
                errors.add(prefix + "监督事件只能归属本系统");
            }
            if (event.getRecoveryForEventCode() != null) {
                errors.add(prefix + "监督事件不能声明恢复查询");
            }
        }
    }

    private void validateEventType(ProcessEventDefinition event, String prefix, List<String> errors) {
        if (event.getEventTypeKey() == null || event.getEventTypeKey().isBlank()
            || event.getEventTypeVersion() == null || event.getEventTypeVersion().isBlank()) {
            errors.add(prefix + "事件类型键/版本不能为空");
            return;
        }
        try {
            var descriptor = capabilityRegistry.eventType(event.getEventTypeKey(), event.getEventTypeVersion());
            if (!descriptor.supportedDirections().contains(event.getEventDirection())) {
                errors.add(prefix + "事件类型不支持方向: " + event.getEventDirection());
            }
        } catch (ServiceException ex) {
            errors.add(prefix + ex.getMessage());
        }
    }

    private void validateInternalEvent(PlanDefinition definition, ProcessEventDefinition event, String prefix,
                                       List<String> errors) {
        if (event.getCallContractKey() != null || event.getCallContractVersion() != null) {
            errors.add(prefix + "INTERNAL 不得绑定调用合同");
        }
        if (event.getEffectKey() != null) {
            errors.add(prefix + "INTERNAL 不得绑定 HTTP 效果键");
        }
        if (event.getTargetSystemCode() != null
            && !event.getTargetSystemCode().equals(definition.getSelfSystemCode())) {
            errors.add(prefix + "INTERNAL 目标只能是本系统");
        }
        if (event.getEndpointRef() != null && !event.getEndpointRef().isBlank()) {
            errors.add(prefix + "INTERNAL 不得配置网络地址");
        }
        if (event.getPurpose() != null) {
            errors.add(prefix + "INTERNAL 不得配置 HTTP 调用用途");
        }
        if (event.getOperationKind() != null) {
            errors.add(prefix + "INTERNAL 不得配置 HTTP 动作类型");
        }
        if (event.getCompletionContract() != null) {
            errors.add(prefix + "INTERNAL 不得配置 HTTP 完成合同");
        }
        if (event.getLocalActionKey() == null || event.getLocalActionKey().isBlank()
            || event.getLocalActionVersion() == null || event.getLocalActionVersion().isBlank()) {
            errors.add(prefix + "INTERNAL 必须绑定唯一本地动作");
            return;
        }
        if (event.getLocalEffectKey() == null || event.getLocalEffectKey().isBlank()) {
            errors.add(prefix + "INTERNAL 必须配置 localEffectKey");
        }
        try {
            LocalActionDescriptor descriptor = capabilityRegistry.localActionDescriptor(
                event.getLocalActionKey(), event.getLocalActionVersion());
            if (!descriptor.checkpointed()) {
                errors.add(prefix + "本地动作必须声明可核对检查点");
            }
            if (!"SAME_TX".equals(descriptor.transactionMode())) {
                errors.add(prefix + "本地动作当前必须声明 SAME_TX，EVIDENCE_ONLY 需先提供可靠效果读取协议");
            }
        } catch (ServiceException ex) {
            errors.add(prefix + ex.getMessage());
        }
    }

    private void validateHttpEvent(PlanDefinition definition, ProcessEventDefinition event, String prefix,
                                   List<String> errors) {
        if (event.getLocalActionKey() != null || event.getLocalActionVersion() != null
            || event.getLocalEffectKey() != null) {
            errors.add(prefix + "INBOUND/OUTBOUND 不得绑定本地动作");
        }
        if (event.getCallContractKey() == null || event.getCallContractKey().isBlank()
            || event.getCallContractVersion() == null || event.getCallContractVersion().isBlank()) {
            errors.add(prefix + "必须绑定唯一调用合同");
            return;
        }
        try {
            CallContractDescriptor contract = capabilityRegistry.callContract(
                event.getCallContractKey(), event.getCallContractVersion());
            if (contract.direction() != event.getEventDirection()) {
                errors.add(prefix + "调用合同方向不兼容");
            }
            if (event.getCompletionContract() == null) {
                errors.add(prefix + "完成合同不能为空");
            } else if (event.getCompletionContract() != contract.completion()) {
                errors.add(prefix + "完成合同与能力不一致");
            }
            if (event.getOperationKind() == null) {
                errors.add(prefix + "动作类型不能为空");
            } else if (event.getOperationKind() != contract.operationKind()) {
                errors.add(prefix + "动作类型与调用合同不一致");
            }
            if (event.getPurpose() == null) {
                errors.add(prefix + "调用用途不能为空");
            } else if (event.getPurpose() != contract.purpose()) {
                errors.add(prefix + "调用用途与调用合同不一致");
            }
            if (event.getEventDirection() == EventDirection.OUTBOUND) {
                if (event.getEndpointRef() == null || event.getEndpointRef().isBlank()) {
                    errors.add(prefix + "出站事件必须配置 endpointRef");
                }
                if (event.getTargetSystemCode() == null || event.getTargetSystemCode().isBlank()) {
                    errors.add(prefix + "出站事件必须配置目标系统");
                } else if (!event.getTargetSystemCode().equals(event.getSystemCode())) {
                    errors.add(prefix + "出站政策系统必须等于目标系统，避免屏蔽错系统");
                }
                if (event.getOperationKind() == OperationKind.EXTERNAL_POSTING
                    && event.getTargetSystemCode() != null
                    && event.getTargetSystemCode().equals(definition.getSelfSystemCode())) {
                    errors.add(prefix + "外部抛账目标不能是本系统");
                }
            } else {
                if (event.getEndpointRef() != null && !event.getEndpointRef().isBlank()) {
                    errors.add(prefix + "入站事件不得配置出站地址引用");
                }
                if (event.getTargetSystemCode() != null
                    && !event.getTargetSystemCode().equals(definition.getSelfSystemCode())) {
                    errors.add(prefix + "入站事件目标应为本系统");
                }
                if (event.getOperationKind() == OperationKind.EXTERNAL_POSTING) {
                    errors.add(prefix + "入站事件不能声明外部抛账动作");
                }
            }
        } catch (ServiceException ex) {
            errors.add(prefix + ex.getMessage());
        }
        if (event.getEffectKey() == null || event.getEffectKey().isBlank()) {
            errors.add(prefix + "效果键不能为空");
        }
    }

    private void validateSkipContract(ProcessEventDefinition event, String prefix, List<String> errors) {
        boolean skipDeclared = event.getSkipPolicy() == SkipPolicy.ALLOW;
        if (skipDeclared && (event.getSkipContractKey() == null || event.getSkipContractKey().isBlank())) {
            errors.add(prefix + "跳过策略缺少跳过合同");
        }
        if (Boolean.FALSE.equals(event.getEnabled())) {
            if (event.getSkipPolicy() != SkipPolicy.ALLOW) {
                errors.add(prefix + "整事件禁用必须使用 ALLOW 跳过策略");
            }
            if (event.getSkipContractKey() == null || event.getSkipContractKey().isBlank()) {
                errors.add(prefix + "整事件禁用必须声明跳过合同");
            }
        }
        if (event.getSkipPolicy() == SkipPolicy.REQUIRE_REAL_INBOUND
            && event.getEventDirection() != EventDirection.INBOUND) {
            errors.add(prefix + "仅入站事件可使用 REQUIRE_REAL_INBOUND");
        }
    }

    private void validateEventReferences(Map<String, ProcessEventDefinition> events, List<String> errors) {
        for (ProcessEventDefinition event : events.values()) {
            if (event.getRecoveryForEventCode() == null || event.getRecoveryForEventCode().isBlank()) {
                continue;
            }
            ProcessEventDefinition origin = events.get(event.getRecoveryForEventCode());
            if (origin == null) {
                errors.add("事件[" + event.getEventCode() + "]恢复来源不存在: " + event.getRecoveryForEventCode());
                continue;
            }
            if (origin == event) {
                errors.add("事件[" + event.getEventCode() + "]不能以自身作为恢复来源");
            }
            if (event.getEventDirection() != EventDirection.OUTBOUND
                || event.getPurpose() != CallPurpose.QUERY
                || event.getOperationKind() != OperationKind.QUERY) {
                errors.add("事件[" + event.getEventCode() + "]恢复分支必须是 OUTBOUND QUERY");
            }
            if (origin.getEventDirection() != EventDirection.OUTBOUND) {
                errors.add("事件[" + event.getEventCode() + "]恢复来源必须是出站事件");
            }
            if (Boolean.FALSE.equals(origin.getEnabled())) {
                errors.add("事件[" + event.getEventCode() + "]不能恢复整事件禁用的事件");
            }
        }
    }

    private void validateEdges(List<ProcessEdge> edges, Map<String, ProcessEventDefinition> events,
                               List<String> errors) {
        Set<String> unique = new HashSet<>();
        Map<String, List<ProcessEdge>> outgoing = new LinkedHashMap<>();
        for (ProcessEdge edge : edges) {
            ProcessEventDefinition before = events.get(edge.getPredecessorEventCode());
            ProcessEventDefinition after = events.get(edge.getSuccessorEventCode());
            String key = edge.getPredecessorEventCode() + "|" + edge.getSuccessorEventCode() + "|"
                + edge.getBranchKey();
            if (!unique.add(key)) {
                errors.add("重复边: " + key);
            }
            if (before == null || after == null) {
                errors.add("边引用不存在的事件: " + edge.getPredecessorEventCode() + " → "
                    + edge.getSuccessorEventCode());
                continue;
            }
            if (edge.getPredecessorEventCode().equals(edge.getSuccessorEventCode())) {
                errors.add("边不能指向自身: " + key);
            }
            if (!before.getTargetStateCode().equals(after.getSourceStateCode())) {
                errors.add("边状态不连续: " + key);
            }
            BranchKind kind = edge.getBranchKind() == null ? BranchKind.SEQUENTIAL : edge.getBranchKind();
            if (kind == BranchKind.XOR) {
                boolean defaultBranch = Boolean.TRUE.equals(edge.getDefaultBranch());
                boolean hasCondition = edge.getConditionKey() != null && !edge.getConditionKey().isBlank();
                if (!hasCondition && !defaultBranch) {
                    errors.add("XOR 边必须配置条件或声明默认分支: " + key);
                }
                if (hasCondition && defaultBranch) {
                    errors.add("XOR 默认分支不能同时配置条件: " + key);
                }
                if (hasCondition && (edge.getConditionVersion() == null
                    || edge.getConditionVersion().isBlank())) {
                    errors.add("XOR 条件缺少版本: " + key);
                }
            } else {
                if (edge.getConditionKey() != null) {
                    errors.add("非 XOR 边不得配置条件: " + key);
                }
                if (Boolean.TRUE.equals(edge.getDefaultBranch())) {
                    errors.add("默认分支只能用于 XOR 边: " + key);
                }
            }
            if (kind == BranchKind.AND
                && (edge.getJoinCode() == null || edge.getJoinCode().isBlank())) {
                errors.add("AND 边必须配置 joinCode: " + key);
            }
            if (kind != BranchKind.AND && edge.getJoinCode() != null && !edge.getJoinCode().isBlank()) {
                errors.add("仅 AND 边可配置 joinCode: " + key);
            }
            if (edge.getConditionKey() != null && !edge.getConditionKey().isBlank()) {
                try {
                    capabilityRegistry.condition(edge.getConditionKey(), edge.getConditionVersion());
                } catch (ServiceException ex) {
                    errors.add(ex.getMessage());
                }
            }
            outgoing.computeIfAbsent(edge.getPredecessorEventCode(), item -> new ArrayList<>()).add(edge);
        }
        for (Map.Entry<String, List<ProcessEdge>> entry : outgoing.entrySet()) {
            validateSiblings(entry.getKey(), entry.getValue(), errors);
        }
    }

    private void validateSiblings(String predecessor, List<ProcessEdge> edges, List<String> errors) {
        if (edges.size() <= 1) {
            ProcessEdge single = edges.get(0);
            if (single.getBranchKind() == BranchKind.XOR || single.getBranchKind() == BranchKind.AND) {
                errors.add("分支节点至少需要两条出边: " + predecessor);
            }
            return;
        }
        Set<BranchKind> kinds = new HashSet<>();
        for (ProcessEdge edge : edges) {
            kinds.add(edge.getBranchKind() == null ? BranchKind.SEQUENTIAL : edge.getBranchKind());
        }
        if (kinds.size() > 1) {
            errors.add("同一事件的出边必须使用同一种分支类型: " + predecessor);
        }
        if (kinds.contains(BranchKind.SEQUENTIAL)) {
            errors.add("顺序事件不能有多条出边，请使用 XOR/AND: " + predecessor);
        }
        if (kinds.contains(BranchKind.XOR)) {
            long defaults = edges.stream().filter(edge -> Boolean.TRUE.equals(edge.getDefaultBranch())).count();
            if (defaults > 1) {
                errors.add("XOR 节点最多一个默认分支: " + predecessor);
            }
            Set<String> conditions = new HashSet<>();
            Set<String> priorities = new HashSet<>();
            for (ProcessEdge edge : edges) {
                if (edge.getConditionKey() != null
                    && !conditions.add(edge.getConditionKey() + "@" + edge.getConditionVersion())) {
                    errors.add("XOR 兄弟边条件重复: " + predecessor + " / " + edge.getConditionKey());
                }
                if (edge.getPriority() != null
                    && !priorities.add(String.valueOf(edge.getPriority()))) {
                    errors.add("XOR 兄弟边优先级重复: " + predecessor + " / " + edge.getPriority());
                }
            }
        }
        if (kinds.contains(BranchKind.AND)) {
            Set<String> joins = new HashSet<>();
            for (ProcessEdge edge : edges) {
                joins.add(edge.getJoinCode());
            }
            if (joins.size() != 1) {
                errors.add("同一 AND fork 必须共享一个 joinCode: " + predecessor);
            }
        }
    }

    private void validateGraph(PlanDefinition definition, DefinitionGraph graph, Map<String, ProcessState> states,
                               Map<String, ProcessEventDefinition> events, List<String> errors) {
        if (events.isEmpty()) {
            errors.add("计划至少需要一个事件");
        }
        if (!events.isEmpty() && graph.topologicalOrder().isEmpty()) {
            errors.add("计划图存在环");
        }
        if (definition.getExceptionStateCode() != null && !definition.getExceptionStateCode().isBlank()) {
            ProcessState exception = states.get(definition.getExceptionStateCode());
            if (exception == null) {
                errors.add("异常状态不存在: " + definition.getExceptionStateCode());
            } else if (exception.getStateKind() != StateKind.EXCEPTION) {
                errors.add("异常状态类别必须为 EXCEPTION: " + definition.getExceptionStateCode());
            }
        }
        if (graph.roots().isEmpty()) {
            return;
        }
        for (String root : graph.roots()) {
            ProcessEventDefinition event = events.get(root);
            ProcessState source = event == null ? null : states.get(event.getSourceStateCode());
            if (source == null || source.getStateKind() != StateKind.START) {
                errors.add("根事件必须从起点状态出发: " + root);
            }
        }
        for (Map.Entry<String, ProcessEventDefinition> entry : events.entrySet()) {
            ProcessEventDefinition event = entry.getValue();
            if (graph.outgoing(entry.getKey()).isEmpty()) {
                ProcessState target = states.get(event.getTargetStateCode());
                if (target == null || target.getStateKind() != StateKind.END) {
                    errors.add("叶子事件必须收敛到终点状态: " + entry.getKey());
                }
            }
        }
        Set<String> reachable = graph.reachableFrom(graph.roots());
        for (String code : graph.eventCodes()) {
            if (!reachable.contains(code)) {
                errors.add("事件从起点不可达: " + code);
            }
        }
    }

    private void validateSupervisor(PlanDefinition definition, List<ProcessEventDefinition> events,
                                    List<ProcessSystemPolicy> policies,
                                    Map<String, ProcessEventDefinition> eventMap, List<String> errors) {
        String self = definition.getSelfSystemCode();
        if (self != null && !self.isBlank()) {
            long selfPolicies = policies.stream().filter(item -> self.equals(item.getSystemCode())).count();
            if (selfPolicies != 1) {
                errors.add("监督计划必须且只能配置本系统一条控制策略");
            }
        }
        for (ProcessEventDefinition event : events) {
            if (event.getEventDirection() != EventDirection.INTERNAL) {
                errors.add("监督事件不是 INTERNAL: " + event.getEventCode());
            }
            if (event.getEventRole() != EventRole.DECISION && event.getEventRole() != EventRole.LOCAL) {
                errors.add("监督事件不是 DECISION/LOCAL: " + event.getEventCode());
            }
            if (event.getCompletionContract() != null || event.getEffectKey() != null
                || event.getCallContractKey() != null) {
                errors.add("监督事件不得携带业务 HTTP 合同: " + event.getEventCode());
            }
        }
        if (eventMap.isEmpty()) {
            errors.add("监督计划至少需要一个调度事件");
        }
    }

    private static void positive(Integer value, String field, List<String> errors) {
        if (value == null || value <= 0) {
            errors.add(field + " 必须为正整数");
        }
    }

    private static void positiveIfPresent(Integer value, String field, List<String> errors) {
        if (value != null && value <= 0) {
            errors.add(field + " 必须为正整数");
        }
    }

    private static void nonNegative(Integer value, String field, List<String> errors) {
        if (value != null && value < 0) {
            errors.add(field + " 不能为负数");
        }
    }
}
