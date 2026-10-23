/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 可信入站触发或结果回执。；
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
import com.innolux.iws.process.enums.ProcessingStatus;
import java.time.LocalDateTime;

/**
 * 可信入站触发或结果回执。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iws_process_trigger_receipt")
public class ProcessTriggerReceipt extends BaseEntity {

    /**
     * 主键。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 来源系统编码。
     */
    private String callerSystemCode;

    /**
     * 来源模块编码。
     */
    private String moduleCode;

    /**
     * 调用合同键。
     */
    private String callContractKey;

    /**
     * 调用合同版本。
     */
    private String callContractVersion;

    /**
     * 来源请求 ID。
     */
    private String sourceRequestId;

    /**
     * 载荷摘要。
     */
    private String payloadHash;

    /**
     * 逻辑效果键。
     */
    private String effectKey;

    /**
     * 逻辑调用键。
     */
    private String logicalCallKey;

    /**
     * 关联实例 ID。
     */
    private Long instanceId;

    /**
     * 唯一关联事件 ID。
     */
    private Long eventId;

    /**
     * 外部任务标识。
     */
    private String externalTaskId;

    /**
     * 结果来源出站事件 ID。
     */
    private Long originOutboundEventId;

    /**
     * 调用用途。
     */
    private CallPurpose purpose;

    /**
     * 是否 canonical 回执。
     */
    private Boolean canonical;

    /**
     * 处理状态。
     */
    private ProcessingStatus processingStatus;

    /**
     * 原 ACK 摘要。
     */
    private String ackSummary;

    /**
     * 脱敏证据摘要。
     */
    private String evidenceSummary;

    /**
     * 接收时间。
     */
    private LocalDateTime receivedAt;

    /**
     * 应用时间。
     */
    private LocalDateTime appliedAt;

}
