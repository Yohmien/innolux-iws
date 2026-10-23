/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : ControlIntentSnapshot；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 监督控制意图快照。
 *
 * @param supervisorInstanceId 监督实例 ID
 * @param desiredState 期望状态
 * @param observedState 已观测状态
 * @param revision 控制修订号
 * @param wakePending 是否待唤醒
 * @param platformJobId 平台任务 ID
 * @param jobSpec 创建/更新任务所需规格
 * @param lastError 最近错误
 */
public record ControlIntentSnapshot(long supervisorInstanceId, String desiredState, String observedState,
                                    long revision, boolean wakePending, String platformJobId, JobSpec jobSpec,
                                    String lastError) {
}
