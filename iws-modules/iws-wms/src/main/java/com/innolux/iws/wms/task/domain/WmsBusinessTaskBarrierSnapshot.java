/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 业务任务
 * @Version      : V1.0
 * @Description  : 返回业务任务完成屏障的冻结任务集合和当前状态；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.task.domain;

import java.util.List;

/** 业务任务完成屏障快照。 */
public record WmsBusinessTaskBarrierSnapshot(
    long barrierId,
    String businessType,
    long businessId,
    String barrierKey,
    List<String> expectedTaskKeys,
    String status,
    int revision
) {
    public WmsBusinessTaskBarrierSnapshot {
        expectedTaskKeys = List.copyOf(expectedTaskKeys);
    }
}
