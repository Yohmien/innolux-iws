/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 业务任务
 * @Version      : V1.0
 * @Description  : 创建、回放和推进业务任务；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.task.service;

import com.innolux.iws.wms.task.domain.WmsBusinessTaskCreateCommand;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskSnapshot;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskTransitionCommand;

/** 业务任务持久化服务。 */
public interface WmsBusinessTaskService {

    /** 创建或按稳定业务键回放任务。 */
    WmsBusinessTaskSnapshot createOrReplay(WmsBusinessTaskCreateCommand command);

    /** 按修订号推进任务状态。 */
    WmsBusinessTaskSnapshot transition(WmsBusinessTaskTransitionCommand command);

    /** 按任务 ID 读取快照。 */
    WmsBusinessTaskSnapshot get(long taskId);
}
