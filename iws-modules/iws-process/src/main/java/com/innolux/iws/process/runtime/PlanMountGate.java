/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 业务模块在计划实例挂载前执行目录准入；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.process.runtime;

import com.innolux.iws.process.api.AttachTaskCommand;
import com.innolux.iws.process.domain.BusinessSchedule;

/** 计划挂载准入扩展点。 */
public interface PlanMountGate {

    /**
     * 当前门禁是否管理该业务类型。
     *
     * @param businessType 业务类型
     * @return 是否管理
     */
    boolean supports(String businessType);

    /**
     * 校验挂载准入；拒绝时抛出业务异常。
     *
     * @param command 挂载命令
     * @param schedule 业务调度
     */
    void requireMountAllowed(AttachTaskCommand command, BusinessSchedule schedule);
}
