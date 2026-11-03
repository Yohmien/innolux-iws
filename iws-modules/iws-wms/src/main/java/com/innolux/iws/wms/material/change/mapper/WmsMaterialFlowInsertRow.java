/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料事实
 * @Version      : V1.0
 * @Description  : 描述一次库存位置 MOVE 流水的全部持久字段；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.change.mapper;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存位置流水插入行。
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsMaterialFlowInsertRow {

    private Long flowId;

    private String flowNo;

    private String flowType;

    private Long inventoryId;

    private String stockUnitCode;

    private String materialCode;

    private String materialBatch;

    private String fromStorageCode;

    private String toStorageCode;

    private BigDecimal beforeQty;

    private BigDecimal changeQty;

    private BigDecimal afterQty;

    private String sourceOrderType;

    private String sourceOrderNo;

    private Long sourceOrderLineId;

    private String idempotencyKey;

    private Long operatorId;

    private LocalDateTime occurredAt;

    private LocalDateTime createTime;

    private String remark;
}
