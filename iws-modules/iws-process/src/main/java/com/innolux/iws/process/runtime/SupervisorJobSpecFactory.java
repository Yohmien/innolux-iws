/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督与 QUEUE worker 完整任务规格
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.process.api.JobSpec;
import com.innolux.iws.process.domain.BusinessSchedule;
import com.innolux.iws.process.domain.BusinessSchedulePlanType;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessInstance;

/**
 * 从冻结定义和稳定身份构造可幂等创建的平台任务规格。
 */
public final class SupervisorJobSpecFactory {

    /** 监督轮次固定执行器。 */
    public static final String EXECUTOR_SUPERVISOR = "businessScheduleExecutor";

    /** QUEUE 业务事件 worker 固定执行器。 */
    public static final String EXECUTOR_WORKER = "planEventWorkerExecutor";

    private SupervisorJobSpecFactory() {
    }

    /**
     * 监督任务规格。
     *
     * @param supervisor 监督实例
     * @param definition 监督冻结定义
     * @param schedule 业务调度
     * @param planType 计划类型
     * @return 规格
     */
    public static JobSpec supervisor(ProcessInstance supervisor, PlanDefinition definition,
                                     BusinessSchedule schedule, BusinessSchedulePlanType planType) {
        String businessName = schedule == null ? supervisor.getBusinessScheduleCode() : schedule.getBusinessName();
        String typeName = planType == null || planType.getPlanTypeName() == null
            ? supervisor.getPlanType() : planType.getPlanTypeName();
        String scopeName = "DEFAULT".equals(supervisor.getScheduleScope()) ? "默认范围" : supervisor.getScheduleScope();
        String jobName = "计划监督－" + businessName + "－" + typeName + "－" + scopeName;
        String scheduleType = definition.getScheduleCron() != null && !definition.getScheduleCron().isBlank()
            ? "CRON" : "FIXED_RATE";
        Long interval = definition.getScheduleIntervalMs() != null ? definition.getScheduleIntervalMs()
            : definition.getRoundMaxDurationMs();
        Long timeout = definition.getJobTimeoutMs() != null ? definition.getJobTimeoutMs()
            : definition.getRoundMaxDurationMs();
        String params = "{\"supervisorInstanceId\":" + supervisor.getId()
            + ",\"batchLimit\":" + (definition.getRoundBatchLimit() == null ? 1 : definition.getRoundBatchLimit())
            + ",\"maxDurationMs\":" + (definition.getRoundMaxDurationMs() == null ? 1000L
            : definition.getRoundMaxDurationMs()) + "}";
        return new JobSpec(EXECUTOR_SUPERVISOR, jobName, "process:supervisor:" + supervisor.getId(),
            scheduleType, "CRON".equals(scheduleType) ? definition.getScheduleCron() : null,
            interval, timeout, definition.getJobMaxRetryTimes() == null ? 0 : definition.getJobMaxRetryTimes(),
            params, "DEFAULT");
    }

    /**
     * QUEUE worker 任务规格。
     *
     * @param schedule 业务调度
     * @param definition 提供 worker 参数的冻结定义
     * @return 规格
     */
    public static JobSpec worker(BusinessSchedule schedule, PlanDefinition definition) {
        String businessName = schedule == null ? "未知业务" : schedule.getBusinessName();
        String jobName = "计划事件队列－" + businessName;
        int batchLimit = definition.getWorkerBatchLimit() == null ? 1 : definition.getWorkerBatchLimit();
        long maxDurationMs = definition.getWorkerMaxDurationMs() == null
            ? durationFallback(definition) : definition.getWorkerMaxDurationMs();
        Long interval = definition.getScheduleIntervalMs() != null && definition.getScheduleIntervalMs() > 0
            ? definition.getScheduleIntervalMs() : maxDurationMs;
        Long timeout = definition.getJobTimeoutMs() != null && definition.getJobTimeoutMs() > 0
            ? definition.getJobTimeoutMs() : maxDurationMs;
        String params = "{\"scheduleId\":" + schedule.getId()
            + ",\"batchLimit\":" + batchLimit + ",\"maxDurationMs\":" + maxDurationMs + "}";
        return new JobSpec(EXECUTOR_WORKER, jobName, "process:worker:" + schedule.getId(),
            "FIXED_RATE", null, interval, timeout, 0, params, "DEFAULT");
    }

    private static long durationFallback(PlanDefinition definition) {
        Long value = definition.getRoundMaxDurationMs();
        return value != null && value > 0 ? value : 1000L;
    }
}
