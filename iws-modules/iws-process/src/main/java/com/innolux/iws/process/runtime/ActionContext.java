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

import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.PostingMode;
import java.util.Map;

/**
 * 单动作执行只读上下文。
 *
 * @param eventId 事件 ID
 * @param instanceId 实例 ID
 * @param eventKey 事件键
 * @param attemptId 尝试 ID
 * @param requestId 请求 ID
 * @param direction 方向
 * @param postingMode 过账模式
 * @param effectKey 逻辑效果键
 * @param localEffectKey 本地效果键
 * @param idempotencyKey 幂等键
 * @param inputHash 输入摘要
 * @param payloadJson 冻结输入摘要
 * @param evidenceRef 已存证据引用
 * @param leaseEpoch 执行代次
 * @param callTimeoutMs 本次调用预算毫秒
 * @param facts 附加只读事实
 */
public record ActionContext(long eventId, long instanceId, String eventKey, long attemptId, String requestId,
                            EventDirection direction, PostingMode postingMode, String effectKey,
                            String localEffectKey, String idempotencyKey, String inputHash, String payloadJson,
                            String evidenceRef, long leaseEpoch, long callTimeoutMs, Map<String, String> facts) {

    /**
     * 固化附加事实。
     */
    public ActionContext {
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }
}
