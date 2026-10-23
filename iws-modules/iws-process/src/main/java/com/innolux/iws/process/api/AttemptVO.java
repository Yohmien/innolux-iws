/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 事件尝试摘要；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 事件尝试摘要。
 *
 * @param attemptId 尝试 ID
 * @param attemptNo 尝试号
 * @param checkpointStatus 检查点状态
 * @param sent 是否已发送
 * @param requestId 请求 ID
 * @param effectResult 效果结果
 * @param errorCode 错误码
 * @param errorMessage 错误说明
 * @param evidenceRef 证据引用
 * @param startedAt 开始时间
 * @param endedAt 结束时间
 */
public record AttemptVO(String attemptId, Integer attemptNo, String checkpointStatus, Boolean sent,
                        String requestId, String effectResult, String errorCode, String errorMessage,
                        String evidenceRef, String startedAt, String endedAt) {
}
