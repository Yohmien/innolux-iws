/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P7 只读查询
 * @Version      : V1.0
 * @Description  : WMS 只读 Mapper 行模型；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.inventory.query;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * WMS 只读查询行模型集合。
 */
public final class WmsInventoryReadRows {

    private WmsInventoryReadRows() {
    }

    @Data
    public static class MaterialSummaryRow {
        private String materialCode;
        private String materialName;
        private String warehouseCode;
        private String storageCode;
        private String unit;
        private Long inventoryCount;
        private BigDecimal inventoryQty;
        private BigDecimal reservedQty;
        private BigDecimal frozenQty;
        private BigDecimal availableQty;
        private Long frozenCount;
        private Long logicalScopeVersionId;
        private String logicalScopeCode;
        private String logicalScopeName;
        private Long rulePackageVersionId;
        private LocalDateTime lastUpdatedAt;
    }

    @Data
    public static class MaterialDetailRow {
        private Long inventoryId;
        private String stockUnitCode;
        private String materialCode;
        private String materialName;
        private String materialBatch;
        private String dateCode;
        private String supplierCode;
        private String supplierName;
        private String plantCode;
        private String warehouseCode;
        private Long zoneId;
        private String zoneCode;
        private Long storageId;
        private String storageCode;
        private Long containerId;
        private String containerCode;
        private String compartmentCode;
        private BigDecimal inventoryQty;
        private BigDecimal reservedQty;
        private BigDecimal frozenQty;
        private BigDecimal availableQty;
        private String unit;
        private Integer status;
        private Integer frozenStatus;
        private Integer inboundStatus;
        private Integer qualityStatus;
        private LocalDate productionDate;
        private LocalDateTime receivingTime;
        private LocalDateTime inboundTime;
        private String reservedOrderNo;
        private String sourceOrderNo;
        private String targetPlace;
        private Integer version;
        private Long logicalScopeVersionId;
        private String logicalScopeCode;
        private String logicalScopeName;
        private Long rulePackageVersionId;
        private LocalDateTime createTime;
        private LocalDateTime updateTime;
    }

    @Data
    public static class ProductionRow {
        private Long productionInfoId;
        private String materialCode;
        private String materialType;
        private LocalDate productionDate;
        private String materialBatch;
        private String supplierCode;
        private String supplierName;
        private String customerCode;
        private String customerName;
        private BigDecimal lengthValue;
        private BigDecimal widthValue;
        private BigDecimal heightValue;
        private String dimensionUnit;
        private Integer bomLevel;
        private BigDecimal bomQty;
        private String bomVersion;
        private String finishedMaterialCode;
        private String finishedMaterialType;
        private String parseVersion;
        private Integer revision;
    }

    @Data
    public static class AttributeRow {
        private String attributeKey;
        private Integer seqNo;
        private String valueText;
        private BigDecimal valueNumber;
        private LocalDate valueDate;
        private LocalDateTime valueDateTime;
        private String unit;
        private String sourceRef;
    }

    @Data
    public static class LoadNodeRow {
        private Long loadNodeId;
        private String nodeType;
        private Long inventoryId;
        private String stockUnitCode;
        private String materialCode;
        private Long containerId;
        private String containerCode;
        private Long parentNodeId;
        private Long rootNodeId;
        private Integer depth;
        private String status;
        private Integer revision;
        private String locationKind;
        private Long storageId;
        private String storageCode;
    }

    @Data
    public static class StorageLocationRow {
        private Long rootNodeId;
        private String locationKind;
        private Long storageId;
        private String storageCode;
        private String warehouseCode;
        private String zoneCode;
        private Integer revision;
    }

    @Data
    public static class TraceSummaryRow {
        private Long traceId;
        private String effectKey;
        private String businessType;
        private String businessName;
        private Long businessId;
        private String businessNo;
        private Long businessLineId;
        private String rootBusinessType;
        private Long rootBusinessId;
        private String taskKey;
        private Long planInstanceId;
        private String planCode;
        private String planType;
        private String definitionVersion;
        private Long eventId;
        private Long attemptId;
        private String inputHash;
        private Long reverseTraceId;
        private String traceKind;
        private String effectResult;
        private String actorName;
        private String departmentName;
        private String executionSubject;
        private String executionAccount;
        private String evidenceRef;
        private LocalDateTime occurredAt;
        private LocalDateTime recordedAt;
    }

    @Data
    public static class BusinessTaskRow {
        private Long taskId;
        private String businessType;
        private Long businessId;
        private Long lineId;
        private Long allocationUnitId;
        private Integer roundNo;
        private String phase;
        private String taskKey;
        private String planType;
        private Long planInstanceId;
        private String inputHash;
        private String status;
        private Integer revision;
        private String instanceStatus;
        private Long instanceVersion;
        private String latestEventCode;
        private String latestEventStatus;
        private String latestEffectResult;
        private LocalDateTime startedAt;
        private LocalDateTime endedAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class RuleDefinitionRow {
        private Long ruleDefinitionId;
        private String ruleKey;
        private String purpose;
        private String ruleName;
        private String scopeType;
        private String status;
        private Long currentVersionId;
        private Integer revision;
        private Long versionCount;
        private Long activeVersionCount;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class RuleVersionRow {
        private Long ruleVersionId;
        private Long ruleDefinitionId;
        private Integer versionNo;
        private String conditionJson;
        private String resultJson;
        private String attributeBindingsJson;
        private String canonicalDigest;
        private String status;
        private LocalDateTime publishedAt;
        private Long publishedBy;
    }

    @Data
    public static class RulePackageRow {
        private Long packageVersionId;
        private String packageCode;
        private Integer versionNo;
        private String canonicalDigest;
        private String status;
        private LocalDateTime publishedAt;
        private Long publishedBy;
        private Long memberCount;
    }

    @Data
    public static class RulePackageMemberRow {
        private String purpose;
        private Long ruleVersionId;
        private Integer revision;
        private String ruleKey;
        private String ruleName;
        private String scopeType;
        private Integer versionNo;
        private String status;
    }

    @Data
    public static class LocateRow {
        private Long inventoryId;
        private String stockUnitCode;
        private String materialCode;
        private String materialName;
        private String storageCode;
        private String containerCode;
        private BigDecimal inventoryQty;
        private String unit;
        private Integer status;
        private Integer frozenStatus;
        private Integer inboundStatus;
        private String matchType;
    }
}
