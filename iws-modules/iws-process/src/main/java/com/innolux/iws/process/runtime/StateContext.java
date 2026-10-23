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

import com.innolux.iws.process.enums.PostingMode;
import java.util.Map;

/**
 * 状态校验只读上下文。
 *
 * @param instanceId 实例 ID
 * @param tokenId token ID
 * @param eventId 事件 ID
 * @param sourceStateCode 源状态
 * @param targetStateCode 目标状态
 * @param postingMode 过账模式
 * @param inputHash 输入摘要
 * @param evidenceSummary 已存证据摘要
 * @param facts 附加事实
 */
public record StateContext(long instanceId, Long tokenId, long eventId, String sourceStateCode,
                           String targetStateCode, PostingMode postingMode, String inputHash,
                           String evidenceSummary, Map<String, String> facts) {

    /**
     * 固化附加事实。
     */
    public StateContext {
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }
}
