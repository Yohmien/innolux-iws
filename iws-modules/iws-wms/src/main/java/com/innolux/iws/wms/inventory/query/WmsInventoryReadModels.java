/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P7 只读查询
 * @Version      : V1.0
 * @Description  : P7 查询命令、只读视图与导入预览结构；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.inventory.query;

import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceSnapshot;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * P7 只读查询模型集合。
 *
 * <p>查询对象仅接受显式白名单字段，所有分页由服务端收紧到可配置上限。</p>
 */
public final class WmsInventoryReadModels {

    private WmsInventoryReadModels() {
    }

    @Data
    @EqualsAndHashCode(callSuper = false)
    public static class PageQuery {

        private int pageNum = 1;

        private int pageSize = WmsInventoryReadContract.DEFAULT_PAGE_SIZE;

        public int normalizedPageNum() {
            return Math.max(1, pageNum);
        }

        public int normalizedPageSize() {
            return Math.min(
                WmsInventoryReadContract.MAX_PAGE_SIZE,
                Math.max(1, pageSize)
            );
        }

        public int offset() {
            return (normalizedPageNum() - 1) * normalizedPageSize();
        }
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class MaterialQuery extends PageQuery {

        private String materialCode;

        private String stockUnitCode;

        private String warehouseCode;

        private String storageCode;

        private Integer status;

        private Integer inboundStatus;

        private Integer frozenStatus;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class TraceQuery extends PageQuery {

        private Long inventoryId;

        private String stockUnitCode;

        private String materialCode;

        private String businessType;

        private Long businessId;

        private String taskKey;

        private Long planInstanceId;

        private String traceKind;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class BusinessTaskQuery extends PageQuery {

        private String businessType;

        private Long businessId;

        private String taskKey;

        private String planType;

        private String status;

        private String instanceStatus;

        private String latestEffectResult;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class RuleDefinitionQuery extends PageQuery {

        private String ruleKey;

        private String purpose;

        private String scopeType;

        private String status;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class RulePackageQuery extends PageQuery {

        private String packageCode;

        private String status;
    }

    @Data
    public static class RulePreviewCommand {

        private String purpose;

        private String scopeType;

        private JsonNode condition;

        private JsonNode result;
    }

    @Data
    public static class LocateQuery {

        private String code;
    }

    public record CapabilitiesView(Map<String, Boolean> allowedActions) {

        public CapabilitiesView {
            allowedActions = Map.copyOf(allowedActions);
        }
    }

    public record MaterialSummaryView(
        String materialCode,
        String materialName,
        String warehouseCode,
        String storageCode,
        String unit,
        long inventoryCount,
        BigDecimal inventoryQty,
        BigDecimal reservedQty,
        BigDecimal frozenQty,
        BigDecimal availableQty,
        long frozenCount,
        String logicalScopeVersionId,
        String logicalScopeCode,
        String logicalScopeName,
        String rulePackageVersionId,
        LocalDateTime lastUpdatedAt
    ) {
    }

    public record MaterialDetailView(
        String inventoryId,
        String stockUnitCode,
        String materialCode,
        String materialName,
        String materialBatch,
        String dateCode,
        String supplierCode,
        String supplierName,
        String plantCode,
        String warehouseCode,
        String zoneId,
        String zoneCode,
        String storageId,
        String storageCode,
        String containerId,
        String containerCode,
        String compartmentCode,
        BigDecimal inventoryQty,
        BigDecimal reservedQty,
        BigDecimal frozenQty,
        BigDecimal availableQty,
        String unit,
        Integer status,
        Integer frozenStatus,
        Integer inboundStatus,
        Integer qualityStatus,
        LocalDateTime productionDate,
        LocalDateTime receivingTime,
        LocalDateTime inboundTime,
        String reservedOrderNo,
        String sourceOrderNo,
        String targetPlace,
        Integer version,
        String logicalScopeVersionId,
        String logicalScopeCode,
        String logicalScopeName,
        String rulePackageVersionId,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        ProductionView production,
        List<AttributeView> attributes,
        StorageLocationView currentLocation
    ) {
    }

    public record ProductionView(
        String productionInfoId,
        String materialCode,
        String materialType,
        LocalDateTime productionDate,
        String materialBatch,
        String supplierCode,
        String supplierName,
        String customerCode,
        String customerName,
        BigDecimal lengthValue,
        BigDecimal widthValue,
        BigDecimal heightValue,
        String dimensionUnit,
        Integer bomLevel,
        BigDecimal bomQty,
        String bomVersion,
        String finishedMaterialCode,
        String finishedMaterialType,
        String parseVersion,
        Integer revision
    ) {
    }

    public record AttributeView(
        String attributeKey,
        int seqNo,
        String valueText,
        BigDecimal valueNumber,
        LocalDateTime valueDate,
        LocalDateTime valueDateTime,
        String unit,
        String sourceRef
    ) {
    }

    public record StorageLocationView(
        String rootNodeId,
        String locationKind,
        String storageId,
        String storageCode,
        String warehouseCode,
        String zoneCode,
        Integer revision
    ) {
    }

    public record LoadNodeView(
        String loadNodeId,
        String nodeType,
        String inventoryId,
        String stockUnitCode,
        String materialCode,
        String containerId,
        String containerCode,
        String parentNodeId,
        String rootNodeId,
        Integer depth,
        String status,
        Integer revision,
        String locationKind,
        String storageId,
        String storageCode
    ) {
    }

    public record MaterialTreeView(
        String inventoryId,
        List<LoadNodeView> ancestry,
        List<LoadNodeView> nodes
    ) {
    }

    public record MaterialDetailEnvelope(
        MaterialDetailView material,
        MaterialTreeView tree,
        List<TraceSummaryView> recentTraces
    ) {
    }

    public record TraceSummaryView(
        String traceId,
        String effectKey,
        String businessType,
        String businessName,
        String businessId,
        String businessNo,
        String businessLineId,
        String rootBusinessType,
        String rootBusinessId,
        String taskKey,
        String planInstanceId,
        String planCode,
        String planType,
        String definitionVersion,
        String eventId,
        String attemptId,
        String inputHash,
        String reverseTraceId,
        String traceKind,
        String effectResult,
        String actorName,
        String departmentName,
        String executionSubject,
        String executionAccount,
        String evidenceRef,
        LocalDateTime occurredAt,
        LocalDateTime recordedAt
    ) {
    }

    public record TraceDetailView(
        TraceSummaryView summary,
        WmsMaterialTraceSnapshot snapshot
    ) {
    }

    public record BusinessTaskView(
        String taskId,
        String businessType,
        String businessId,
        String lineId,
        String allocationUnitId,
        Integer roundNo,
        String phase,
        String taskKey,
        String planType,
        String planInstanceId,
        String inputHash,
        String status,
        Integer revision,
        String instanceStatus,
        String instanceVersion,
        String latestEventCode,
        String latestEventStatus,
        String latestEffectResult,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        LocalDateTime updatedAt
    ) {
    }

    public record RuleDefinitionView(
        String ruleDefinitionId,
        String ruleKey,
        String purpose,
        String ruleName,
        String scopeType,
        String status,
        String currentVersionId,
        Integer revision,
        long versionCount,
        long activeVersionCount,
        LocalDateTime updatedAt
    ) {
    }

    public record RuleVersionView(
        String ruleVersionId,
        String ruleDefinitionId,
        Integer versionNo,
        String status,
        String canonicalDigest,
        JsonNode condition,
        JsonNode result,
        String bindingSnapshotState,
        boolean executionReady,
        LocalDateTime publishedAt,
        String publishedBy
    ) {
    }

    public record RuleDefinitionDetailView(
        RuleDefinitionView definition,
        List<RuleVersionView> versions
    ) {
    }

    public record RulePackageView(
        String packageVersionId,
        String packageCode,
        Integer versionNo,
        String canonicalDigest,
        String status,
        LocalDateTime publishedAt,
        String publishedBy,
        long memberCount
    ) {
    }

    public record RulePackageMemberView(
        String purpose,
        String ruleVersionId,
        Integer revision,
        String ruleKey,
        String ruleName,
        String scopeType,
        String versionNo,
        String status
    ) {
    }

    public record RulePackageDetailView(
        RulePackageView packageVersion,
        List<RulePackageMemberView> members
    ) {
    }

    public record RulePreviewView(
        boolean valid,
        String canonicalDigest,
        String bindingSnapshotState,
        List<String> referencedAttributes,
        String purpose,
        String purposeVersion,
        String scopeType,
        String limitsVersion
    ) {
    }

    public record LocateItemView(
        String inventoryId,
        String stockUnitCode,
        String materialCode,
        String materialName,
        String storageCode,
        String containerCode,
        BigDecimal inventoryQty,
        String unit,
        Integer status,
        Integer frozenStatus,
        Integer inboundStatus,
        String matchType
    ) {
    }

    public record LocateView(
        String queryCode,
        String outcome,
        List<LocateItemView> items
    ) {
    }

    public record ImportPreviewRow(
        int rowNo,
        String materialCode,
        String stockUnitCode,
        BigDecimal quantity,
        String unit,
        String reason,
        List<String> errors
    ) {
    }

    public record ImportPreviewView(
        String fileHash,
        int totalRows,
        int validRows,
        int invalidRows,
        String previewRevision,
        boolean writable,
        String writeBlockedReason,
        List<String> fileErrors,
        List<ImportPreviewRow> rows
    ) {
    }
}
