/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : PlatformControlIntent；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 平台控制意图。
 *
 * @param scopeType 控制范围
 * @param scopeKey 范围键
 * @param action ENSURE_RUNNING/ENSURE_STOPPED/TRIGGER_ONCE
 * @param desiredRevision 期望控制修订号
 * @param jobSpec 完整最小任务规格
 */
public record PlatformControlIntent(String scopeType, String scopeKey, String action, long desiredRevision,
                                    JobSpec jobSpec) {
}
