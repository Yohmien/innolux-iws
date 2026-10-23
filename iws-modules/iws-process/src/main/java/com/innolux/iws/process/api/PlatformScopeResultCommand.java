/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 通用平台 scope 结果回写命令
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 通用平台结果命令。
 *
 * @param scopeType 范围类型
 * @param scopeKey 范围键
 * @param duty 职责
 * @param action 已执行的 ControlIntent.action，调用方不得自造
 * @param expectedRevision 应用时控制修订号
 * @param applied 平台是否应用成功
 * @param observedState 平台观测状态
 * @param platformJobId 平台任务 ID
 * @param message 说明
 */
public record PlatformScopeResultCommand(String scopeType, String scopeKey, String duty, String action,
                                         long expectedRevision, boolean applied,
                                         String observedState, String platformJobId, String message) {
}
