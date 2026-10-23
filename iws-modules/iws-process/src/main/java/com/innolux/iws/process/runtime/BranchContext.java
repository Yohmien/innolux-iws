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
 * 分支选择只读上下文。
 *
 * @param instanceId 实例 ID
 * @param decisionNodeCode 决策节点
 * @param scopeKey 决策范围
 * @param inputHash 输入摘要
 * @param facts 冻结事实
 */
public record BranchContext(long instanceId, String decisionNodeCode, String scopeKey, String inputHash,
                            Map<String, String> facts) {

    /**
     * 固化事实。
     */
    public BranchContext {
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }
}
