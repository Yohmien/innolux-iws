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

import java.util.Map;

/**
 * 已注册分支条件。
 */
public interface BranchCondition {

    /**
     * 条件描述。
     *
     * @return 描述
     */
    BranchConditionDescriptor describe();

    /**
     * 根据冻结事实判断是否命中。
     *
     * @param facts 冻结事实
     * @return 是否命中
     */
    boolean matches(Map<String, String> facts);
}
