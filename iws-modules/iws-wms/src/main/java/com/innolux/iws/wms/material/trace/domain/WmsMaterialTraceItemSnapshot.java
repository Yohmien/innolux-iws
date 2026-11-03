/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料 trace 持久化
 * @Version      : V1.0
 * @Description  : 表达已写入的物料事实项和全部来源快照；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.trace.domain;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 已写入物料事实项快照。
 *
 * @author YONGJIE.PENG
 */
public record WmsMaterialTraceItemSnapshot(
    long itemId,
    long traceId,
    long inventoryId,
    String stockUnitCode,
    String materialCode,
    Long productionInfoId,
    Integer productionRevision,
    JsonNode productionSnapshot,
    BigDecimal quantity,
    String unit,
    BigDecimal beforeQty,
    BigDecimal deltaQty,
    BigDecimal afterQty,
    Long physicalFromStorageId,
    String physicalFromStorageCode,
    Long physicalToStorageId,
    String physicalToStorageCode,
    Long logicalFromScopeVersionId,
    Long logicalToScopeVersionId,
    Long loadParentBeforeId,
    Long loadParentAfterId,
    Long loadRootBeforeId,
    Long loadRootAfterId,
    String lifecycleBefore,
    String lifecycleAfter,
    Long rulePackageVersionId,
    LocalDateTime recordedAt,
    Long createDept,
    Long createBy,
    LocalDateTime createTime,
    Long updateBy,
    LocalDateTime updateTime
) {
}
