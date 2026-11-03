/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 锁位短事务
 * @Version      : V1.0
 * @Description  : 已提交或回读的分配决策、选中候选与锁位结果；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation.domain;

import java.util.List;

/** 分配锁位持久结果。 */
public record WmsAllocationReservationResult(
    long decisionId,
    String result,
    Long selectedStorageId,
    Long selectedCandidateId,
    List<Long> channelLockIds,
    List<Long> allocationLockIds,
    String checkpointJson
) {
    public WmsAllocationReservationResult {
        channelLockIds = List.copyOf(channelLockIds);
        allocationLockIds = List.copyOf(allocationLockIds);
    }
}
