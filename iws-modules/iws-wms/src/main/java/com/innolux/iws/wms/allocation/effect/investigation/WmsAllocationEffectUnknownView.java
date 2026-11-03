/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配效果查证
 * @Version      : V1.0
 * @Description  : 返回UNKNOWN分配锁及最近一次未知效果证据；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.effect.investigation;

import java.time.LocalDateTime;

/**
 * UNKNOWN 分配效果查证视图。
 *
 * @param allocationLockId 分配锁 ID
 * @param channelLockId 通道锁 ID
 * @param channelId 访问通道 ID
 * @param storageId 储位 ID
 * @param businessType 父业务类型
 * @param businessName 父业务类型名称快照
 * @param businessId 父业务 ID
 * @param businessNo 父业务号
 * @param businessLineId 父业务明细 ID
 * @param allocationUnitId 不可拆分配单元 ID
 * @param roundNo 分配轮次
 * @param taskKey 分配锁任务键
 * @param planInstanceId 分配计划实例 ID
 * @param channelStatus 通道锁状态
 * @param channelEffectResult 通道锁效果状态
 * @param channelAligned 通道锁与分配锁的 UNKNOWN 活动态是否一致
 * @param latestUnknownAuditId 最近一次 UNKNOWN 审计 ID
 * @param latestUnknownEvidenceJson 最近一次 UNKNOWN 审计证据
 * @param unknownOccurredAt 最近一次 UNKNOWN 业务时间
 * @param lockUpdatedAt 分配锁更新时间
 */
public record WmsAllocationEffectUnknownView(
    String allocationLockId,
    String channelLockId,
    String channelId,
    String storageId,
    String businessType,
    String businessName,
    String businessId,
    String businessNo,
    String businessLineId,
    String allocationUnitId,
    int roundNo,
    String taskKey,
    String planInstanceId,
    String channelStatus,
    String channelEffectResult,
    boolean channelAligned,
    String latestUnknownAuditId,
    String latestUnknownEvidenceJson,
    LocalDateTime unknownOccurredAt,
    LocalDateTime lockUpdatedAt
) {
}
