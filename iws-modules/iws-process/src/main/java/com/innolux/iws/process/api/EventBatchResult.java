/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : EventBatchResult；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 业务事件批次结果。
 *
 * @param scheduleId 业务调度 ID
 * @param processed 处理数
 * @param succeeded 成功数
 * @param failed 失败数
 * @param skipped 跳过数
 * @param continueImmediately 是否仍有到期候选
 * @param durationMs 耗时毫秒
 * @param errorMessage 错误说明
 */
public record EventBatchResult(long scheduleId, int processed, int succeeded, int failed, int skipped,
                               boolean continueImmediately, long durationMs, String errorMessage) {
}
