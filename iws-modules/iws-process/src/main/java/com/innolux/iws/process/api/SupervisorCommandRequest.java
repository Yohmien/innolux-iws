/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督控制命令请求
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 监督控制命令。
 *
 * @param expectedVersion 当前控制修订号
 * @param commandKey 幂等命令键
 * @param reason 原因
 */
public record SupervisorCommandRequest(long expectedVersion, String commandKey, String reason) {
}
