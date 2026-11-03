/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 业务任务
 * @Version      : V1.0
 * @Description  : 以修订号完成父业务任务屏障；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.task.domain;

/** 业务任务完成屏障迁移命令。 */
public record WmsBusinessTaskBarrierCompleteCommand(
    String businessType,
    long businessId,
    String barrierKey,
    int expectedRevision,
    Long actorId
) {
}
