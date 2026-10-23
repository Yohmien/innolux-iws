/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : PlatformApplyResult；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 平台控制应用结果。
 *
 * @param applied 是否成功应用
 * @param observedRevision 平台已观测修订号
 * @param observedState 平台观测状态
 * @param platformJobId 平台任务 ID
 * @param message 结果说明
 */
public record PlatformApplyResult(boolean applied, long observedRevision, String observedState,
                                  String platformJobId, String message) {
}
