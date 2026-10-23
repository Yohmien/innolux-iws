/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : PlanEventWorkerService；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.process.api.EventBatchResult;

/**
 * QUEUE 业务事件 worker 服务。IMMEDIATE 与普通后继不得经此队列认领。
 */
public interface PlanEventWorkerService {

    /**
     * 有界认领并执行到期业务事件。
     *
     * @param scheduleId 业务调度 ID
     * @param batchLimit 单轮事件上限
     * @param maxDurationMs 单轮时长上限
     * @return 批次结果
     */
    EventBatchResult runBatch(long scheduleId, int batchLimit, long maxDurationMs);
}
