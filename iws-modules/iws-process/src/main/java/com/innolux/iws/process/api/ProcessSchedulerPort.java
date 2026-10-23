/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : ProcessSchedulerPort；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * job 调用 process 运行层的唯一调度端口。
 *
 * <p>IMMEDIATE 不进入事件批次；显式 RECOVERY_ONLY 由 {@link #runEventBatch} 单独筛选。</p>
 */
public interface ProcessSchedulerPort {

    /**
     * 推进一个监督实例的有限轮次。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @param batchLimit 单轮事件上限
     * @param maxDurationMs 单轮时长上限毫秒
     * @return 轮次结果
     */
    SupervisorTickResult tickSupervisor(long supervisorInstanceId, int batchLimit, long maxDurationMs);

    /**
     * 有界执行业务事件。
     *
     * @param scheduleId 业务调度 ID
     * @param batchLimit 单轮事件上限
     * @param maxDurationMs 单轮时长上限毫秒
     * @return 批次结果
     */
    EventBatchResult runEventBatch(long scheduleId, int batchLimit, long maxDurationMs);

    /**
     * 读取并刷新监督控制意图。平台调用由 job 在事务外完成。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @return 当前控制意图快照
     */
    ControlIntentSnapshot flushControlIntent(long supervisorInstanceId);

    /**
     * job 完成平台调用后 CAS 回写观测结果；flush 本身不代表平台已应用。
     *
     * @param command 平台结果
     * @return 回写确认
     */
    PlatformResultAck recordPlatformResult(PlatformResultCommand command);
}
