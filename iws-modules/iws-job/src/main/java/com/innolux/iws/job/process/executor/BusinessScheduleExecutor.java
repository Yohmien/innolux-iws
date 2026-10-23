/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督有限轮次固定执行器；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.job.process.executor;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import com.innolux.iws.job.process.ProcessJobExecutorNames;
import com.innolux.iws.job.process.control.SupervisorControlService;
import com.innolux.iws.process.api.ProcessSchedulerPort;
import com.innolux.iws.process.api.SupervisorTickResult;

/**
 * 推进一个监督实例的有限轮次，不执行业务事件。
 */
@JobExecutor(name = ProcessJobExecutorNames.BUSINESS_SCHEDULE)
public class BusinessScheduleExecutor {

    private final ProcessSchedulerPort schedulerPort;

    private final SupervisorControlService controlService;

    /**
     * 构造执行器。
     *
     * @param schedulerPort process 调度端口
     * @param controlService 监督控制服务
     */
    public BusinessScheduleExecutor(ProcessSchedulerPort schedulerPort, SupervisorControlService controlService) {
        this.schedulerPort = schedulerPort;
        this.controlService = controlService;
    }

    /**
     * 执行监督轮次。
     *
     * @param jobArgs SnailJob 参数
     * @return 执行结果
     */
    public ExecuteResult jobExecute(JobArgs jobArgs) {
        try {
            SupervisorJobParameters parameters = SupervisorJobParameters.from(jobArgs.getJobParams());
            SupervisorTickResult result = schedulerPort.tickSupervisor(parameters.supervisorInstanceId(),
                parameters.batchLimit(), parameters.maxDurationMs());
            controlService.synchronize(parameters.supervisorInstanceId());
            SnailJobLog.REMOTE.info("计划监督轮次完成: supervisorInstanceId={}, roundNo={}, status={}",
                result.supervisorInstanceId(), result.roundNo(), result.roundStatus());
            return ExecuteResult.success(result);
        } catch (RuntimeException ex) {
            SnailJobLog.REMOTE.error("计划监督轮次失败: {}", ex.getMessage());
            return ExecuteResult.failure(null, message(ex));
        }
    }

    private static String message(RuntimeException ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
