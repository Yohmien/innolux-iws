/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 业务任务
 * @Version      : V1.0
 * @Description  : 创建持久业务任务并冻结完整本地动作命令；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.task.domain;

/** 业务任务创建命令。 */
public record WmsBusinessTaskCreateCommand(
    String businessType,
    long businessId,
    Long lineId,
    long allocationUnitId,
    int roundNo,
    String phase,
    String taskKey,
    String planType,
    long planInstanceId,
    String inputHash,
    String inputJson,
    Long actorId,
    Long departmentId
) {
}
