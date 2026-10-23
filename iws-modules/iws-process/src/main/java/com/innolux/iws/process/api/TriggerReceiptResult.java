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
 * 入站回执结果。
 *
 * @param receiptId 回执 ID
 * @param eventId 唯一关联事件 ID
 * @param accepted 是否受理
 * @param duplicate 是否重复投递
 * @param processingStatus 处理状态
 */
public record TriggerReceiptResult(String receiptId, String eventId, boolean accepted, boolean duplicate,
                                   String processingStatus) {
}
