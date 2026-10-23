/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 发布 JobSpec 到 SnailJob 请求字段的转换与校验；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.job.process.platform;

import com.aizuda.snailjob.client.job.core.enums.AllocationAlgorithmEnum;
import com.aizuda.snailjob.client.job.core.enums.TriggerTypeEnum;
import com.innolux.iws.job.process.ProcessJobExecutorNames;
import com.innolux.iws.job.process.param.ProcessJobParameters;
import com.innolux.iws.process.api.JobSpec;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * SnailJob 任务定义，只接受已发布 JobSpec 中的配置。
 *
 * @param jobSpec 发布任务规格
 * @param triggerType SnailJob 触发类型
 * @param triggerInterval SnailJob 触发间隔，CRON 为表达式，固定频率为秒
 * @param executorTimeoutSeconds SnailJob 执行超时秒数
 * @param maxRetryTimes 平台重试次数
 * @param routeKey 路由键，空表示 SnailJob 默认
 */
public record SnailJobJobDefinition(JobSpec jobSpec, TriggerTypeEnum triggerType, String triggerInterval,
                                    Integer executorTimeoutSeconds, Integer maxRetryTimes,
                                    AllocationAlgorithmEnum routeKey) {

    private static final Pattern HAN = Pattern.compile("\\p{IsHan}");

    /**
     * 校验并转换发布任务规格。
     *
     * @param jobSpec 发布任务规格
     * @return SnailJob 任务定义
     */
    public static SnailJobJobDefinition from(JobSpec jobSpec) {
        Objects.requireNonNull(jobSpec, "缺少 JobSpec");
        requireText(jobSpec.executorName(), "executorName");
        if (!ProcessJobExecutorNames.isSupported(jobSpec.executorName())) {
            throw new IllegalArgumentException("不支持的执行器: " + jobSpec.executorName());
        }
        requireText(jobSpec.bizId(), "bizId");
        requireText(jobSpec.jobName(), "jobName");
        if (!HAN.matcher(jobSpec.jobName()).find() || jobSpec.jobName().equals(jobSpec.bizId())
            || jobSpec.jobName().equals(jobSpec.executorName())) {
            throw new IllegalArgumentException("jobName 必须是独立中文运维名称");
        }
        requireText(jobSpec.paramsJson(), "paramsJson");
        validateParams(jobSpec);

        Long timeoutMs = jobSpec.timeoutMs();
        if (timeoutMs == null || timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs 必须为正数");
        }
        int retryTimes = jobSpec.maxRetryTimes() == null ? 0 : jobSpec.maxRetryTimes();
        if (retryTimes < 0) {
            throw new IllegalArgumentException("maxRetryTimes 不能为负数");
        }

        String scheduleType = jobSpec.scheduleType();
        TriggerTypeEnum triggerType;
        String triggerInterval;
        if ("CRON".equals(scheduleType)) {
            requireText(jobSpec.cronExpression(), "cronExpression");
            triggerType = TriggerTypeEnum.CRON;
            triggerInterval = jobSpec.cronExpression();
        } else if ("FIXED_RATE".equals(scheduleType)) {
            if (jobSpec.intervalMs() == null || jobSpec.intervalMs() <= 0) {
                throw new IllegalArgumentException("FIXED_RATE 需要正数 intervalMs");
            }
            triggerType = TriggerTypeEnum.SCHEDULED_TIME;
            triggerInterval = String.valueOf(toWholeSeconds(jobSpec.intervalMs(), "intervalMs"));
        } else if ("ONE_SHOT".equals(scheduleType)) {
            throw new IllegalArgumentException("ONE_SHOT 缺少平台触发时间字段，不能安全创建任务");
        } else {
            throw new IllegalArgumentException("不支持的 scheduleType: " + scheduleType);
        }

        return new SnailJobJobDefinition(jobSpec, triggerType, triggerInterval,
            toWholeSeconds(timeoutMs, "timeoutMs"), retryTimes, routeKey(jobSpec.routeKey()));
    }

    /**
     * 业务执行器名称。
     *
     * @return 执行器名称
     */
    public String executorName() {
        return jobSpec.executorName();
    }

    /**
     * 中文运维名。
     *
     * @return 任务名称
     */
    public String jobName() {
        return jobSpec.jobName();
    }

    /**
     * 稳定技术任务 ID。
     *
     * @return bizId
     */
    public String bizId() {
        return jobSpec.bizId();
    }

    /**
     * 执行参数 JSON。
     *
     * @return 参数 JSON
     */
    public String paramsJson() {
        return jobSpec.paramsJson();
    }

    private static void validateParams(JobSpec jobSpec) {
        Map<String, Object> params = ProcessJobParameters.asMap(jobSpec.paramsJson());
        if (ProcessJobExecutorNames.BUSINESS_SCHEDULE.equals(jobSpec.executorName())) {
            ProcessJobParameters.requiredPositiveLong(params, "supervisorInstanceId");
        } else {
            ProcessJobParameters.requiredPositiveLong(params, "scheduleId");
        }
        ProcessJobParameters.requiredPositiveInt(params, "batchLimit");
        ProcessJobParameters.requiredPositiveLong(params, "maxDurationMs");
    }

    private static AllocationAlgorithmEnum routeKey(String routeKey) {
        if (routeKey == null || routeKey.isBlank() || "DEFAULT".equals(routeKey)) {
            return null;
        }
        try {
            return AllocationAlgorithmEnum.valueOf(routeKey);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("不支持的 SnailJob routeKey: " + routeKey, ex);
        }
    }

    private static int toWholeSeconds(long milliseconds, String field) {
        long seconds = Math.ceilDiv(milliseconds, 1000L);
        if (seconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " 超出 SnailJob 秒数范围");
        }
        return (int) seconds;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
