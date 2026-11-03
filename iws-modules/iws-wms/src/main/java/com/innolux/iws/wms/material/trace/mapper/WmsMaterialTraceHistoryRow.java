/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料 trace 持久化
 * @Version      : V1.0
 * @Description  : 承载物料 trace 事实头的插入和读取列；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.trace.mapper;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 物料 trace 事实头持久化行。
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsMaterialTraceHistoryRow {

    private Long traceId;

    private String effectKey;

    private String businessType;

    private String businessName;

    private Long businessId;

    private String businessNo;

    private Long businessLineId;

    private String rootBusinessType;

    private Long rootBusinessId;

    private String taskKey;

    private Long planInstanceId;

    private String planCode;

    private String planType;

    private String definitionVersion;

    private Long eventId;

    private Long attemptId;

    private String inputHash;

    private Long reverseTraceId;

    private String traceKind;

    private String effectResult;

    private String reason;

    private String evidenceRef;

    private Long actorId;

    private String actorName;

    private String initiatorId;

    private String initiatorName;

    private Long departmentId;

    private String departmentName;

    private String executionSubject;

    private String executionAccount;

    private String onBehalfOf;

    private String sourceSystem;

    private String terminalId;

    private String requestId;

    private String sourceTraceId;

    private LocalDateTime occurredAt;

    private LocalDateTime recordedAt;

    private Long createDept;

    private Long createBy;

    private LocalDateTime createTime;

    private Long updateBy;

    private LocalDateTime updateTime;
}
