/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划事件固定 SnailJob 执行器名称；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.job.process;

/**
 * 计划事件固定执行器名称。
 */
public final class ProcessJobExecutorNames {

    /**
     * 监督轮次执行器。
     */
    public static final String BUSINESS_SCHEDULE = "businessScheduleExecutor";

    /**
     * QUEUE 业务事件 worker 执行器。
     */
    public static final String PLAN_EVENT_WORKER = "planEventWorkerExecutor";

    private ProcessJobExecutorNames() {
    }

    /**
     * 判断是否为计划事件允许的固定执行器。
     *
     * @param executorName 执行器名称
     * @return 是否受支持
     */
    public static boolean isSupported(String executorName) {
        return BUSINESS_SCHEDULE.equals(executorName) || PLAN_EVENT_WORKER.equals(executorName);
    }
}
