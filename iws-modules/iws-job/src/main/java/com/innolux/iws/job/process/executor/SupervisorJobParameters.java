/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督轮次执行参数；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.job.process.executor;

import com.innolux.iws.job.process.param.ProcessJobParameters;

import java.util.Map;

/**
 * 监督轮次任务参数。
 *
 * @param supervisorInstanceId 监督实例 ID
 * @param batchLimit 单轮事件上限
 * @param maxDurationMs 单轮时长上限毫秒
 */
record SupervisorJobParameters(long supervisorInstanceId, int batchLimit, long maxDurationMs) {

    /**
     * 从 SnailJob 原始参数解析。
     *
     * @param rawParams SnailJob 原始参数
     * @return 监督轮次参数
     */
    static SupervisorJobParameters from(Object rawParams) {
        Map<String, Object> params = ProcessJobParameters.asMap(rawParams);
        return new SupervisorJobParameters(
            ProcessJobParameters.requiredPositiveLong(params, "supervisorInstanceId"),
            ProcessJobParameters.requiredPositiveInt(params, "batchLimit"),
            ProcessJobParameters.requiredPositiveLong(params, "maxDurationMs"));
    }
}
