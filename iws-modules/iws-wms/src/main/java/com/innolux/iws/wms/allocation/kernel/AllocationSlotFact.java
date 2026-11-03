/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配候选纯内核
 * @Version      : V1.0
 * @Description  : 储位实体槽、占用、锁与保护余量快照；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.kernel;

/** 储位实体槽冻结实时账；实体槽模式不借用容量数量冒充槽位。 */
public record AllocationSlotFact(
    int totalSlots,
    int occupiedSlots,
    int lockedSlots,
    int heldSlots,
    int protectedFreeSlots
) {
    public AllocationSlotFact {
        if (totalSlots < 0 || occupiedSlots < 0 || lockedSlots < 0 || heldSlots < 0
            || protectedFreeSlots < 0 || protectedFreeSlots > totalSlots) {
            throw AllocationKernelException.failure(
                AllocationKernelException.Code.ALLOCATION_SLOT_INVALID);
        }
    }

    /** 当前可分配槽位；超占时保留负值。 */
    public int available() {
        return totalSlots - occupiedSlots - lockedSlots - heldSlots;
    }

    AllocationSlotFact withLockedAdded(int amount) {
        return new AllocationSlotFact(
            totalSlots, occupiedSlots, lockedSlots + amount, heldSlots, protectedFreeSlots);
    }
}
