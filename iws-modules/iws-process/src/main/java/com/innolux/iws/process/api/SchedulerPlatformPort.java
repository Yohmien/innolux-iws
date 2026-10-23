/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : SchedulerPlatformPort；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * process 调用 job 控制平台适配器的窄端口。
 */
public interface SchedulerPlatformPort {

    /**
     * 幂等应用一次控制意图。
     *
     * @param intent 控制意图
     * @return 平台应用结果
     */
    PlatformApplyResult apply(PlatformControlIntent intent);
}
