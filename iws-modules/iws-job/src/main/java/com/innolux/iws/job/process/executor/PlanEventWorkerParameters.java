/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : QUEUE 业务事件 worker 执行参数；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.job.process.executor;

import com.innolux.iws.job.process.param.ProcessJobParameters;

import java.util.Map;

/**
 * QUEUE 业务事件 worker 任务参数。
 *
 * @param scheduleId 业务调度 ID
 * @param batchLimit 单轮事件上限
 * @param maxDurationMs 单轮时长上限毫秒
 */
record PlanEventWorkerParameters(long scheduleId, int batchLimit, long maxDurationMs) {

    /**
     * 从 SnailJob 原始参数解析。
     *
     * @param rawParams SnailJob 原始参数
     * @return worker 参数
     */
    static PlanEventWorkerParameters from(Object rawParams) {
        Map<String, Object> params = ProcessJobParameters.asMap(rawParams);
        return new PlanEventWorkerParameters(
            ProcessJobParameters.requiredPositiveLong(params, "scheduleId"),
            ProcessJobParameters.requiredPositiveInt(params, "batchLimit"),
            ProcessJobParameters.requiredPositiveLong(params, "maxDurationMs"));
    }
}
