/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料事实
 * @Version      : V1.0
 * @Description  : 读取库存位置事实所需的当前行快照；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.change.mapper;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 库存位置当前行。
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsMaterialInventoryRow {

    private Long inventoryId;

    private String stockUnitCode;

    private String materialCode;

    private Long storageId;

    private String storageCode;

    private Long zoneId;

    private String zoneCode;

    private BigDecimal inventoryQty;

    private BigDecimal reservedQty;

    private String reservedOrderNo;

    private String unit;

    private Integer version;

    private String delFlag;
}
