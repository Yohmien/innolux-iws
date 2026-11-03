/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料 trace 持久化
 * @Version      : V1.0
 * @Description  : 承载物料 trace 事实项的插入和读取列；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.trace.mapper;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 物料 trace 事实项持久化行。
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsMaterialTraceItemRow {

    private Long itemId;

    private Long traceId;

    private Long inventoryId;

    private String stockUnitCode;

    private String materialCode;

    private Long productionInfoId;

    private Integer productionRevision;

    private String productionSnapshotJson;

    private BigDecimal quantity;

    private String unit;

    private BigDecimal beforeQty;

    private BigDecimal deltaQty;

    private BigDecimal afterQty;

    private Long physicalFromStorageId;

    private String physicalFromStorageCode;

    private Long physicalToStorageId;

    private String physicalToStorageCode;

    private Long logicalFromScopeVersionId;

    private Long logicalToScopeVersionId;

    private Long loadParentBeforeId;

    private Long loadParentAfterId;

    private Long loadRootBeforeId;

    private Long loadRootAfterId;

    private String lifecycleBefore;

    private String lifecycleAfter;

    private Long rulePackageVersionId;

    private LocalDateTime recordedAt;

    private Long createDept;

    private Long createBy;

    private LocalDateTime createTime;

    private Long updateBy;

    private LocalDateTime updateTime;
}
