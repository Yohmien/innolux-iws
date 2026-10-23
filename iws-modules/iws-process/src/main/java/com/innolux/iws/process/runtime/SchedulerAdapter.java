/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : SchedulerAdapter；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.process.api.SchedulerPlatformPort;

/**
 * job 侧 SnailJob 控制平台适配器。process 只持久控制意图，平台调用由 job 在事务外完成。
 */
public interface SchedulerAdapter extends SchedulerPlatformPort {

    /**
     * 适配器技术标识，用于审计与故障定位。
     *
     * @return 稳定适配器标识
     */
    String adapterKey();
}
