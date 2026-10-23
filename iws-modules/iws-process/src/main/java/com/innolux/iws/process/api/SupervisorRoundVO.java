/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督轮次查询
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

import java.time.LocalDateTime;

/**
 * 监督轮次记录。
 *
 * @param roundNo 轮次号
 * @param status 轮次状态
 * @param startedAt 开始时间
 * @param endedAt 结束时间
 * @param eventCount 事件数
 * @param successCount 成功数
 * @param failedCount 失败数
 * @param candidateCount 候选数
 * @param selectedCount 选中数
 * @param decisionReason 决策原因
 * @param definitionVersionId 冻结定义版本
 * @param errorMessage 错误
 */
public record SupervisorRoundVO(long roundNo, String status, LocalDateTime startedAt, LocalDateTime endedAt,
                               int eventCount, int successCount, int failedCount, int candidateCount,
                               int selectedCount, String decisionReason, String definitionVersionId,
                               String errorMessage) {
}
