/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P7 UNKNOWN 查证
 * @Version      : V1.0
 * @Description  : 仍待查证的分配锁、通道锁和最近审计证据行；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.allocation.effect.investigation.mapper;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * UNKNOWN 分配效果只读行。
 */
@Data
public class WmsAllocationEffectUnknownRow {

    private Long allocationLockId;

    private Long channelLockId;

    private Long channelId;

    private Long storageId;

    private String businessType;

    private String businessName;

    private Long businessId;

    private String businessNo;

    private Long businessLineId;

    private Long allocationUnitId;

    private Integer roundNo;

    private String taskKey;

    private Long planInstanceId;

    private String channelStatus;

    private String channelEffectResult;

    private Long latestUnknownAuditId;

    private String latestUnknownEvidenceJson;

    private LocalDateTime unknownOccurredAt;

    private LocalDateTime lockUpdatedAt;
}
