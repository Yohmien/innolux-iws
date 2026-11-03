/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 锁位短事务
 * @Version      : V1.0
 * @Description  : 调用方冻结的规则、保护与实体槽事实；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation.domain;

import com.innolux.iws.wms.allocation.kernel.AllocationProtection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 候选储位的非实时账输入。
 *
 * <p>启停、通道成员、容量、占用和活动锁由服务在锁内重读，不采用本对象中的副本。
 * 逐叶规则、保护与实体槽目录仍由调用方先冻结，避免在短事务内展开大对象图。</p>
 */
public record WmsAllocationSnapshotInput(
    long storageId,
    boolean allLeavesSafe,
    boolean anyLeafForbidden,
    boolean pathReachable,
    boolean compatibleMaterialPresent,
    boolean compatibleLogicalScopePresent,
    AllocationProtection protection,
    Map<String, BigDecimal> protectedRemaining,
    List<String> slotKeys,
    Set<String> occupiedSlotKeys,
    int protectedFreeSlots
) {
    public WmsAllocationSnapshotInput {
        protectedRemaining = protectedRemaining == null ? Map.of() : Map.copyOf(protectedRemaining);
        slotKeys = slotKeys == null ? List.of() : List.copyOf(slotKeys);
        occupiedSlotKeys = occupiedSlotKeys == null ? Set.of() : Set.copyOf(occupiedSlotKeys);
    }
}
