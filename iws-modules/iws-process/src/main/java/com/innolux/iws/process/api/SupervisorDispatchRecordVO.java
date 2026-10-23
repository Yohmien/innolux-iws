/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督调度记录查询
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

import java.time.LocalDateTime;

/**
 * 监督调度记录。
 *
 * @param auditId 审计 ID
 * @param actionCode 动作编码
 * @param result 结果
 * @param supervisorInstanceId 监督实例 ID
 * @param roundNo 轮次号
 * @param childInstanceId 子计划 ID
 * @param eventId 事件 ID
 * @param attemptId 尝试 ID
 * @param commandKey 命令键
 * @param reason 原因
 * @param decisionSummary 决策摘要
 * @param slotBefore 准入前占用
 * @param slotAfter 准入后占用
 * @param epoch 租约代次
 * @param controlRevision 控制修订号
 * @param platformJobId 平台任务 ID
 * @param occurredAt 发生时间
 */
public record SupervisorDispatchRecordVO(String auditId, String actionCode, String result,
                                         String supervisorInstanceId, Long roundNo, String childInstanceId,
                                         String eventId, String attemptId, String commandKey, String reason,
                                         String decisionSummary, Integer slotBefore, Integer slotAfter,
                                         Long epoch, Long controlRevision, String platformJobId,
                                         LocalDateTime occurredAt) {
}
