/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : QUEUE 业务事件有界 worker；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.process.api.EventBatchResult;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.ExecutionPurpose;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.PlanStatus;
import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * QUEUE 事件 worker。IMMEDIATE 由同步服务驱动，不进入本方法。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class PlanEventWorkerServiceImpl implements PlanEventWorkerService {

    private final ProcessInstanceMapper instanceMapper;

    private final ProcessEventMapper eventMapper;

    private final EventUpdateService eventUpdateService;

    private final EventMutationService eventMutationService;

    private final ProcessObjectAuthorizer objectAuthorizer;

    private final PlanCandidateQueryService candidateQueryService;

    /**
     * 构造 worker。
     *
     * @param instanceMapper 实例 Mapper
     * @param eventMapper 事件 Mapper
     * @param eventUpdateService 事件更新服务
     * @param eventMutationService 事件写入服务
     * @param objectAuthorizer 对象授权入口
     * @param candidateQueryService 共享候选规则
     */
    public PlanEventWorkerServiceImpl(ProcessInstanceMapper instanceMapper, ProcessEventMapper eventMapper,
                                      EventUpdateService eventUpdateService,
                                      EventMutationService eventMutationService,
                                      ProcessObjectAuthorizer objectAuthorizer,
                                      PlanCandidateQueryService candidateQueryService) {
        this.instanceMapper = instanceMapper;
        this.eventMapper = eventMapper;
        this.eventUpdateService = eventUpdateService;
        this.eventMutationService = eventMutationService;
        this.objectAuthorizer = objectAuthorizer;
        this.candidateQueryService = candidateQueryService;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EventBatchResult runBatch(long scheduleId, int batchLimit, long maxDurationMs) {
        return ProcessTrustedInvocation.call(() -> runBatchTrusted(scheduleId, batchLimit, maxDurationMs));
    }

    private EventBatchResult runBatchTrusted(long scheduleId, int batchLimit, long maxDurationMs) {
        long started = System.nanoTime();
        eventMutationService.recoverExpiredLeases();
        int processed = 0;
        int succeeded = 0;
        int failed = 0;
        int skipped = 0;
        String owner = "worker:" + UUID.randomUUID();
        List<PlanCandidateQueryService.CandidateRow> candidates = candidateQueryService
            .loadQueueCandidates(scheduleId, Math.max(1, batchLimit));
        for (PlanCandidateQueryService.CandidateRow candidate : candidates) {
            if (processed >= batchLimit || elapsed(started) >= maxDurationMs) {
                return new EventBatchResult(scheduleId, processed, succeeded, failed, skipped, true,
                    elapsed(started), null);
            }
            objectAuthorizer.authorizeTrustedEvent(candidate.eventId(), "EXECUTE");
            EventUpdateService.UpdateResult result = eventUpdateService.update(candidate.eventId(), owner);
            processed++;
            switch (result) {
                case SUCCEEDED -> succeeded++;
                case SKIPPED -> skipped++;
                case FAILED, UNKNOWN -> failed++;
                case RACED -> {
                    // 竞争的 worker 已处理，不计本批次结果。
                }
            }
        }
        List<ProcessEvent> recoveryEvents = eventMapper.selectList(Wrappers.<ProcessEvent>lambdaQuery()
            .eq(ProcessEvent::getExecutionPurpose, ExecutionPurpose.RECOVERY_ONLY)
            .eq(ProcessEvent::getEventStatus, EventStatus.PENDING)
            .isNotNull(ProcessEvent::getRecoveryDeadline)
            .gt(ProcessEvent::getRecoveryDeadline, LocalDateTime.now())
            .orderByAsc(ProcessEvent::getId));
        for (ProcessEvent event : recoveryEvents) {
            if (processed >= batchLimit || elapsed(started) >= maxDurationMs) {
                return new EventBatchResult(scheduleId, processed, succeeded, failed, skipped, true,
                    elapsed(started), null);
            }
            ProcessInstance instance = instanceMapper.selectById(event.getInstanceId());
            if (instance == null || instance.getScheduleId() != scheduleId
                || instance.getPlanStatus() == PlanStatus.PAUSED
                || instance.getPlanStatus() == PlanStatus.CANCELLED
                || instance.getPlanStatus() == PlanStatus.COMPLETED
                || instance.getPlanStatus() == PlanStatus.FAILED_FINAL) {
                continue;
            }
            objectAuthorizer.authorizeTrustedEvent(event.getId(), "RECOVERY_ONLY");
            EventUpdateService.UpdateResult result = eventUpdateService.update(event.getId(), owner);
            processed++;
            switch (result) {
                case SUCCEEDED -> succeeded++;
                case SKIPPED -> skipped++;
                case FAILED, UNKNOWN -> failed++;
                case RACED -> {
                    // 竞争执行者已处理。
                }
            }
        }
        return new EventBatchResult(scheduleId, processed, succeeded, failed, skipped, false,
            elapsed(started), null);
    }

    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
