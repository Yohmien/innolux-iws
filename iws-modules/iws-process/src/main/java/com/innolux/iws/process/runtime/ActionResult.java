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

import com.innolux.iws.process.enums.EffectResult;

/**
 * 单动作结果。
 *
 * @param effectResult 效果结果
 * @param evidenceRef 证据引用
 * @param requestId 实际请求 ID
 * @param externalTaskId 外部任务 ID
 * @param httpStatus HTTP 状态
 * @param responseSummary 脱敏响应摘要
 * @param checkpointJson 检查点 JSON
 * @param message 结果说明
 */
public record ActionResult(EffectResult effectResult, String evidenceRef, String requestId,
                           String externalTaskId, Integer httpStatus, String responseSummary,
                           String checkpointJson, String message) {

    /**
     * 构建成功结果。
     *
     * @param evidenceRef 证据引用
     * @return 成功结果
     */
    public static ActionResult succeeded(String evidenceRef) {
        return new ActionResult(EffectResult.SUCCEEDED, evidenceRef, null, null, null, null, null, "成功");
    }

    /**
     * 构建结果未知。
     *
     * @param message 说明
     * @return 未知结果
     */
    public static ActionResult unknown(String message) {
        return new ActionResult(EffectResult.UNKNOWN, null, null, null, null, null, null, message);
    }
}
