/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 实例运行查询与命令 DTO；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 实例优先级命令。
 *
 * @param instanceId 实例 ID
 * @param priority 新优先级
 * @param expectedVersion 实例版本
 * @param commandKey 命令键
 * @param reason 原因
 */
public record PriorityCommand(long instanceId, int priority, long expectedVersion, String commandKey,
                              String reason) {
}
