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
 * 入站触发或结果回调命令。
 *
 * @param instanceId 计划实例 ID
 * @param callerSystemCode 可信来源系统编码
 * @param moduleCode 来源模块编码
 * @param callContractKey 调用合同键
 * @param callContractVersion 调用合同版本
 * @param purpose 调用用途
 * @param sourceRequestId 来源请求 ID
 * @param effectKey 逻辑效果键
 * @param payloadHash 载荷摘要
 * @param payloadSummary 脱敏载荷摘要
 * @param originOutboundEventId 结果事件来源出站事件 ID
 * @param externalTaskId 外部任务标识
 */
public record ReceiveTriggerCommand(long instanceId, String callerSystemCode, String moduleCode,
                                    String callContractKey, String callContractVersion, String purpose,
                                    String sourceRequestId, String effectKey, String payloadHash,
                                    String payloadSummary, Long originOutboundEventId, String externalTaskId) {
}
