/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配效果代次
 * @Version      : V1.0
 * @Description  : 返回效果推进后的锁代次和计划checkpoint；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.effect.domain;

import java.util.List;

/**
 * 分配锁效果推进结果。
 *
 * @param targetEffectResult 已提交目标效果
 * @param allocationLockIds 分配锁 ID
 * @param generations 推进后逐锁 generation
 * @param checkpointJson 计划 checkpoint
 */
public record WmsAllocationEffectResult(
    String targetEffectResult,
    List<Long> allocationLockIds,
    List<Integer> generations,
    String checkpointJson
) {
    public WmsAllocationEffectResult {
        allocationLockIds = List.copyOf(allocationLockIds);
        generations = List.copyOf(generations);
    }
}
