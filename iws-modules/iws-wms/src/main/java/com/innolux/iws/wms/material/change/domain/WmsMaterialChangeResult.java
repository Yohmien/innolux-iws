/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料事实
 * @Version      : V1.0
 * @Description  : 返回已提交物料位置事实、流水和 trace 快照；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.change.domain;

import com.innolux.iws.wms.allocation.effect.domain.WmsAllocationEffectResult;
import com.innolux.iws.wms.load.change.domain.WmsLoadChangeResult;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceSnapshot;

import java.math.BigDecimal;

/**
 * 已提交物料位置事实。
 *
 * @param inventoryId 库存单元 ID
 * @param flowId 库存流水 ID
 * @param fromStorageId 原储位 ID
 * @param fromStorageCode 原储位编码
 * @param toStorageId 目标储位 ID
 * @param toStorageCode 目标储位编码
 * @param quantity 数量快照
 * @param trace 完整物料 trace 快照
 * @param loadChange 同事务装载根位置与占用结果
 * @param allocationEffect 同事务分配锁消费结果
 * @author YONGJIE.PENG
 */
public record WmsMaterialChangeResult(
    long inventoryId,
    long flowId,
    Long fromStorageId,
    String fromStorageCode,
    long toStorageId,
    String toStorageCode,
    BigDecimal quantity,
    WmsMaterialTraceSnapshot trace,
    WmsLoadChangeResult loadChange,
    WmsAllocationEffectResult allocationEffect
) {
}
