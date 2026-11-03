/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 业务任务
 * @Version      : V1.0
 * @Description  : 创建父业务任务完成屏障并冻结预期任务键集合；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.task.domain;

import java.util.List;

/** 业务任务完成屏障创建命令。 */
public record WmsBusinessTaskBarrierCreateCommand(
    String businessType,
    long businessId,
    String barrierKey,
    List<String> expectedTaskKeys,
    Long actorId,
    Long departmentId
) {
    public WmsBusinessTaskBarrierCreateCommand {
        expectedTaskKeys = expectedTaskKeys == null ? null : List.copyOf(expectedTaskKeys);
    }
}
