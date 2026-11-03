/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配候选纯内核
 * @Version      : V1.0
 * @Description  : 候选储位的规则、路径、容量与保护冻结快照；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.kernel;

import java.util.Map;

/**
 * 候选储位的不可变冻结快照。
 *
 * <p>{@code allLeavesSafe}、{@code anyLeafForbidden} 和 {@code pathReachable}
 * 必须由调用方完成逐叶规则、显式阻挡边和通道成员模拟后聚合；内核不按地址文本推断
 * 深度，也不把未提供的路径当成可达。{@code addressText} 只用于解释，不参与排序。</p>
 *
 * <p>{@code accessOrder} 是显式通道成员顺序；缺失时硬拒绝。该值用于调用方模拟
 * 深位路径，不按地址文本推导，也不作为最终候选排序键。</p>
 */
public record AllocationStorageSnapshot(
    long storageId,
    boolean enabled,
    boolean allLeavesSafe,
    boolean anyLeafForbidden,
    boolean pathReachable,
    Long channelId,
    Integer accessOrder,
    String addressText,
    boolean compatibleMaterialPresent,
    boolean compatibleLogicalScopePresent,
    AllocationProtection protection,
    Map<String, AllocationCapacityFact> capacities,
    AllocationSlotFact slots
) {
    public AllocationStorageSnapshot {
        if (storageId <= 0 || (channelId != null && channelId <= 0)
            || (accessOrder != null && accessOrder < 0)
            || protection == null || capacities == null) {
            throw AllocationKernelException.failure(
                AllocationKernelException.Code.ALLOCATION_STORAGE_INVALID);
        }
        capacities = Map.copyOf(capacities);
        if (capacities.entrySet().stream()
            .anyMatch(entry -> entry.getKey() == null || entry.getKey().isBlank()
                || entry.getValue() == null)) {
            throw AllocationKernelException.failure(
                AllocationKernelException.Code.ALLOCATION_STORAGE_INVALID);
        }
    }
}
