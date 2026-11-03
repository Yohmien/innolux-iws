/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 业务任务
 * @Version      : V1.0
 * @Description  : 按修订号推进业务任务状态；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.task.domain;

/** 业务任务状态迁移命令。 */
public record WmsBusinessTaskTransitionCommand(
    long taskId,
    int expectedRevision,
    String targetStatus,
    Long actorId,
    Long departmentId
) {
}
