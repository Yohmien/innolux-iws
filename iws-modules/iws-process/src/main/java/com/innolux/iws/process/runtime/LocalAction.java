/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 单动作与状态校验能力契约；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

/**
 * 一个事件的唯一本地动作能力。
 */
public interface LocalAction {

    /**
     * 本地动作描述。
     *
     * @return 描述
     */
    LocalActionDescriptor describe();

    /**
     * 执行一次本地效果。实现不得发送 HTTP 或调用第二个动作。
     *
     * @param context 只读上下文
     * @return 本地结果
     */
    ActionResult execute(ActionContext context);
}
