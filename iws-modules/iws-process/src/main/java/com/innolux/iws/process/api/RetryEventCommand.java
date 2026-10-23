/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划事件公开命令与结果；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 事件重试命令。
 *
 * @param eventId 事件 ID
 * @param expectedVersion 事件版本
 * @param commandKey 命令幂等键
 * @param reason 操作原因
 * @param recoveryOnly 是否仅恢复原证据、不重做业务效果
 */
public record RetryEventCommand(long eventId, long expectedVersion, String commandKey, String reason,
                                boolean recoveryOnly) {
}
