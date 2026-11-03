/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料 trace 持久化
 * @Version      : V1.0
 * @Description  : 表达已写入的物料 trace 不可变事实头快照；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.trace.domain;

import java.time.LocalDateTime;

/**
 * 已写入物料事实头快照。
 *
 * @author YONGJIE.PENG
 */
public record WmsMaterialTraceHistorySnapshot(
    long traceId,
    String effectKey,
    String businessType,
    String businessName,
    long businessId,
    String businessNo,
    Long businessLineId,
    String rootBusinessType,
    long rootBusinessId,
    String taskKey,
    Long planInstanceId,
    String planCode,
    String planType,
    String definitionVersion,
    Long eventId,
    Long attemptId,
    String inputHash,
    Long reverseTraceId,
    WmsMaterialTraceKind traceKind,
    String effectResult,
    String reason,
    String evidenceRef,
    Long actorId,
    String actorName,
    String initiatorId,
    String initiatorName,
    Long departmentId,
    String departmentName,
    String executionSubject,
    String executionAccount,
    String onBehalfOf,
    String sourceSystem,
    String terminalId,
    String requestId,
    String sourceTraceId,
    LocalDateTime occurredAt,
    LocalDateTime recordedAt,
    Long createDept,
    Long createBy,
    LocalDateTime createTime,
    Long updateBy,
    LocalDateTime updateTime
) {
}
