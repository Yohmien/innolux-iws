/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : ProcessSchedulerPortImpl；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.process.api.ControlIntentSnapshot;
import com.innolux.iws.process.api.EventBatchResult;
import com.innolux.iws.process.api.PlatformResultAck;
import com.innolux.iws.process.api.PlatformResultCommand;
import com.innolux.iws.process.api.ProcessSchedulerPort;
import com.innolux.iws.process.api.SupervisorTickResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * job 调度端口实现，仅委托监督轮次与事件 worker 服务。
 */
@Service
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class ProcessSchedulerPortImpl implements ProcessSchedulerPort {

    private final SupervisorTickService supervisorTickService;

    private final PlanEventWorkerService planEventWorkerService;

    /**
     * 构造调度端口。
     *
     * @param supervisorTickService 监督轮次服务
     * @param planEventWorkerService 业务事件 worker
     */
    public ProcessSchedulerPortImpl(SupervisorTickService supervisorTickService,
                                    PlanEventWorkerService planEventWorkerService) {
        this.supervisorTickService = supervisorTickService;
        this.planEventWorkerService = planEventWorkerService;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SupervisorTickResult tickSupervisor(long supervisorInstanceId, int batchLimit, long maxDurationMs) {
        return supervisorTickService.tick(supervisorInstanceId, batchLimit, maxDurationMs);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EventBatchResult runEventBatch(long scheduleId, int batchLimit, long maxDurationMs) {
        return planEventWorkerService.runBatch(scheduleId, batchLimit, maxDurationMs);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ControlIntentSnapshot flushControlIntent(long supervisorInstanceId) {
        return supervisorTickService.flushControlIntent(supervisorInstanceId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PlatformResultAck recordPlatformResult(PlatformResultCommand command) {
        return supervisorTickService.recordPlatformResult(command);
    }
}
