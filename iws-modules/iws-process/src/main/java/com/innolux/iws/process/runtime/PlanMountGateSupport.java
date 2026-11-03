/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 按业务类型执行适用挂载门禁；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.process.runtime;

import com.innolux.iws.process.api.AttachTaskCommand;
import com.innolux.iws.process.domain.BusinessSchedule;

import java.util.List;

/** 计划挂载门禁执行器。 */
public final class PlanMountGateSupport {

    private PlanMountGateSupport() {
    }

    /**
     * 顺序执行所有适用门禁。
     *
     * @param gates 门禁
     * @param command 挂载命令
     * @param schedule 业务调度
     */
    public static void requireAllowed(
        List<PlanMountGate> gates,
        AttachTaskCommand command,
        BusinessSchedule schedule
    ) {
        for (PlanMountGate gate : gates) {
            if (gate.supports(command.businessType())) {
                gate.requireMountAllowed(command, schedule);
            }
        }
    }
}
