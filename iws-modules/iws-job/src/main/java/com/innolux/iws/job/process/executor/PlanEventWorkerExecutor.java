/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : QUEUE 业务事件 worker 固定执行器；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.job.process.executor;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import com.innolux.iws.job.process.ProcessJobExecutorNames;
import com.innolux.iws.process.api.EventBatchResult;
import com.innolux.iws.process.api.ProcessSchedulerPort;

/**
 * 有界执行已准入 QUEUE 子计划的业务事件，不驱动 IMMEDIATE。
 */
@JobExecutor(name = ProcessJobExecutorNames.PLAN_EVENT_WORKER)
public class PlanEventWorkerExecutor {

    private final ProcessSchedulerPort schedulerPort;

    /**
     * 构造执行器。
     *
     * @param schedulerPort process 调度端口
     */
    public PlanEventWorkerExecutor(ProcessSchedulerPort schedulerPort) {
        this.schedulerPort = schedulerPort;
    }

    /**
     * 执行一批 QUEUE 业务事件。
     *
     * @param jobArgs SnailJob 参数
     * @return 执行结果
     */
    public ExecuteResult jobExecute(JobArgs jobArgs) {
        try {
            PlanEventWorkerParameters parameters = PlanEventWorkerParameters.from(jobArgs.getJobParams());
            EventBatchResult result = schedulerPort.runEventBatch(parameters.scheduleId(),
                parameters.batchLimit(), parameters.maxDurationMs());
            SnailJobLog.REMOTE.info("计划事件批次完成: scheduleId={}, processed={}, failed={}",
                result.scheduleId(), result.processed(), result.failed());
            return ExecuteResult.success(result);
        } catch (RuntimeException ex) {
            SnailJobLog.REMOTE.error("计划事件批次失败: {}", ex.getMessage());
            return ExecuteResult.failure(null, message(ex));
        }
    }

    private static String message(RuntimeException ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
