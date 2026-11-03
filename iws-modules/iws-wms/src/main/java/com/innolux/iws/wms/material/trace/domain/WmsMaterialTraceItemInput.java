/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料 trace 持久化
 * @Version      : V1.0
 * @Description  : 描述待追加的不可变物料事实项及其完整来源快照；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.trace.domain;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * 待追加物料项。主键由服务分配，调用者只提供本次批次内的局部引用。
 *
 * @param itemRef             本次批次内唯一的局部引用
 * @param inventoryId         库存单元 ID 快照
 * @param stockUnitCode       原始库存单元编码快照
 * @param materialCode        料号快照，允许为 null
 * @param productionInfoId    生产信息 ID 快照
 * @param productionRevision  生产信息修订号快照
 * @param productionSnapshot  生产属性完整快照
 * @param quantity            事实数量
 * @param unit                事实单位
 * @param beforeQty           变更前数量
 * @param deltaQty            数量增量，可为负
 * @param afterQty            变更后数量
 * @param physicalFromStorageId   物理源储位 ID 快照
 * @param physicalFromStorageCode 物理源储位编码快照
 * @param physicalToStorageId     物理目标储位 ID 快照
 * @param physicalToStorageCode   物理目标储位编码快照
 * @param logicalFromScopeVersionId 变更前逻辑归属版本 ID 快照
 * @param logicalToScopeVersionId   变更后逻辑归属版本 ID 快照
 * @param loadParentBeforeId  变更前装载父节点 ID 快照
 * @param loadParentAfterId   变更后装载父节点 ID 快照
 * @param loadRootBeforeId    变更前装载根节点 ID 快照
 * @param loadRootAfterId     变更后装载根节点 ID 快照
 * @param lifecycleBefore     变更前生命周期快照
 * @param lifecycleAfter      变更后生命周期快照
 * @param rulePackageVersionId 适用规则包版本 ID 快照
 * @author YONGJIE.PENG
 */
public record WmsMaterialTraceItemInput(
    String itemRef,
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
    Long rulePackageVersionId
) {
}
