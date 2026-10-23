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
 * 逐系统首次确认命令。
 *
 * @param instanceId 计划实例 ID
 * @param systemCodes 明确选择的人工模式系统
 * @param expectedVersion 实例版本
 * @param commandKey 命令幂等键
 * @param reason 操作原因
 */
public record ConfirmPostingCommand(long instanceId, java.util.List<String> systemCodes, long expectedVersion,
                                    String commandKey, String reason) {
}
