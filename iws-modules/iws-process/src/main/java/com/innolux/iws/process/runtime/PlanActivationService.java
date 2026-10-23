/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.2
 * @Description  : 活动路径闭合、汇合屏障、token 推进与计划终结；
 * @ModifyRecord : V1.0 新建；V1.1 XOR 后代闭合/共享汇合保留、AND scope 锁、监督不终结；V1.2 实例锁统一激活锁序；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessEdge;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.domain.ProcessToken;
import com.innolux.iws.process.enums.BranchKind;
import com.innolux.iws.process.enums.CompletionPolicy;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.PermitState;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.PlanStatus;
import com.innolux.iws.process.enums.ScheduleControlScope;
import com.innolux.iws.process.enums.SlotState;
import com.innolux.iws.process.enums.StateKind;
import com.innolux.iws.process.enums.TokenRole;
import com.innolux.iws.process.enums.TokenStatus;
import com.innolux.iws.process.enums.WaitReason;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import com.innolux.iws.process.support.DefinitionGraph;
import com.innolux.iws.process.support.ProcessText;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 在事件终态后激活后继、关闭不可达路径并计算计划聚合状态。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class PlanActivationService {

    private static final Set<EventStatus> TERMINAL_SUCCESS = Set.of(EventStatus.SUCCESS, EventStatus.SKIPPED,
        EventStatus.NOT_SELECTED);

    private static final Set<EventStatus> EXPECTED_TERMINAL = Set.of(EventStatus.SUCCESS, EventStatus.SKIPPED);

    private static final Set<EventStatus> ACTIVE_STATUS = Set.of(EventStatus.PROCESSING, EventStatus.PENDING,
        EventStatus.RETRY_WAIT, EventStatus.WAIT_EXTERNAL_TRIGGER, EventStatus.WAIT_PREDECESSOR);

    private static final Set<EventStatus> SELECTABLE_STATUS = Set.of(EventStatus.WAIT_PREDECESSOR,
        EventStatus.WAIT_EXTERNAL_TRIGGER, EventStatus.MANUAL_PENDING, EventStatus.PENDING,
        EventStatus.RETRY_WAIT);

    private final RuntimeDao runtimeDao;

    private final EventEligibilityService eligibilityService;

    private final BranchSelector branchSelector;

    private final ProcessEventMapper eventMapper;

    private final ProcessTokenMapper tokenMapper;

    private final ProcessInstanceMapper instanceMapper;

    private final ProcessScheduleControlMapper controlMapper;

    private final ObjectMapper objectMapper;

    /**
     * 构造激活服务。
     *
     * @param runtimeDao 运行时 DAO
     * @param eligibilityService 资格服务
     * @param branchSelector 分支选择器
     * @param eventMapper 事件 Mapper
     * @param tokenMapper token Mapper
     * @param instanceMapper 实例 Mapper
     * @param controlMapper 控制 Mapper
     * @param objectMapper JSON 映射器，只读检查点事实
     */
    public PlanActivationService(RuntimeDao runtimeDao, EventEligibilityService eligibilityService,
                                 BranchSelector branchSelector, ProcessEventMapper eventMapper,
                                 ProcessTokenMapper tokenMapper, ProcessInstanceMapper instanceMapper,
                                 ProcessScheduleControlMapper controlMapper, ObjectMapper objectMapper) {
        this.runtimeDao = runtimeDao;
        this.eligibilityService = eligibilityService;
        this.branchSelector = branchSelector;
        this.eventMapper = eventMapper;
        this.tokenMapper = tokenMapper;
        this.instanceMapper = instanceMapper;
        this.controlMapper = controlMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 激活已完成事件的后继。
     *
     * <p>本方法由 T5 收口事务调用。XOR 决策、未选路径关闭、汇合到达和资格重算均在当前
     * 事务内同步完成，不另起异步内存补唤醒。</p>
     *
     * @param event 已完成事件
     */
    @Transactional(rollbackFor = Exception.class)
    public void activateSuccessors(ProcessEvent event) {
        ProcessInstance instance = lockInstance(event.getInstanceId());
        DefinitionGraph graph = runtimeDao.graph(event.getDefinitionVersionId());
        long roundNo = roundNo(event);
        List<ProcessEdge> outgoing = graph.outgoing(event.getEventCode());
        if (outgoing.isEmpty()) {
            finishToken(event);
        } else {
            if (outgoing.size() > 1) {
                freezeBranch(instance, event, graph, outgoing, roundNo);
            }
            for (ProcessEdge edge : outgoing) {
                if (BranchActivity.isJoin(graph, edge.getSuccessorEventCode())) {
                    ProcessEvent successor = runtimeDao.findEvent(instance.getId(),
                        edge.getSuccessorEventCode(), roundNo);
                    if (successor != null) {
                        registerArrival(instance, graph, successor, edge);
                    }
                }
            }
            reconcile(instance, graph, roundNo);
        }
        updatePlanStatus(instance.getId());
    }

    private void freezeBranch(ProcessInstance instance, ProcessEvent event, DefinitionGraph graph,
                              List<ProcessEdge> outgoing, long roundNo) {
        BranchKind kind = outgoing.getFirst().getBranchKind();
        if (kind != BranchKind.XOR && kind != BranchKind.AND) {
            throw new ServiceException("多条出边必须是 XOR 或 AND: " + event.getEventCode());
        }
        branchSelector.select(new BranchContext(event.getInstanceId(), event.getEventCode(),
                BranchActivity.forkScopeKey(roundNo, event.getEventCode()), decisionHash(event),
                decisionFacts(instance, event, graph, roundNo)),
            outgoing, BranchActivity.parentScopeKey(graph, event.getEventCode(), roundNo), roundNo);
    }

    /**
     * 从本库冻结输入和已持久动作证据构建只读决策事实，不发起任何外部调用。
     *
     * @param instance 实例
     * @param event 已完成的决策节点事件
     * @param graph 定义图
     * @param roundNo 轮次
     * @return 决策事实
     */
    private Map<String, String> decisionFacts(ProcessInstance instance, ProcessEvent event,
                                              DefinitionGraph graph, long roundNo) {
        Map<String, String> facts = new LinkedHashMap<>();
        put(facts, "instance.id", instance.getId());
        put(facts, "instance.inputHash", instance.getInputHash());
        put(facts, "instance.businessType", instance.getBusinessType());
        put(facts, "instance.businessId", instance.getBusinessId());
        put(facts, "instance.taskKey", instance.getTaskKey());
        put(facts, "instance.planType", instance.getPlanType());
        put(facts, "instance.scheduleScope", instance.getScheduleScope());
        put(facts, "instance.selectedRootSet", instance.getSelectedRootSet());
        put(facts, "instance.instancePriority", instance.getInstancePriority());
        put(facts, "event.code", event.getEventCode());
        put(facts, "event.key", event.getEventKey());
        put(facts, "event.roundNo", roundNo);
        put(facts, "event.inputHash", event.getInputHash());
        put(facts, "event.effectResult", event.getEffectResult());
        put(facts, "event.outcome", event.getOutcome());
        put(facts, "event.systemCode", event.getSystemCode());
        put(facts, "event.sourceStateCode", event.getSourceStateCode());
        put(facts, "event.targetStateCode", event.getTargetStateCode());
        put(facts, "event.branchIdentity", event.getBranchIdentity());
        put(facts, "event.idempotencyKey", event.getIdempotencyKey());
        put(facts, "event.originOutboundEventId", event.getOriginOutboundEventId());
        put(facts, "event.recoveryForEventCode", event.getRecoveryForEventCode());
        if (event.getLastAttemptId() != null) {
            ProcessAttempt attempt = runtimeDao.attempt(event.getLastAttemptId());
            put(facts, "attempt.effectResult", attempt.getEffectResult());
            put(facts, "attempt.checkpointStatus", attempt.getCheckpointStatus());
            put(facts, "attempt.evidenceRef", attempt.getEvidenceRef());
            put(facts, "attempt.requestId", attempt.getRequestId());
            put(facts, "attempt.localEffectKey", attempt.getLocalEffectKey());
            mergeCheckpointFacts(facts, attempt.getCheckpointJson());
        }
        for (ProcessEdge edge : graph.incoming(event.getEventCode())) {
            ProcessEvent before = runtimeDao.findEvent(instance.getId(), edge.getPredecessorEventCode(), roundNo);
            if (before == null) {
                continue;
            }
            String prefix = "pred." + before.getEventCode() + ".";
            put(facts, prefix + "status", before.getEventStatus());
            put(facts, prefix + "effectResult", before.getEffectResult());
            put(facts, prefix + "outcome", before.getOutcome());
        }
        return Map.copyOf(facts);
    }

    private void mergeCheckpointFacts(Map<String, String> facts, String checkpointJson) {
        if (checkpointJson == null || checkpointJson.isBlank()) {
            return;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(checkpointJson);
        } catch (Exception ex) {
            facts.put("checkpoint.raw", checkpointJson);
            return;
        }
        if (node == null || !node.isObject()) {
            facts.put("checkpoint.raw", checkpointJson);
            return;
        }
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue() != null && !entry.getValue().isNull()) {
                facts.put("checkpoint." + entry.getKey(), entry.getValue().isValueNode()
                    ? entry.getValue().asText() : entry.getValue().toString());
            }
        });
    }

    private static void put(Map<String, String> facts, String key, Object value) {
        if (value != null) {
            facts.put(key, String.valueOf(value));
        }
    }

    private void finishToken(ProcessEvent event) {
        ProcessToken token = runtimeDao.token(event.getTokenId());
        if (token.getTokenStatus() == TokenStatus.FINISHED
            || token.getTokenStatus() == TokenStatus.NOT_SELECTED) {
            return;
        }
        long version = token.getTokenVersion() == null ? 0L : token.getTokenVersion();
        int rows = tokenMapper.update(null, Wrappers.<ProcessToken>lambdaUpdate()
            .eq(ProcessToken::getId, token.getId())
            .eq(ProcessToken::getTokenVersion, version)
            .set(ProcessToken::getTokenStatus, TokenStatus.FINISHED)
            .set(ProcessToken::getTokenVersion, version + 1)
            .set(ProcessToken::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new ServiceException("token 完成版本冲突: " + token.getId());
        }
    }

    private void registerArrival(ProcessInstance instance, DefinitionGraph graph, ProcessEvent successor,
                                 ProcessEdge edge) {
        long roundNo = roundNo(successor);
        String scopeKey = BranchActivity.joinScopeKey(roundNo, successor.getEventCode());
        String arrival = BranchActivity.arrivalKey(edge);
        ProcessToken scope = lockScope(instance.getId(), scopeKey);
        BranchActivity activity = activity(instance, graph, roundNo);
        Set<String> expected = activity.expectedJoinKeys(successor.getEventCode());
        if (expected.isEmpty()) {
            return;
        }
        if (scope == null) {
            Set<String> joined = new LinkedHashSet<>();
            if (expected.contains(arrival)) {
                joined.add(arrival);
            }
            ProcessToken row = newJoinScope(instance, successor, activity, graph, scopeKey, roundNo, expected,
                joined);
            try {
                int rows = tokenMapper.insert(row);
                if (rows != 1) {
                    throw new ServiceException("汇合 scope 写入失败: " + scopeKey);
                }
                return;
            } catch (DuplicateKeyException ex) {
                scope = lockScope(instance.getId(), scopeKey);
                if (scope == null) {
                    throw new ServiceException("汇合 scope 并发写入后读回失败: " + scopeKey);
                }
            }
        }
        applyArrival(scope, successor, expected, arrival);
    }

    private void refreshJoin(ProcessInstance instance, DefinitionGraph graph, ProcessEvent joinEvent) {
        long roundNo = roundNo(joinEvent);
        ProcessToken scope = lockScope(instance.getId(),
            BranchActivity.joinScopeKey(roundNo, joinEvent.getEventCode()));
        if (scope == null) {
            return;
        }
        BranchActivity activity = activity(instance, graph, roundNo);
        Set<String> expected = activity.expectedJoinKeys(joinEvent.getEventCode());
        if (expected.isEmpty()) {
            return;
        }
        Set<String> joined = new LinkedHashSet<>(BranchActivity.split(scope.getJoinedKeys()));
        joined.retainAll(expected);
        persistArrival(scope, expected, joined);
    }

    private void applyArrival(ProcessToken scope, ProcessEvent successor, Set<String> expected, String arrival) {
        if (Boolean.TRUE.equals(scope.getBarrierClosed())) {
            return;
        }
        Set<String> joined = new LinkedHashSet<>(BranchActivity.split(scope.getJoinedKeys()));
        if (expected.contains(arrival)) {
            joined.add(arrival);
        }
        joined.retainAll(expected);
        persistArrival(scope, expected, joined);
    }

    private void persistArrival(ProcessToken scope, Set<String> expected, Set<String> joined) {
        boolean closed = joined.containsAll(expected);
        Set<String> storedExpected = BranchActivity.split(scope.getExpectedKeys());
        Set<String> storedJoined = BranchActivity.split(scope.getJoinedKeys());
        if (storedExpected.equals(expected) && storedJoined.equals(joined)
            && Objects.equals(Boolean.valueOf(closed), scope.getBarrierClosed())) {
            return;
        }
        long version = scope.getTokenVersion() == null ? 0L : scope.getTokenVersion();
        int rows = tokenMapper.update(null, Wrappers.<ProcessToken>lambdaUpdate()
            .eq(ProcessToken::getId, scope.getId())
            .eq(ProcessToken::getTokenVersion, version)
            .set(ProcessToken::getExpectedKeys, csv(expected))
            .set(ProcessToken::getJoinedKeys, csv(joined))
            .set(ProcessToken::getBarrierClosed, closed)
            .set(ProcessToken::getTokenVersion, version + 1)
            .set(ProcessToken::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new ServiceException("汇合 scope 版本冲突: " + scope.getScopeKey());
        }
    }

    private ProcessToken newJoinScope(ProcessInstance instance, ProcessEvent joinEvent, BranchActivity activity,
                                      DefinitionGraph graph, String scopeKey, long roundNo,
                                      Set<String> expected, Set<String> joined) {
        ProcessToken scope = new ProcessToken();
        scope.setInstanceId(instance.getId());
        scope.setTokenRole(TokenRole.SCOPE);
        scope.setTokenStatus(TokenStatus.ACTIVE);
        scope.setStateCode("SCOPE");
        scope.setDecisionNodeCode(joinEvent.getEventCode());
        scope.setParentScopeKey(BranchActivity.parentScopeKey(graph, joinEvent.getEventCode(), roundNo));
        scope.setScopeKey(scopeKey);
        scope.setBranchKey("");
        scope.setDecisionInputHash(joinEvent.getInputHash());
        scope.setExpectedKeys(csv(expected));
        scope.setJoinedKeys(csv(joined));
        scope.setBarrierClosed(joined.containsAll(expected));
        scope.setTokenVersion(1L);
        scope.setRoundNo(roundNo);
        scope.setUpdateTime(LocalDateTime.now());
        return scope;
    }

    private void reconcile(ProcessInstance instance, DefinitionGraph graph, long roundNo) {
        BranchActivity activity = activity(instance, graph, roundNo);
        List<String> order = graph.topologicalOrder();
        if (order.isEmpty()) {
            order = new ArrayList<>(graph.eventCodes());
        }
        for (String code : order) {
            ProcessEvent candidate = runtimeDao.findEvent(instance.getId(), code, roundNo);
            if (candidate != null && activity.closed(code)) {
                markNotSelected(candidate);
            }
        }
        for (String code : order) {
            ProcessEvent candidate = runtimeDao.findEvent(instance.getId(), code, roundNo);
            if (candidate != null && BranchActivity.isJoin(graph, code) && !activity.closed(code)) {
                refreshJoin(instance, graph, candidate);
            }
        }
        for (String code : order) {
            ProcessEvent candidate = runtimeDao.findEvent(instance.getId(), code, roundNo);
            if (candidate != null && !activity.closed(code)) {
                eligibilityService.recompute(candidate.getId());
            }
        }
    }

    /**
     * 重新计算计划聚合状态。
     *
     * @param instanceId 实例 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void updatePlanStatus(long instanceId) {
        ProcessInstance instance = runtimeDao.instance(instanceId);
        if (instance.getPlanStatus() == PlanStatus.PAUSED || instance.getPlanStatus() == PlanStatus.CANCELLED
            || instance.getPlanStatus() == PlanStatus.COMPLETED || instance.getPlanStatus() == PlanStatus.FAILED_FINAL) {
            return;
        }
        PlanDefinition definition = runtimeDao.definition(instance.getDefinitionVersionId());
        PlanRole role = definition.getPlanRole() == null ? PlanRole.BUSINESS : definition.getPlanRole();
        List<ProcessEvent> events = runtimeDao.events(instanceId);
        boolean unknown = events.stream().anyMatch(item -> item.getEffectResult() == EffectResult.UNKNOWN);
        boolean manual = events.stream().anyMatch(item -> item.getEventStatus() == EventStatus.MANUAL_PENDING);
        boolean active = events.stream().anyMatch(item -> ACTIVE_STATUS.contains(item.getEventStatus()));
        boolean failedFinal = events.stream().anyMatch(item -> item.getEventStatus() == EventStatus.FAILED_FINAL);
        PlanStatus next;
        boolean release = false;
        if (role == PlanRole.SUPERVISOR) {
            next = unknown ? PlanStatus.EXCEPTION_HOLD
                : (manual ? PlanStatus.MANUAL_PENDING : PlanStatus.RUNNING);
            if (instance.getPlanStatus() == PlanStatus.CREATED) {
                return;
            }
        } else {
            if (instance.getPlanStatus() == PlanStatus.CREATED) {
                return;
            }
            DefinitionGraph graph = runtimeDao.graph(instance.getDefinitionVersionId());
            BranchActivity activity = activity(instance, graph, 0L);
            boolean settled = expectedSettled(activity, events, graph, instanceId);
            next = PlanStatus.RUNNING;
            if (unknown) {
                next = PlanStatus.EXCEPTION_HOLD;
            } else if (manual) {
                next = PlanStatus.MANUAL_PENDING;
            } else if (!active && settled) {
                if (completionSatisfied(instance, graph, events, activity, definition)) {
                    next = hasOpenCall(events) || hasOpenJoin(instanceId, graph, activity, 0L) ? PlanStatus.RUNNING
                        : PlanStatus.COMPLETED;
                } else if (failedFinal) {
                    next = PlanStatus.FAILED_FINAL;
                }
            }
            release = next == PlanStatus.COMPLETED || next == PlanStatus.FAILED_FINAL;
        }
        if (next == instance.getPlanStatus()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, instanceId)
            .eq(ProcessInstance::getInstanceVersion, instance.getInstanceVersion())
            .set(ProcessInstance::getPlanStatus, next)
            .set(ProcessInstance::getInstanceVersion, instance.getInstanceVersion() + 1)
            .set(next == PlanStatus.COMPLETED || next == PlanStatus.FAILED_FINAL, ProcessInstance::getEndedAt, now)
            .set(ProcessInstance::getUpdateTime, now));
        if (rows != 1) {
            ProcessInstance latest = runtimeDao.instance(instanceId);
            if (latest.getPlanStatus() == next) {
                return;
            }
            throw new ServiceException("计划状态并发冲突: " + instanceId);
        }
        if (release) {
            releaseSlot(instanceId);
        }
    }

    private boolean expectedSettled(BranchActivity activity, List<ProcessEvent> events, DefinitionGraph graph,
                                    long instanceId) {
        Map<String, ProcessEvent> byCode = new LinkedHashMap<>();
        for (ProcessEvent event : events) {
            byCode.putIfAbsent(event.getEventCode(), event);
        }
        for (String code : activity.expectedCodes()) {
            ProcessEvent event = byCode.get(code);
            if (event == null) {
                return false;
            }
            if (!EXPECTED_TERMINAL.contains(event.getEventStatus())
                && event.getEventStatus() != EventStatus.FAILED_FINAL) {
                return false;
            }
            if (BranchActivity.isJoin(graph, code) && !joinClosed(instanceId, 0L, activity, code)) {
                return false;
            }
        }
        return true;
    }

    private boolean completionSatisfied(ProcessInstance instance, DefinitionGraph graph, List<ProcessEvent> events,
                                        BranchActivity activity, PlanDefinition definition) {
        boolean anyTerminalSuccess = events.stream().anyMatch(event -> event.getEventStatus() == EventStatus.SUCCESS
            && graph.state(event.getTargetStateCode()) != null
            && graph.state(event.getTargetStateCode()).getStateKind() == StateKind.END);
        if (definition.getCompletionPolicy() == CompletionPolicy.ANY_SUCCESS) {
            return anyTerminalSuccess;
        }
        if (!anyTerminalSuccess) {
            return false;
        }
        for (String code : activity.expectedCodes()) {
            ProcessEvent event = events.stream().filter(item -> code.equals(item.getEventCode())).findFirst()
                .orElse(null);
            if (event == null || !EXPECTED_TERMINAL.contains(event.getEventStatus())) {
                return false;
            }
        }
        return true;
    }

    private boolean hasOpenJoin(long instanceId, DefinitionGraph graph, BranchActivity activity, long roundNo) {
        for (String code : activity.expectedCodes()) {
            if (BranchActivity.isJoin(graph, code) && !joinClosed(instanceId, roundNo, activity, code)) {
                return true;
            }
        }
        return false;
    }

    private boolean joinClosed(long instanceId, long roundNo, BranchActivity activity, String eventCode) {
        Set<String> expected = activity.expectedJoinKeys(eventCode);
        if (expected.isEmpty()) {
            return true;
        }
        ProcessToken scope = tokenMapper.selectOne(Wrappers.<ProcessToken>lambdaQuery()
            .eq(ProcessToken::getInstanceId, instanceId)
            .eq(ProcessToken::getScopeKey, BranchActivity.joinScopeKey(roundNo, eventCode))
            .eq(ProcessToken::getBranchKey, ""));
        return scope != null && Boolean.TRUE.equals(scope.getBarrierClosed());
    }

    private boolean hasOpenCall(List<ProcessEvent> events) {
        if (events.isEmpty()) {
            return false;
        }
        List<String> eventIds = events.stream().map(event -> String.valueOf(event.getId())).toList();
        return controlMapper.selectCount(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.CALL)
            .in(ProcessScheduleControl::getScopeKey, eventIds)
            .eq(ProcessScheduleControl::getDuty, "CALL_PERMIT")
            .in(ProcessScheduleControl::getPermitState, PermitState.INTENT, PermitState.IN_FLIGHT,
                PermitState.ACCEPTED, PermitState.WAIT_RESULT)) > 0;
    }

    /**
     * 安全释放一次 PLAN_SLOT。
     *
     * @param instanceId 子计划实例 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void releaseSlot(long instanceId) {
        ProcessScheduleControl slot = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.PLAN_SLOT)
            .eq(ProcessScheduleControl::getScopeKey, String.valueOf(instanceId))
            .eq(ProcessScheduleControl::getDuty, "PLAN_SLOT")
            .last("FOR UPDATE"));
        if (slot == null || slot.getSlotState() == SlotState.RELEASED) {
            return;
        }
        if (slot.getSlotState() != SlotState.HELD) {
            throw new ServiceException("PLAN_SLOT 状态非法: " + instanceId);
        }
        long revision = slot.getRevision() == null ? 0L : slot.getRevision();
        int rows = controlMapper.update(null, Wrappers.<ProcessScheduleControl>lambdaUpdate()
            .eq(ProcessScheduleControl::getId, slot.getId())
            .eq(ProcessScheduleControl::getRevision, revision)
            .eq(ProcessScheduleControl::getSlotState, SlotState.HELD)
            .set(ProcessScheduleControl::getSlotState, SlotState.RELEASED)
            .set(ProcessScheduleControl::getRevision, revision + 1)
            .set(ProcessScheduleControl::getLastActivityAt, LocalDateTime.now()));
        if (rows != 1) {
            throw new ServiceException("PLAN_SLOT 释放竞争失败: " + instanceId);
        }
    }

    private void markNotSelected(ProcessEvent event) {
        EventStatus status = event.getEventStatus();
        if (status == null || status == EventStatus.NOT_SELECTED || TERMINAL_SUCCESS.contains(status)
            || status == EventStatus.PROCESSING || status == EventStatus.FAILED_FINAL) {
            return;
        }
        if (event.getEffectResult() != null && event.getEffectResult() != EffectResult.FAILED
            && event.getEffectResult() != EffectResult.UNKNOWN) {
            return;
        }
        long version = event.getEventVersion() == null ? 1L : event.getEventVersion();
        int rows = eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, event.getId())
            .eq(ProcessEvent::getEventVersion, version)
            .in(ProcessEvent::getEventStatus, SELECTABLE_STATUS)
            .set(ProcessEvent::getEventStatus, EventStatus.NOT_SELECTED)
            .set(ProcessEvent::getWaitReason, null)
            .set(ProcessEvent::getEventVersion, version + 1)
            .set(ProcessEvent::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            ProcessEvent latest = runtimeDao.event(event.getId());
            if (latest.getEventStatus() == EventStatus.NOT_SELECTED) {
                return;
            }
            throw new ServiceException("事件关闭竞争失败: " + event.getId());
        }
        ProcessToken token = runtimeDao.token(event.getTokenId());
        if (token.getTokenStatus() == TokenStatus.NOT_SELECTED) {
            return;
        }
        long tokenVersion = token.getTokenVersion() == null ? 0L : token.getTokenVersion();
        rows = tokenMapper.update(null, Wrappers.<ProcessToken>lambdaUpdate()
            .eq(ProcessToken::getId, token.getId())
            .eq(ProcessToken::getTokenVersion, tokenVersion)
            .set(ProcessToken::getTokenStatus, TokenStatus.NOT_SELECTED)
            .set(ProcessToken::getTokenVersion, tokenVersion + 1)
            .set(ProcessToken::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new ServiceException("token 关闭竞争失败: " + token.getId());
        }
    }

    private BranchActivity activity(ProcessInstance instance, DefinitionGraph graph, long roundNo) {
        List<ProcessToken> scopes = tokenMapper.selectList(Wrappers.<ProcessToken>lambdaQuery()
            .eq(ProcessToken::getInstanceId, instance.getId())
            .eq(ProcessToken::getTokenRole, TokenRole.SCOPE)
            .eq(ProcessToken::getBranchKey, "")
            .isNotNull(ProcessToken::getSelectedKeys));
        Set<String> forcedClosed = new LinkedHashSet<>();
        for (ProcessEvent event : runtimeDao.events(instance.getId())) {
            if (event.getRecoveryForEventCode() != null
                && !Boolean.TRUE.equals(event.getRecoveryRequested())) {
                forcedClosed.add(event.getEventCode());
            }
        }
        return new BranchActivity(graph, instance, scopes, forcedClosed, roundNo);
    }

    private ProcessToken lockScope(long instanceId, String scopeKey) {
        return tokenMapper.selectOne(Wrappers.<ProcessToken>lambdaQuery()
            .eq(ProcessToken::getInstanceId, instanceId)
            .eq(ProcessToken::getScopeKey, scopeKey)
            .eq(ProcessToken::getBranchKey, "")
            .last("FOR UPDATE"));
    }

    /**
     * 统一激活事务锁序：实例行、scope、事件。
     *
     * <p>同一实例的后继激活串行执行；不同实例互不阻塞。实例锁必须先于任何 scope 读取和
     * 首次插入，避免两个事务各持 gap 锁后互等插入意向锁，也避免多个汇合 scope 以相反顺序互锁。</p>
     *
     * @param instanceId 实例 ID
     * @return 已锁定实例
     */
    private ProcessInstance lockInstance(long instanceId) {
        ProcessInstance instance = instanceMapper.selectOne(Wrappers.<ProcessInstance>lambdaQuery()
            .eq(ProcessInstance::getId, instanceId)
            .last("FOR UPDATE"));
        if (instance == null) {
            throw new ServiceException("计划实例不存在: " + instanceId);
        }
        return instance;
    }

    private String decisionHash(ProcessEvent event) {
        if (event.getInputHash() != null && !event.getInputHash().isBlank()) {
            return event.getInputHash();
        }
        return ProcessText.sha256(event.getInstanceId() + ":" + event.getEventCode() + ":"
            + roundNo(event) + ":" + event.getTokenId());
    }

    private static long roundNo(ProcessEvent event) {
        return event.getRoundNo() == null ? 0L : event.getRoundNo();
    }

    private static String csv(Collection<String> values) {
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(String::compareTo);
        return String.join(",", sorted);
    }
}
