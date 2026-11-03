/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P2 空间生命周期
 * @Version      : V1.0
 * @Description  : 返回空间变更持久单、业务任务和计划实例身份；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.space.change.domain;

/** 空间变更受理结果。 */
public record WmsSpaceChangeStartResult(
    long changeOrderId,
    long planInstanceId,
    long businessTaskId,
    String inputHash,
    String queueStatus
) {
}
