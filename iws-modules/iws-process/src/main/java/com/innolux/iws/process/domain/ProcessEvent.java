/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划事件运行事实。；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.innolux.iws.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventRole;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.ExecutionPurpose;
import com.innolux.iws.process.enums.WaitReason;
import java.time.LocalDateTime;

/**
 * 计划事件运行事实。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iws_process_event")
public class ProcessEvent extends BaseEntity {

    /**
     * 主键。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 计划实例 ID。
     */
    private Long instanceId;

    /**
     * 所属分支 token。
     */
    private Long tokenId;

    /**
     * 定义版本。
     */
    private Long definitionVersionId;

    /**
     * 事件定义 ID。
     */
    private Long eventDefinitionId;

    /**
     * 事件编码。
     */
    private String eventCode;

    /**
     * 实例内稳定事件键。
     */
    private String eventKey;

    /**
     * 监督轮次号。
     */
    private Long roundNo;

    /**
     * 分支身份。
     */
    private String branchIdentity;

    /**
     * 事件角色。
     */
    private EventRole eventRole;

    /**
     * 事件方向。
     */
    private EventDirection eventDirection;

    /**
     * 政策系统编码。
     */
    private String systemCode;

    /**
     * 调用合同键。
     */
    private String callContractKey;

    /**
     * 调用合同版本。
     */
    private String callContractVersion;

    /**
     * 本地动作键。
     */
    private String localActionKey;

    /**
     * 本地动作版本。
     */
    private String localActionVersion;

    /**
     * 逻辑效果键。
     */
    private String effectKey;

    /**
     * 本地效果键。
     */
    private String localEffectKey;

    /**
     * 源状态。
     */
    private String sourceStateCode;

    /**
     * 目标状态。
     */
    private String targetStateCode;

    /**
     * 事件执行态。
     */
    private EventStatus eventStatus;

    /**
     * 等待原因。
     */
    private WaitReason waitReason;

    /**
     * 合同结果。
     */
    private String outcome;

    /**
     * 效果判定。
     */
    private EffectResult effectResult;

    /**
     * 是否已形成真实发送。
     */
    private Boolean sent;

    /**
     * 输入是否就绪。
     */
    private Boolean payloadReady;

    /**
     * 输入摘要。
     */
    private String inputHash;

    /**
     * 业务幂等键。
     */
    private String idempotencyKey;

    /**
     * 结果事件来源出站事件 ID。
     */
    private Long originOutboundEventId;

    /**
     * 恢复查询来源事件编码。
     */
    private String recoveryForEventCode;

    /**
     * 执行用途。
     */
    private ExecutionPurpose executionPurpose;

    /**
     * 事件优先级。
     */
    private Integer priority;

    /**
     * 下次重试时间。
     */
    private LocalDateTime nextRetryTime;

    /**
     * 已失败重试次数。
     */
    private Integer retryCount;

    /**
     * 当前认领者。
     */
    private String owner;

    /**
     * 租约代次。
     */
    private Long leaseEpoch;

    /**
     * 租约到期时间。
     */
    private LocalDateTime leaseUntil;

    /**
     * 事件并发版本。
     */
    private Long eventVersion;

    /**
     * 开始时间。
     */
    private LocalDateTime startedAt;

    /**
     * 结束时间。
     */
    private LocalDateTime endedAt;

    /**
     * 最后尝试 ID。
     */
    private Long lastAttemptId;

    /**
     * 是否已请求事件恢复。
     */
    private Boolean recoveryRequested;

    /**
     * 显式恢复截止时间。
     */
    private LocalDateTime recoveryDeadline;

    /**
     * 失败码。
     */
    private String errorCode;

    /**
     * 失败说明。
     */
    private String errorMessage;

}
