/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 业务任务
 * @Version      : V1.0
 * @Description  : 持久校验预期任务集合全部完成后才推进父业务屏障；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.task.service;

import com.innolux.iws.wms.task.domain.WmsBusinessTaskBarrierCompleteCommand;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskBarrierCreateCommand;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskBarrierSnapshot;

/** 业务任务完成屏障服务。 */
public interface WmsBusinessTaskBarrierService {

    String ERROR_BARRIER_BLOCKED = "BUSINESS_TASK_BARRIER_BLOCKED";

    String ERROR_BARRIER_CONFLICT = "BUSINESS_TASK_BARRIER_CONFLICT";

    /** 创建或按稳定屏障键回放。 */
    WmsBusinessTaskBarrierSnapshot createOrReplay(WmsBusinessTaskBarrierCreateCommand command);

    /** 仅在全部预期任务完成后推进为 COMPLETED。 */
    WmsBusinessTaskBarrierSnapshot complete(WmsBusinessTaskBarrierCompleteCommand command);

    /** 按业务身份读取屏障。 */
    WmsBusinessTaskBarrierSnapshot get(String businessType, long businessId, String barrierKey);
}
