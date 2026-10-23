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

import java.util.List;

/**
 * 一个事件的唯一 HTTP 单动作能力。
 */
public interface SingleActionOperation {

    /**
     * 本能力可提供的合同描述。
     *
     * @return 合同描述集合
     */
    List<CallContractDescriptor> describe();

    /**
     * 执行一次逻辑调用。实现不得更新事件、token 或 attempt。
     *
     * @param context 只读执行上下文
     * @return 单动作结果
     */
    ActionResult execute(ActionContext context);
}
