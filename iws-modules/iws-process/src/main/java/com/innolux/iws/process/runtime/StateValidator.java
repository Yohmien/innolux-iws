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
 * 状态结构与业务证据校验器。实现只读取本库已持久事实。
 */
public interface StateValidator {

    /**
     * 能力描述。
     *
     * @return 描述
     */
    StateValidatorDescriptor describe();

    /**
     * 校验实例、版本、状态、前驱和租约结构。
     *
     * @param context 校验上下文
     * @return 校验结果
     */
    ValidationResult validateStructural(StateContext context);

    /**
     * 校验本事件已存业务证据。
     *
     * @param context 校验上下文
     * @return 校验结果
     */
    ValidationResult validateBusiness(StateContext context);
}
