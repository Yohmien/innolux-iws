/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 返回父单、屏障、计划和固定批次身份；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.logical.merge.domain;

import java.util.List;

/** 逻辑合并受理结果。 */
public record WmsLogicalMergeStartResult(
    long mergeOrderId,
    long parentPlanInstanceId,
    long parentBusinessTaskId,
    long barrierId,
    String inputHash,
    List<Batch> batches
) {
    public WmsLogicalMergeStartResult {
        batches = List.copyOf(batches);
    }

    /** 固定批次身份。 */
    public record Batch(
        long batchId,
        int batchNo,
        String taskKey,
        long planInstanceId,
        long businessTaskId
    ) {
    }
}
