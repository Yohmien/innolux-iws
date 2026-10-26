/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 计划执行查询
 * @Version      : V1.0
 * @Description  : 出入站请求方向记录安全总览；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api.query;

/**
 * 出入站请求方向记录安全总览。
 *
 * <p>该投影包含方向、结果、计划关联及调用链标识，不包含请求或响应摘要。原报文摘要只能经
 * {@code process:event:request} 权限的事件请求入口读取。</p>
 *
 * @param requestId 本端请求 ID
 * @param direction 请求方向
 * @param directionText 方向中文
 * @param purpose 调用用途
 * @param purposeText 用途中文
 * @param sourceRequestId 来源请求 ID
 * @param correlationId 调用链标识
 * @param parentRequestId 父请求 ID
 * @param peerRequestId 对端请求 ID
 * @param callerSystemCode 调用方系统
 * @param receiverSystemCode 接收方系统
 * @param moduleCode 模块编码
 * @param clientIp 来源 IP
 * @param accessSummary 访问信息摘要
 * @param contractKey 调用合同键
 * @param contractVersion 调用合同版本
 * @param effectKey 逻辑效果键
 * @param instanceId 计划实例 ID
 * @param eventId 事件 ID
 * @param attemptId 尝试 ID
 * @param receiptId 入站回执 ID
 * @param businessScheduleCode 业务调度编码
 * @param planType 计划类型
 * @param businessId 业务 ID
 * @param taskKey 任务唯一键
 * @param httpStatus HTTP 状态
 * @param resultCode 接收方业务结果码
 * @param effectResult 效果判定
 * @param effectResultText 效果中文
 * @param sent 是否真实发送
 * @param startedAt 开始时间
 * @param endedAt 结束时间
 * @param evidenceAt 证据时间
 * @param associationStatus 计划关联状态
 * @param associationText 计划关联中文
 */
public record ProcessRequestRecordVO(String requestId, String direction, String directionText,
                                     String purpose, String purposeText, String sourceRequestId,
                                     String correlationId, String parentRequestId, String peerRequestId,
                                     String callerSystemCode, String receiverSystemCode, String moduleCode,
                                     String clientIp, String accessSummary,
                                     String contractKey, String contractVersion, String effectKey,
                                     String instanceId, String eventId, String attemptId, String receiptId,
                                     String businessScheduleCode, String planType, String businessId,
                                     String taskKey, Integer httpStatus, String resultCode,
                                     String effectResult, String effectResultText, Boolean sent,
                                     String startedAt, String endedAt, String evidenceAt,
                                     String associationStatus, String associationText) {
}
