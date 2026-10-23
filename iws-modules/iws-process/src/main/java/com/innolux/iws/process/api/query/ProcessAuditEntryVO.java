/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 计划执行查询
 * @Version      : V1.0
 * @Description  : 计划领域审计只读条目；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api.query;

/**
 * 计划领域审计只读条目。
 *
 * @param auditId 审计 ID
 * @param occurredAt 发生时间
 * @param action 动作编码
 * @param actionText 动作中文
 * @param operator 操作者
 * @param operatorType 操作者类型
 * @param result 结果
 * @param resultText 结果中文
 * @param reason 原因
 * @param beforeStatus 变更前状态
 * @param afterStatus 变更后状态
 * @param beforeRevision 变更前版本
 * @param afterRevision 变更后版本
 * @param commandKey 命令键
 * @param instanceId 实例 ID
 * @param eventId 事件 ID
 * @param attemptId 尝试 ID
 * @param requestId 请求 ID
 * @param roundNo 监督轮次
 * @param childInstanceId 子计划实例 ID
 * @param evidence 决策摘要
 */
public record ProcessAuditEntryVO(String auditId, String occurredAt, String action, String actionText,
                                  String operator, String operatorType, String result, String resultText,
                                  String reason, String beforeStatus, String afterStatus, String beforeRevision,
                                  String afterRevision, String commandKey, String instanceId, String eventId,
                                  String attemptId, String requestId, Long roundNo, String childInstanceId,
                                  String evidence) {
}
