/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督版本切换请求
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 监督版本切换。
 *
 * @param expectedVersion 当前控制修订号
 * @param commandKey 幂等命令键
 * @param reason 原因
 * @param definitionVersionId 目标监督定义版本
 */
public record SupervisorActivateVersionRequest(long expectedVersion, String commandKey, String reason,
                                               long definitionVersionId) {
}
