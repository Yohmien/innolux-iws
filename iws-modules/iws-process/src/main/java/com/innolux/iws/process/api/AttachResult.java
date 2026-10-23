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
 * 挂载结果。
 *
 * @param instanceId 计划实例 ID
 * @param supervisorInstanceId 监督实例 ID
 * @param mode 监督模式
 * @param queueStatus 队列状态
 * @param eligible 当前是否可准入
 * @param blockReason 阻塞原因
 * @param queueRank 未准入候选序位
 * @param instanceVersion 实例版本
 */
public record AttachResult(String instanceId, String supervisorInstanceId, String mode, String queueStatus,
                           boolean eligible, String blockReason, Integer queueRank, String instanceVersion) {
}
