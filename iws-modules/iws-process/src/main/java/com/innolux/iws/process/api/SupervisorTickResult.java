/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : SupervisorTickResult；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 监督轮次执行结果。
 *
 * @param supervisorInstanceId 监督实例 ID
 * @param roundNo 轮次号
 * @param roundStatus 轮次状态
 * @param candidateCount 候选数
 * @param selectedCount 选中数
 * @param eventCount 执行事件数
 * @param durationMs 耗时毫秒
 * @param continueImmediately 是否仍有可继续工作
 * @param errorMessage 错误说明
 */
public record SupervisorTickResult(long supervisorInstanceId, long roundNo, String roundStatus,
                                   int candidateCount, int selectedCount, int eventCount,
                                   long durationMs, boolean continueImmediately, String errorMessage) {
}
