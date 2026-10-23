/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.2
 * @Description  : 计划事件占位、token 与系统上下文物化；
 * @ModifyRecord : V1.0 新建；V1.1 selectedRootSet 限制入口并关闭不可达占位；V1.2 预建全部 scope 并原子物化；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessEdge;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessSystemContext;
import com.innolux.iws.process.domain.ProcessSystemPolicy;
import com.innolux.iws.process.domain.ProcessToken;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.ExecutionPurpose;
import com.innolux.iws.process.enums.PostingMode;
import com.innolux.iws.process.enums.TokenRole;
import com.innolux.iws.process.enums.TokenStatus;
import com.innolux.iws.process.enums.WaitReason;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessSystemContextMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import com.innolux.iws.process.support.DefinitionGraph;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把冻结定义物化为实例内的持久占位，不执行任何业务动作。
 *
 * <p>第一版仍为每个事件和 token 预建占位，但只有从 {@code selectedRootSet} 出发可达的节点
 * 保持活动；不可达占位直接写 {@code NOT_SELECTED}，不会进入 worker 候选。</p>
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class PlanMaterializer {

    private final RuntimeDao runtimeDao;

    private final ProcessEventMapper eventMapper;

    private final ProcessTokenMapper tokenMapper;

    private final ProcessSystemContextMapper contextMapper;

    private final EventEligibilityService eligibilityService;

    /**
     * 构造物化器。
     *
     * @param runtimeDao 运行时 DAO
     * @param eventMapper 事件 Mapper
     * @param tokenMapper token Mapper
     * @param contextMapper 系统上下文 Mapper
     * @param eligibilityService 资格服务
     */
    public PlanMaterializer(RuntimeDao runtimeDao, ProcessEventMapper eventMapper,
                            ProcessTokenMapper tokenMapper, ProcessSystemContextMapper contextMapper,
                            EventEligibilityService eligibilityService) {
        this.runtimeDao = runtimeDao;
        this.eventMapper = eventMapper;
        this.tokenMapper = tokenMapper;
        this.contextMapper = contextMapper;
        this.eligibilityService = eligibilityService;
    }

    /**
     * 物化实例。
     *
     * @param instance 计划实例
     */
    @Transactional(rollbackFor = Exception.class)
    public void materialize(ProcessInstance instance) {
        DefinitionGraph graph = runtimeDao.graph(instance.getDefinitionVersionId());
        Set<String> forcedClosed = new LinkedHashSet<>();
        for (String code : graph.eventCodes()) {
            ProcessEventDefinition definition = graph.event(code);
            if (definition.getRecoveryForEventCode() != null) {
                forcedClosed.add(code);
            }
        }
        BranchActivity activity = new BranchActivity(graph, instance, List.of(), forcedClosed, 0L);
        Map<String, PostingMode> modes = new HashMap<>();
        for (ProcessSystemPolicy policy : runtimeDao.policies(instance.getDefinitionVersionId())) {
            modes.put(policy.getSystemCode(), policy.getPostingMode());
            ProcessSystemContext context = new ProcessSystemContext();
            context.setInstanceId(instance.getId());
            context.setSystemCode(policy.getSystemCode());
            context.setPostingMode(policy.getPostingMode());
            context.setFirstConfirmed(Boolean.FALSE);
            context.setRevision(1L);
            contextMapper.insert(context);
        }
        materializeScopes(instance, graph);
        for (String eventCode : graph.eventCodes()) {
            ProcessEventDefinition definition = graph.event(eventCode);
            boolean closed = activity.closed(eventCode);
            EventStatus status = initialStatus(definition, modes, activity, closed);
            WaitReason reason = initialReason(definition, status);

            ProcessToken token = new ProcessToken();
            token.setInstanceId(instance.getId());
            token.setTokenRole(TokenRole.BRANCH);
            token.setTokenStatus(closed ? TokenStatus.NOT_SELECTED : TokenStatus.ACTIVE);
            token.setStateCode(definition.getSourceStateCode());
            token.setDecisionNodeCode(eventCode);
            token.setParentScopeKey("ROOT");
            token.setScopeKey("ROOT:" + eventCode);
            token.setBranchKey("ROOT");
            token.setBarrierClosed(Boolean.FALSE);
            token.setTokenVersion(1L);
            token.setRoundNo(0L);
            tokenMapper.insert(token);

            ProcessEvent event = new ProcessEvent();
            event.setInstanceId(instance.getId());
            event.setTokenId(token.getId());
            event.setDefinitionVersionId(instance.getDefinitionVersionId());
            event.setEventDefinitionId(definition.getId());
            event.setEventCode(eventCode);
            event.setEventKey("E:" + eventCode);
            event.setRoundNo(0L);
            event.setBranchIdentity("ROOT");
            event.setEventRole(definition.getEventRole());
            event.setEventDirection(definition.getEventDirection());
            event.setSystemCode(definition.getSystemCode());
            event.setCallContractKey(definition.getCallContractKey());
            event.setCallContractVersion(definition.getCallContractVersion());
            event.setLocalActionKey(definition.getLocalActionKey());
            event.setLocalActionVersion(definition.getLocalActionVersion());
            event.setEffectKey(definition.getEffectKey());
            event.setLocalEffectKey(definition.getLocalEffectKey());
            event.setSourceStateCode(definition.getSourceStateCode());
            event.setTargetStateCode(definition.getTargetStateCode());
            event.setEventStatus(status);
            event.setWaitReason(reason);
            event.setSent(Boolean.FALSE);
            event.setPayloadReady(Boolean.FALSE);
            event.setRetryCount(0);
            event.setRecoveryRequested(Boolean.FALSE);
            event.setRecoveryForEventCode(definition.getRecoveryForEventCode());
            event.setExecutionPurpose(ExecutionPurpose.NORMAL);
            event.setPriority(definition.getPriority() == null ? 0 : definition.getPriority());
            event.setLeaseEpoch(0L);
            event.setEventVersion(1L);
            event.setIdempotencyKey(idempotencyKey(instance.getId(), definition));
            eventMapper.insert(event);
        }
        for (String root : graph.roots()) {
            if (!activity.closed(root)) {
                ProcessEvent event = runtimeDao.findEvent(instance.getId(), root, 0L);
                if (event != null) {
                    eligibilityService.recompute(event.getId());
                }
            }
        }
    }

    /**
     * 预建图中全部 FORK/JOIN scope，消除运行期并发首次插入的唯一键 gap 死锁。
     *
     * <p>恢复分支在挂载时可能仍处于关闭状态，后续变为可执行时也必须已有稳定 scope 行，
     * 因此这里不按当前活动路径跳过。关闭事实仍由事件状态表达，预建 scope 不激活任何事件。</p>
     *
     * @param instance 实例
     * @param graph 冻结图
     */
    private void materializeScopes(ProcessInstance instance, DefinitionGraph graph) {
        for (String code : graph.eventCodes()) {
            if (BranchActivity.isFork(graph, code)) {
                List<String> keys = graph.outgoing(code).stream().map(ProcessEdge::getBranchKey).toList();
                List<String> sorted = new ArrayList<>(keys);
                sorted.sort(String::compareTo);
                insertScope(instance, graph, code, BranchActivity.forkScopeKey(0L, code),
                    String.join(",", sorted));
            }
            if (BranchActivity.isJoin(graph, code)) {
                insertScope(instance, graph, code, BranchActivity.joinScopeKey(0L, code), null);
            }
        }
    }

    private void insertScope(ProcessInstance instance, DefinitionGraph graph, String code, String scopeKey,
                             String expectedKeys) {
        ProcessToken scope = new ProcessToken();
        scope.setInstanceId(instance.getId());
        scope.setTokenRole(TokenRole.SCOPE);
        scope.setTokenStatus(TokenStatus.ACTIVE);
        scope.setStateCode("SCOPE");
        scope.setDecisionNodeCode(code);
        scope.setParentScopeKey(BranchActivity.parentScopeKey(graph, code, 0L));
        scope.setScopeKey(scopeKey);
        scope.setBranchKey("");
        scope.setExpectedKeys(expectedKeys);
        scope.setBarrierClosed(Boolean.FALSE);
        scope.setTokenVersion(1L);
        scope.setRoundNo(0L);
        int rows = tokenMapper.insert(scope);
        if (rows != 1) {
            throw new IllegalStateException("预建 scope 失败: " + scopeKey);
        }
    }

    private EventStatus initialStatus(ProcessEventDefinition definition, Map<String, PostingMode> modes,
                                      BranchActivity activity, boolean closed) {
        if (closed) {
            return EventStatus.NOT_SELECTED;
        }
        boolean root = activity.rootSelected(definition.getEventCode());
        if (!root) {
            return EventStatus.WAIT_PREDECESSOR;
        }
        if (definition.getEventDirection() == EventDirection.INBOUND) {
            return EventStatus.WAIT_EXTERNAL_TRIGGER;
        }
        if (modes.get(definition.getSystemCode()) == PostingMode.MANUAL_CONFIRM) {
            return EventStatus.MANUAL_PENDING;
        }
        return EventStatus.PENDING;
    }

    private WaitReason initialReason(ProcessEventDefinition definition, EventStatus status) {
        if (status == EventStatus.NOT_SELECTED || status == EventStatus.PENDING) {
            return null;
        }
        if (status == EventStatus.MANUAL_PENDING) {
            return WaitReason.FIRST_CONFIRM;
        }
        if (status == EventStatus.WAIT_EXTERNAL_TRIGGER) {
            return definition.getPurpose() != null
                && definition.getPurpose().name().equals("RESULT_CALLBACK")
                ? WaitReason.RESULT_CALLBACK : WaitReason.BUSINESS_TRIGGER;
        }
        return WaitReason.PREDECESSOR;
    }

    private String idempotencyKey(long instanceId, ProcessEventDefinition definition) {
        String key = "I:" + instanceId + ":" + definition.getEventCode();
        if (definition.getEventDirection() == EventDirection.INTERNAL) {
            return key + ":" + definition.getLocalActionVersion() + ":" + definition.getLocalEffectKey();
        }
        return key + ":" + definition.getCallContractVersion() + ":" + definition.getEffectKey();
    }
}
