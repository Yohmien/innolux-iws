/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : job 平台结果回写命令；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * job 完成平台 apply 后向 process 回写观测结果。
 *
 * @param supervisorInstanceId 监督实例 ID
 * @param expectedControlRevision apply 时读取的控制修订号
 * @param applied 平台是否成功应用
 * @param observedState 平台观测状态
 * @param platformJobId 平台任务 ID
 * @param message 结果说明
 */
public record PlatformResultCommand(long supervisorInstanceId, long expectedControlRevision, boolean applied,
                                    String observedState, String platformJobId, String message) {
}
