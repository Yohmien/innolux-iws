/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 计划执行查询
 * @Version      : V1.0
 * @Description  : 查询内部请求方向记录安全投影；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api.query;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 查询内部请求方向记录安全投影；不包含请求或响应摘要。
 */
@Data
public class ProcessRequestRecordRow {

    private Long id;
    private String requestId;
    private String direction;
    private String purpose;
    private String sourceRequestId;
    private String correlationId;
    private String parentRequestId;
    private String peerRequestId;
    private String callerSystemCode;
    private String receiverSystemCode;
    private String moduleCode;
    private String clientIp;
    private String accessSummary;
    private String contractKey;
    private String contractVersion;
    private String effectKey;
    private Long instanceId;
    private Long eventId;
    private Long attemptId;
    private Long receiptId;
    private Integer httpStatus;
    private String resultCode;
    private String effectResult;
    private Boolean sent;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime evidenceAt;
    private LocalDateTime createTime;
    private String businessScheduleCode;
    private String planType;
    private String businessId;
    private String taskKey;
}
