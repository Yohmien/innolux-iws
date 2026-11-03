/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 锁位短事务
 * @Version      : V1.0
 * @Description  : 一个不可拆单元的分配候选、实时账重读与锁位命令；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation.domain;

import com.innolux.iws.wms.allocation.kernel.AllocationRequest;

import java.util.List;

/** 一个不可拆分配单元的锁位命令。 */
public record WmsAllocationReserveCommand(
    WmsAllocationBusinessContext business,
    String effectKey,
    String inputHash,
    long rulePackageVersionId,
    long logicalScopeVersionId,
    String spaceSnapshotHash,
    String capacitySnapshotHash,
    List<WmsAllocationSnapshotInput> storages,
    AllocationRequest request
) {
    public WmsAllocationReserveCommand {
        storages = storages == null ? null : List.copyOf(storages);
    }
}
