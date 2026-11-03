/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 读取合并父单、固定批次和屏障当前持久事实；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.logical.merge.domain;

/** 逻辑合并父单快照。 */
public record WmsLogicalMergeOrderSnapshot(
    long mergeOrderId,
    String commandKey,
    long targetScopeVersionId,
    String sourceScopeVersionIdsJson,
    String compatibleSourceScopeVersionIdsJson,
    int batchSize,
    String status,
    int revision,
    Long parentPlanInstanceId,
    Long parentBusinessTaskId,
    Long barrierId,
    int expectedBatchCount
) {
}
