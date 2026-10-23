/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 入站或出站 HTTP 请求方向记录。；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.innolux.iws.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import com.innolux.iws.process.enums.CallPurpose;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventDirection;
import java.time.LocalDateTime;

/**
 * 入站或出站 HTTP 请求方向记录。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iws_process_request_detail")
public class ProcessRequestDetail extends BaseEntity {

    /**
     * 主键。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 本端请求 ID。
     */
    private String requestId;

    /**
     * 请求方向。
     */
    private EventDirection direction;

    /**
     * 调用用途。
     */
    private CallPurpose purpose;

    /**
     * 来源请求 ID。
     */
    private String sourceRequestId;

    /**
     * 调用链关联 ID。
     */
    private String correlationId;

    /**
     * 父请求 ID。
     */
    private String parentRequestId;

    /**
     * 对端请求 ID。
     */
    private String peerRequestId;

    /**
     * 调用方系统。
     */
    private String callerSystemCode;

    /**
     * 接收方系统。
     */
    private String receiverSystemCode;

    /**
     * 模块编码。
     */
    private String moduleCode;

    /**
     * 接口合同键。
     */
    private String contractKey;

    /**
     * 接口合同版本。
     */
    private String contractVersion;

    /**
     * 逻辑效果键。
     */
    private String effectKey;

    /**
     * 计划实例 ID。
     */
    private Long instanceId;

    /**
     * 所属事件 ID。
     */
    private Long eventId;

    /**
     * 所属尝试 ID。
     */
    private Long attemptId;

    /**
     * 入站回执 ID。
     */
    private Long receiptId;

    /**
     * HTTP 状态。
     */
    private Integer httpStatus;

    /**
     * 业务结果码。
     */
    private String resultCode;

    /**
     * 效果判定。
     */
    private EffectResult effectResult;

    /**
     * 是否真实发送。
     */
    private Boolean sent;

    /**
     * 脱敏请求摘要。
     */
    private String requestSummary;

    /**
     * 脱敏响应摘要。
     */
    private String responseSummary;

    /**
     * 开始时间。
     */
    private LocalDateTime startedAt;

    /**
     * 结束时间。
     */
    private LocalDateTime endedAt;

    /**
     * 证据时间。
     */
    private LocalDateTime evidenceAt;

}
