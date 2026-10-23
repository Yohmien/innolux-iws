/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.1
 * @Description  : 事件资格、活动路径与汇合等待原因统一计算；
 * @ModifyRecord : V1.0 新建；V1.1 NOT_SELECTED 不满足前驱，XOR 闭合与 AND 屏障；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.process.domain.ProcessEdge;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessSystemContext;
import com.innolux.iws.process.domain.ProcessToken;
import com.innolux.iws.process.domain.ProcessTriggerReceipt;
import com.innolux.iws.process.enums.CallPurpose;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.PlanStatus;
import com.innolux.iws.process.enums.PostingMode;
import com.innolux.iws.process.enums.TokenRole;
import com.innolux.iws.process.enums.WaitReason;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import com.innolux.iws.process.mapper.ProcessTriggerReceiptMapper;
import com.innolux.iws.process.support.DefinitionGraph;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 只读本库事实计算事件是否可进入 PENDING。
 *
 * <p>{@code NOT_SELECTED} 表示不可达路径已关闭，不是成功前驱。汇合只等待当前活动路径上的
 * 入边，并以同 scope 屏障关闭作为最后到达事实；已选共享汇合仍会保留。</p>
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class EventEligibilityService {

    private static final Set<EventStatus> SATISFIED = Set.of(EventStatus.SUCCESS, EventStatus.SKIPPED);

    private static final Set<EventStatus> SELECTABLE_STATUS = Set.of(EventStatus.WAIT_PREDECESSOR,
        EventStatus.WAIT_EXTERNAL_TRIGGER, EventStatus.MANUAL_PENDING, EventStatus.PENDING,
        EventStatus.RETRY_WAIT);

    private final RuntimeDao runtimeDao;

    private final ProcessEventMapper eventMapper;

    private final ProcessTriggerReceiptMapper receiptMapper;

    private final ProcessTokenMapper tokenMapper;

    /**
     * 构造资格服务。
     *
     * @param runtimeDao 运行时 DAO
     * @param eventMapper 事件 Mapper
     * @param receiptMapper 回执 Mapper
     * @param tokenMapper token Mapper
     */
    public EventEligibilityService(RuntimeDao runtimeDao, ProcessEventMapper eventMapper,
                                   ProcessTriggerReceiptMapper receiptMapper, ProcessTokenMapper tokenMapper) {
        this.runtimeDao = runtimeDao;
        this.eventMapper = eventMapper;
        this.receiptMapper = receiptMapper;
        this.tokenMapper = tokenMapper;
    }

    /**
     * 重新计算单个事件。只改等待/待执行状态，不清除失败门禁或既有成功证据。
     *
     * @param eventId 事件 ID
     */
    public void recompute(long eventId) {
        ProcessEvent event = runtimeDao.event(eventId);
        EventStatus status = event.getEventStatus();
        if (status == EventStatus.PROCESSING || status == EventStatus.SUCCESS || status == EventStatus.SKIPPED
            || status == EventStatus.NOT_SELECTED || status == EventStatus.FAILED_FINAL
            || (status == EventStatus.MANUAL_PENDING && event.getWaitReason() == WaitReason.EXECUTION_FAILED)) {
            return;
        }
        ProcessInstance instance = runtimeDao.instance(event.getInstanceId());
        if (instance.getPlanStatus() == PlanStatus.CANCELLED
            || instance.getPlanStatus() == PlanStatus.COMPLETED
            || instance.getPlanStatus() == PlanStatus.FAILED_FINAL) {
            return;
        }
        DefinitionGraph graph = runtimeDao.graph(event.getDefinitionVersionId());
        long roundNo = event.getRoundNo() == null ? 0L : event.getRoundNo();
        BranchActivity activity = activity(instance, graph, roundNo);
        if (activity.closed(event.getEventCode())) {
            transition(event, EventStatus.NOT_SELECTED, null);
            return;
        }
        if (status == EventStatus.RETRY_WAIT) {
            return;
        }
        List<ProcessEdge> incoming = graph.incoming(event.getEventCode());
        if (incoming.isEmpty()) {
            if (!activity.rootSelected(event.getEventCode())) {
                transition(event, EventStatus.NOT_SELECTED, null);
                return;
            }
            readyRoot(event);
            return;
        }
        List<ProcessEdge> expected = activity.expectedIncoming(event.getEventCode());
        if (expected.isEmpty()) {
            transition(event, EventStatus.NOT_SELECTED, null);
            return;
        }
        if (incoming.size() > 1 && !joinClosed(instance.getId(), roundNo, event.getEventCode())) {
            transition(event, EventStatus.WAIT_PREDECESSOR, WaitReason.PREDECESSOR);
            return;
        }
        for (ProcessEdge edge : expected) {
            ProcessEvent before = runtimeDao.findEvent(event.getInstanceId(), edge.getPredecessorEventCode(),
                roundNo);
            if (before == null || !SATISFIED.contains(before.getEventStatus())) {
                transition(event, EventStatus.WAIT_PREDECESSOR, WaitReason.PREDECESSOR);
                return;
            }
        }
        if (event.getEventDirection() == EventDirection.INBOUND && !hasCanonicalReceipt(event)) {
            WaitReason reason = event.getExecutionPurpose() == com.innolux.iws.process.enums.ExecutionPurpose.NORMAL
                && isResultCallback(event) ? WaitReason.RESULT_CALLBACK : WaitReason.BUSINESS_TRIGGER;
            transition(event, EventStatus.WAIT_EXTERNAL_TRIGGER, reason);
            return;
        }
        ProcessSystemContext context = runtimeDao.systemContext(event.getInstanceId(), event.getSystemCode());
        if (context != null && context.getPostingMode() == PostingMode.MANUAL_CONFIRM
            && !Boolean.TRUE.equals(context.getFirstConfirmed())) {
            transition(event, EventStatus.MANUAL_PENDING, WaitReason.FIRST_CONFIRM);
            return;
        }
        boolean firstConfirmWait = status == EventStatus.MANUAL_PENDING
            && event.getWaitReason() == WaitReason.FIRST_CONFIRM;
        if (firstConfirmWait || status != EventStatus.MANUAL_PENDING) {
            transition(event, EventStatus.PENDING, null);
        }
    }

    /**
     * 重新计算实例内全部可重算事件。
     *
     * @param instanceId 实例 ID
     */
    public void recomputeInstance(long instanceId) {
        for (ProcessEvent event : runtimeDao.events(instanceId)) {
            recompute(event.getId());
        }
    }

    private void readyRoot(ProcessEvent event) {
        ProcessSystemContext context = runtimeDao.systemContext(event.getInstanceId(), event.getSystemCode());
        if (event.getEventDirection() == EventDirection.INBOUND && !hasCanonicalReceipt(event)) {
            WaitReason reason = event.getExecutionPurpose() == com.innolux.iws.process.enums.ExecutionPurpose.NORMAL
                && isResultCallback(event) ? WaitReason.RESULT_CALLBACK : WaitReason.BUSINESS_TRIGGER;
            transition(event, EventStatus.WAIT_EXTERNAL_TRIGGER, reason);
            return;
        }
        if (context != null && context.getPostingMode() == PostingMode.MANUAL_CONFIRM
            && !Boolean.TRUE.equals(context.getFirstConfirmed())) {
            transition(event, EventStatus.MANUAL_PENDING, WaitReason.FIRST_CONFIRM);
            return;
        }
        transition(event, EventStatus.PENDING, null);
    }

    private boolean joinClosed(long instanceId, long roundNo, String eventCode) {
        ProcessToken scope = tokenMapper.selectOne(Wrappers.<ProcessToken>lambdaQuery()
            .eq(ProcessToken::getInstanceId, instanceId)
            .eq(ProcessToken::getScopeKey, BranchActivity.joinScopeKey(roundNo, eventCode))
            .eq(ProcessToken::getBranchKey, ""));
        return scope != null && Boolean.TRUE.equals(scope.getBarrierClosed());
    }

    private BranchActivity activity(ProcessInstance instance, DefinitionGraph graph, long roundNo) {
        List<ProcessToken> scopes = tokenMapper.selectList(Wrappers.<ProcessToken>lambdaQuery()
            .eq(ProcessToken::getInstanceId, instance.getId())
            .eq(ProcessToken::getTokenRole, TokenRole.SCOPE)
            .eq(ProcessToken::getBranchKey, "")
            .isNotNull(ProcessToken::getSelectedKeys));
        Set<String> forcedClosed = new LinkedHashSet<>();
        for (ProcessEvent item : runtimeDao.events(instance.getId())) {
            if (item.getRecoveryForEventCode() != null && !Boolean.TRUE.equals(item.getRecoveryRequested())) {
                forcedClosed.add(item.getEventCode());
            }
        }
        return new BranchActivity(graph, instance, scopes, forcedClosed, roundNo);
    }

    private boolean hasCanonicalReceipt(ProcessEvent event) {
        List<ProcessTriggerReceipt> receipts = receiptMapper.selectList(
            Wrappers.<ProcessTriggerReceipt>lambdaQuery()
                .eq(ProcessTriggerReceipt::getEventId, event.getId())
                .eq(ProcessTriggerReceipt::getCanonical, Boolean.TRUE));
        return !receipts.isEmpty();
    }

    private boolean isResultCallback(ProcessEvent event) {
        var definition = runtimeDao.graph(event.getDefinitionVersionId()).event(event.getEventCode());
        return definition != null && definition.getPurpose() == CallPurpose.RESULT_CALLBACK;
    }

    private void transition(ProcessEvent event, EventStatus status, WaitReason reason) {
        if (event.getEventStatus() == status && event.getWaitReason() == reason) {
            return;
        }
        long version = event.getEventVersion() == null ? 1L : event.getEventVersion();
        int rows = eventMapper.update(null, Wrappers.<ProcessEvent>lambdaUpdate()
            .eq(ProcessEvent::getId, event.getId())
            .eq(ProcessEvent::getEventVersion, version)
            .in(ProcessEvent::getEventStatus, SELECTABLE_STATUS)
            .set(ProcessEvent::getEventStatus, status)
            .set(ProcessEvent::getWaitReason, reason)
            .set(ProcessEvent::getEventVersion, version + 1)
            .set(ProcessEvent::getUpdateTime, LocalDateTime.now()));
        if (rows == 1) {
            event.setEventStatus(status);
            event.setWaitReason(reason);
            event.setEventVersion(version + 1);
            return;
        }
        ProcessEvent latest = eventMapper.selectById(event.getId());
        if (latest != null) {
            event.setEventStatus(latest.getEventStatus());
            event.setWaitReason(latest.getWaitReason());
            event.setEventVersion(latest.getEventVersion());
        }
    }
}
