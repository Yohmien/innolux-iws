/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督详情
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 监督详情。
 *
 * @param summary 摘要
 * @param planCode 计划编码
 * @param planName 计划名称
 * @param definitionStatus 定义状态
 * @param definitionRevision 定义修订号
 * @param eventCount 事件总数
 * @param lastRoundNo 最近轮次
 * @param lastActivityAt 最近活动时间
 * @param recentErrors 最近错误
 */
public record SupervisorDetailVO(SupervisorSummaryVO summary, String planCode, String planName,
                                 String definitionStatus, Long definitionRevision, long eventCount,
                                 Long lastRoundNo, LocalDateTime lastActivityAt, List<String> recentErrors) {
}
