/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料事实
 * @Version      : V1.0
 * @Description  : 读取按效果键幂等的库存流水事实；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.change.mapper;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 物料位置库存流水行。
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsMaterialFlowRow {

    private Long flowId;

    private String idempotencyKey;

    private Long inventoryId;

    private String fromStorageCode;

    private String toStorageCode;

    private LocalDateTime occurredAt;
}
