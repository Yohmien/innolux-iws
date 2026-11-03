/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 为需要业务命令的本地动作提供持久冻结输入；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

/**
 * 本地动作冻结输入提供器。
 *
 * <p>实现只能读取父业务已持久、不可变的输入快照，不得根据当前页面参数重建命令、
 * 写业务事实或执行第二个动作。提供器与动作在同一 SAME_TX 事务内执行。</p>
 */
public interface LocalActionInputProvider {

    /**
     * 读取本次动作的冻结输入。
     *
     * @param context 平台基础上下文
     * @return 冻结输入
     */
    LocalActionInput provide(ActionContext context);
}
