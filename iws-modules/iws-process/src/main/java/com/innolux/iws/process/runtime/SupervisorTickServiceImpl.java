/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督有限轮次执行、逐候选 LOCAL、续轮与收口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.api.ControlIntentSnapshot;
import com.innolux.iws.process.api.PlatformResultAck;
import com.innolux.iws.process.api.PlatformResultCommand;
import com.innolux.iws.process.api.SupervisorRoundLease;
import com.innolux.iws.process.api.SupervisorTickResult;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.enums.ControlState;
import com.innolux.iws.process.enums.EventRole;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.RoundStatus;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 监督轮次实现。轮次内只执行监督 DECISION/LOCAL 事件，不执行业务事件。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class SupervisorTickServiceImpl implements SupervisorTickService {

    private final RuntimeDao runtimeDao;

    private final SupervisorRuntimeService supervisorRuntimeService;

    private final ProcessInstanceMapper instanceMapper;

    private final ProcessScheduleControlMapper controlMapper;

    private final PlanDefinitionMapper definitionMapper;

    private final ProcessEventMapper eventMapper;

    private final ProcessAttemptMapper attemptMapper;

    private final SupervisorRoundMaterializer roundMaterializer;

    private final EventUpdateService eventUpdateService;

    /**
     * 构造服务。
     *
     * @param runtimeDao 运行时 DAO
     * @param supervisorRuntimeService 监督运行服务
     * @param instanceMapper 实例 Mapper
     * @param controlMapper 控制 Mapper
     * @param definitionMapper 定义 Mapper
     * @param eventMapper 事件 Mapper
     * @param attemptMapper 尝试 Mapper
     * @param roundMaterializer 轮次物化器
     * @param eventUpdateService 事件更新服务
     */
    public SupervisorTickServiceImpl(RuntimeDao runtimeDao, SupervisorRuntimeService supervisorRuntimeService,
                                     ProcessInstanceMapper instanceMapper,
                                     ProcessScheduleControlMapper controlMapper,
                                     PlanDefinitionMapper definitionMapper,
                                     ProcessEventMapper eventMapper,
                                     ProcessAttemptMapper attemptMapper,
                                     SupervisorRoundMaterializer roundMaterializer,
                                     EventUpdateService eventUpdateService) {
        this.runtimeDao = runtimeDao;
        this.supervisorRuntimeService = supervisorRuntimeService;
        this.instanceMapper = instanceMapper;
        this.controlMapper = controlMapper;
        this.definitionMapper = definitionMapper;
        this.eventMapper = eventMapper;
        this.attemptMapper = attemptMapper;
        this.roundMaterializer = roundMaterializer;
        this.eventUpdateService = eventUpdateService;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SupervisorTickResult tick(long supervisorInstanceId, int batchLimit, long maxDurationMs) {
        long started = System.nanoTime();
        ProcessInstance supervisor = runtimeDao.instance(supervisorInstanceId);
        if (supervisor.getPlanRole() != PlanRole.SUPERVISOR) {
            throw new ServiceException("不是监督实例: " + supervisorInstanceId);
        }
        PlanDefinition definition = definitionMapper.selectById(supervisor.getDefinitionVersionId());
        SupervisorRules.requireSupervisorDefinition(definition);
        int eventsLimit = batchLimit > 0 ? batchLimit
            : (definition.getRoundBatchLimit() == null ? 1 : definition.getRoundBatchLimit());
        long duration = maxDurationMs > 0 ? maxDurationMs
            : (definition.getRoundMaxDurationMs() == null ? 1000L : definition.getRoundMaxDurationMs());
        ProcessScheduleControl before = control(supervisorInstanceId);
        if (before == null) {
            throw new ServiceException("监督控制行不存在: " + supervisorInstanceId);
        }
        if (before.getControlState() == ControlState.MANUAL_PAUSED) {
            return new SupervisorTickResult(supervisorInstanceId, value(before.getRoundNo()), "MANUAL_PAUSED",
                0, 0, 0, elapsed(started), false, "监督已人工暂停");
        }
        SupervisorRoundLease lease = supervisorRuntimeService.beginRound(supervisorInstanceId, null, duration);
        if (lease == null) {
            ProcessScheduleControl latest = control(supervisorInstanceId);
            boolean paused = latest != null && latest.getControlState() == ControlState.MANUAL_PAUSED;
            return new SupervisorTickResult(supervisorInstanceId,
                latest == null ? value(before.getRoundNo()) : value(latest.getRoundNo()),
                paused ? "MANUAL_PAUSED" : "OPEN", 0, 0, 0, elapsed(started), false,
                paused ? "监督已人工暂停" : "SUPERVISOR_BUSY");
        }
        int executed = 0;
        int succeeded = 0;
        int candidateCount = 0;
        int selectedCount = 0;
        boolean failed = false;
        boolean fenced = false;
        String error = null;
        Set<Long> attempted = new HashSet<>();
        try {
            supervisorRuntimeService.restoreRunning(supervisorInstanceId, lease.roundNo(), false);
            roundMaterializer.materializeRound(supervisor, definition, lease.roundNo());
            recoverDecisions(supervisor, definition, lease.roundNo());
            while (executed < eventsLimit && elapsed(started) < duration) {
                if (!supervisorRuntimeService.verifyLease(lease)) {
                    fenced = true;
                    break;
                }
                ProcessEvent event = nextEvent(supervisorInstanceId, lease.roundNo(), attempted);
                if (event == null) {
                    break;
                }
                EventUpdateService.UpdateResult result = eventUpdateService.update(event.getId(), lease.owner());
                attempted.add(event.getId());
                if (result != EventUpdateService.UpdateResult.RACED) {
                    executed++;
                }
                if (result == EventUpdateService.UpdateResult.SUCCEEDED
                    || result == EventUpdateService.UpdateResult.SKIPPED) {
                    succeeded++;
                }
                supervisorRuntimeService.restoreRunning(supervisorInstanceId, lease.roundNo(), false);
                if ((result == EventUpdateService.UpdateResult.SUCCEEDED
                    || result == EventUpdateService.UpdateResult.SKIPPED)
                    && event.getEventRole() == EventRole.DECISION) {
                    SupervisorDecisionStore.Decision decision = decision(event.getId());
                    if (decision != null) {
                        roundMaterializer.materializeCandidates(supervisor, definition, lease.roundNo(), decision);
                    }
                }
                if (result == EventUpdateService.UpdateResult.FAILED
                    || result == EventUpdateService.UpdateResult.UNKNOWN) {
                    failed = true;
                    ProcessEvent latest = eventMapper.selectById(event.getId());
                    error = latest == null ? "监督事件失败" : latest.getErrorMessage();
                    break;
                }
            }
            int[] counts = decisionCounts(supervisorInstanceId, lease.roundNo());
            candidateCount = counts[0];
            selectedCount = counts[1];
            List<ProcessEvent> events = events(supervisorInstanceId, lease.roundNo());
            boolean failedEvent = events.stream().anyMatch(item ->
                item.getEventStatus() == EventStatus.FAILED_FINAL
                    || item.getEffectResult() == EffectResult.UNKNOWN
                    || item.getEffectResult() == EffectResult.FAILED);
            boolean openEvent = events.stream().anyMatch(item -> nonTerminal(item.getEventStatus()));
            boolean unreadyEvent = events.stream().anyMatch(item -> nonTerminal(item.getEventStatus())
                && !ready(item));
            boolean finalFailed = failed || failedEvent;
            if (fenced) {
                return new SupervisorTickResult(supervisorInstanceId, lease.roundNo(), "OPEN",
                    candidateCount, selectedCount, executed, elapsed(started), false, "FENCED");
            }
            if (unreadyEvent) {
                supervisorRuntimeService.closeRound(lease, RoundStatus.FAILED, "EVENT_NOT_READY");
                supervisorRuntimeService.restoreRunning(supervisorInstanceId, lease.roundNo(), false);
                return new SupervisorTickResult(supervisorInstanceId, lease.roundNo(), "FAILED",
                    candidateCount, selectedCount, executed, elapsed(started), false, "EVENT_NOT_READY");
            }
            if (finalFailed) {
                ProcessScheduleControl latest = control(supervisorInstanceId);
                String reason = error == null ? "轮次存在失败事件" : error;
                supervisorRuntimeService.closeRound(lease, RoundStatus.FAILED, reason);
                ProcessScheduleControl after = control(supervisorInstanceId);
                return new SupervisorTickResult(supervisorInstanceId, lease.roundNo(),
                    after != null && after.getRoundStatus() != null ? after.getRoundStatus().name() : "FAILED",
                    candidateCount, selectedCount, executed, elapsed(started), false, reason);
            }
            if (openEvent) {
                supervisorRuntimeService.yieldRound(lease);
                return new SupervisorTickResult(supervisorInstanceId, lease.roundNo(), "OPEN",
                    candidateCount, selectedCount, executed, elapsed(started), true,
                    "本轮未完成，保留 OPEN 续轮");
            }
            supervisorRuntimeService.closeRound(lease, RoundStatus.COMPLETED, "events=" + executed);
            ProcessScheduleControl after = control(supervisorInstanceId);
            return new SupervisorTickResult(supervisorInstanceId, lease.roundNo(),
                after != null && after.getRoundStatus() != null ? after.getRoundStatus().name() : "COMPLETED",
                candidateCount, selectedCount, executed, elapsed(started), false, null);
        } catch (RuntimeException ex) {
            try {
                supervisorRuntimeService.closeRound(lease, RoundStatus.FAILED, ex.getMessage());
                supervisorRuntimeService.restoreRunning(supervisorInstanceId, lease.roundNo(), false);
            } catch (RuntimeException ignored) {
                // 旧代次已失效时不得覆盖新执行者。
            }
            return new SupervisorTickResult(supervisorInstanceId, lease.roundNo(), "FAILED",
                candidateCount, selectedCount, executed, elapsed(started), false,
                ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ControlIntentSnapshot flushControlIntent(long supervisorInstanceId) {
        return supervisorRuntimeService.controlSnapshot(supervisorInstanceId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PlatformResultAck recordPlatformResult(PlatformResultCommand command) {
        return supervisorRuntimeService.recordPlatformResult(command);
    }

    private void recoverDecisions(ProcessInstance supervisor, PlanDefinition definition, long roundNo) {
        List<ProcessEvent> decisions = eventMapper.selectList(Wrappers.<ProcessEvent>lambdaQuery()
            .eq(ProcessEvent::getInstanceId, supervisor.getId())
            .eq(ProcessEvent::getRoundNo, roundNo)
            .eq(ProcessEvent::getEventRole, EventRole.DECISION)
            .eq(ProcessEvent::getEventStatus, EventStatus.SUCCESS));
        for (ProcessEvent decisionEvent : decisions) {
            SupervisorDecisionStore.Decision decision = decision(decisionEvent.getId());
            if (decision != null) {
                roundMaterializer.materializeCandidates(supervisor, definition, roundNo, decision);
            }
        }
    }

    private SupervisorDecisionStore.Decision decision(long eventId) {
        ProcessEvent event = eventMapper.selectById(eventId);
        if (event == null || event.getLastAttemptId() == null) {
            return null;
        }
        ProcessAttempt attempt = attemptMapper.selectById(event.getLastAttemptId());
        return attempt == null ? null : SupervisorDecisionStore.parse(attempt.getCheckpointJson());
    }

    private int[] decisionCounts(long supervisorInstanceId, long roundNo) {
        int candidates = 0;
        int selected = 0;
        List<ProcessEvent> decisions = eventMapper.selectList(Wrappers.<ProcessEvent>lambdaQuery()
            .eq(ProcessEvent::getInstanceId, supervisorInstanceId)
            .eq(ProcessEvent::getRoundNo, roundNo)
            .eq(ProcessEvent::getEventRole, EventRole.DECISION)
            .eq(ProcessEvent::getEventStatus, EventStatus.SUCCESS));
        for (ProcessEvent event : decisions) {
            SupervisorDecisionStore.Decision decision = decision(event.getId());
            if (decision != null) {
                candidates += decision.candidateCount();
                selected += decision.selected().size();
            }
        }
        return new int[]{candidates, selected};
    }

    private ProcessEvent nextEvent(long supervisorInstanceId, long roundNo, Set<Long> attempted) {
        LocalDateTime now = LocalDateTime.now();
        var query = Wrappers.<ProcessEvent>lambdaQuery()
            .eq(ProcessEvent::getInstanceId, supervisorInstanceId)
            .eq(ProcessEvent::getRoundNo, roundNo)
            .and(wrapper -> wrapper.eq(ProcessEvent::getEventStatus, EventStatus.PENDING)
                .or(item -> item.eq(ProcessEvent::getEventStatus, EventStatus.RETRY_WAIT)
                    .and(retry -> retry.isNull(ProcessEvent::getNextRetryTime)
                        .or().le(ProcessEvent::getNextRetryTime, now))))
            .eq(ProcessEvent::getPayloadReady, Boolean.TRUE)
            .isNotNull(ProcessEvent::getInputHash)
            .orderByDesc(ProcessEvent::getPriority)
            .orderByAsc(ProcessEvent::getId)
            .last("LIMIT 1");
        if (!attempted.isEmpty()) {
            query.notIn(ProcessEvent::getId, attempted);
        }
        return eventMapper.selectOne(query);
    }

    private List<ProcessEvent> events(long supervisorInstanceId, long roundNo) {
        return eventMapper.selectList(Wrappers.<ProcessEvent>lambdaQuery()
            .eq(ProcessEvent::getInstanceId, supervisorInstanceId)
            .eq(ProcessEvent::getRoundNo, roundNo)
            .orderByAsc(ProcessEvent::getId));
    }

    private ProcessScheduleControl control(long supervisorInstanceId) {
        return controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisorInstanceId)
            .eq(ProcessScheduleControl::getDuty, SupervisorRules.DUTY_DISPATCH));
    }

    private static boolean nonTerminal(EventStatus status) {
        return status == EventStatus.PENDING || status == EventStatus.PROCESSING
            || status == EventStatus.RETRY_WAIT || status == EventStatus.WAIT_PREDECESSOR
            || status == EventStatus.WAIT_EXTERNAL_TRIGGER || status == EventStatus.MANUAL_PENDING;
    }

    private static boolean ready(ProcessEvent event) {
        return Boolean.TRUE.equals(event.getPayloadReady())
            && event.getInputHash() != null && !event.getInputHash().isBlank();
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }
}
