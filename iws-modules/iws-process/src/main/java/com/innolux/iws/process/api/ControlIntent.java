/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 持久平台控制意图
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

import java.time.LocalDateTime;

/**
 * 一条持久平台控制意图。
 *
 * @param scopeType 范围类型 SUPERVISOR/BUSINESS
 * @param scopeKey 范围键
 * @param duty DISPATCH/WORKER
 * @param supervisorInstanceId 监督实例 ID，worker 为 null
 * @param action 建议平台动作
 * @param desiredState 期望状态
 * @param observedState 观测状态
 * @param revision 控制修订号
 * @param epoch 代次
 * @param wakePending 是否待触发
 * @param jobSpec 完整任务规格
 * @param lastError 最近错误
 * @param lastActivityAt 最近活动时间
 */
public record ControlIntent(String scopeType, String scopeKey, String duty,
                            String supervisorInstanceId, String action, String desiredState,
                            String observedState, long revision, long epoch, boolean wakePending,
                            JobSpec jobSpec, String lastError, LocalDateTime lastActivityAt) {
}
