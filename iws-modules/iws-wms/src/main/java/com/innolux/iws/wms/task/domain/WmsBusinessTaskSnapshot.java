/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 业务任务
 * @Version      : V1.0
 * @Description  : 返回业务任务稳定身份、冻结摘要和当前状态；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.task.domain;

/** 业务任务只读快照。 */
public record WmsBusinessTaskSnapshot(
    long taskId,
    String businessType,
    long businessId,
    String taskKey,
    String planType,
    long planInstanceId,
    String inputHash,
    String inputJson,
    String status,
    int revision
) {
}
