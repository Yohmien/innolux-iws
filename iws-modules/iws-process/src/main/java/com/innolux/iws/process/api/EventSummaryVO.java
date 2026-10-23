/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 实例运行查询与命令 DTO；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 实例事件摘要。
 *
 * @param eventId 事件 ID
 * @param eventCode 事件编码
 * @param eventName 事件中文名
 * @param direction 方向
 * @param status 执行状态
 * @param waitReason 等待原因
 * @param effectResult 效果结果
 * @param tokenId token ID
 * @param attemptNo 尝试号
 */
public record EventSummaryVO(String eventId, String eventCode, String eventName, String direction,
                             String status, String waitReason, String effectResult, String tokenId,
                             Integer attemptNo) {
}
