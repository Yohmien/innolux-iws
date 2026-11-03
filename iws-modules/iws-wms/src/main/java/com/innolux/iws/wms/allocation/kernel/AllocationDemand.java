/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配候选纯内核
 * @Version      : V1.0
 * @Description  : 一个不可拆单元的容量或实体槽需求；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.kernel;

import java.util.Map;

/** 一个不可拆分配单元的冻结需求；容量模式与实体槽模式二选一。 */
public record AllocationDemand(
    long allocationUnitId,
    Map<String, AllocationQuantity> quantities,
    int slots
) {
    public AllocationDemand {
        if (allocationUnitId <= 0 || quantities == null || slots < 0) {
            throw AllocationKernelException.failure(
                AllocationKernelException.Code.ALLOCATION_DEMAND_INVALID);
        }
        quantities = Map.copyOf(quantities);
        boolean capacityMode = !quantities.isEmpty();
        boolean slotMode = slots > 0;
        if (capacityMode == slotMode
            || quantities.entrySet().stream()
                .anyMatch(entry -> entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null)) {
            throw AllocationKernelException.failure(
                AllocationKernelException.Code.ALLOCATION_DEMAND_INVALID);
        }
    }

    public boolean capacityMode() {
        return !quantities.isEmpty();
    }
}
