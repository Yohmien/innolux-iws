/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 持久平台控制意图发现与通用结果 CAS 窄端口
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

import java.util.List;

/**
 * 供 job 协调器发现已持久控制意图并回写平台观测结果。
 *
 * <p>意图只在数据库控制行；内存通知丢失后仍可由 pending 重放。</p>
 */
public interface ProcessControlIntentPort {

    /**
     * 查询待收敛控制意图。
     *
     * @param limit 上限
     * @return 监督与 worker 意图
     */
    List<ControlIntent> listPendingIntents(int limit);

    /**
     * 按范围查询单个意图。
     *
     * @param scopeType 范围类型
     * @param scopeKey 范围键
     * @param duty 职责
     * @return 意图，不存在返回 null
     */
    ControlIntent find(String scopeType, String scopeKey, String duty);

    /**
     * 通用 scope 结果 CAS。
     *
     * @param command 平台结果
     * @return 回写确认
     */
    ControlIntentAck recordResult(PlatformScopeResultCommand command);

    /**
     * 幂等建立并唤醒业务调度的 worker 控制行。
     *
     * @param scheduleId 业务调度 ID
     * @param definitionVersionId 提供 worker 参数的冻结定义
     */
    void ensureWorkerIntent(long scheduleId, long definitionVersionId);

    /**
     * 持久唤醒已存在的 worker 控制行。
     *
     * @param scheduleId 业务调度 ID
     */
    void wakeWorker(long scheduleId);
}
