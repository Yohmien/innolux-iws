/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 事件单动作尝试与检查点。；
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
import com.innolux.iws.process.enums.CheckpointStatus;
import com.innolux.iws.process.enums.ExecutionPurpose;
import java.time.LocalDateTime;

/**
 * 事件单动作尝试与检查点。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iws_process_attempt")
public class ProcessAttempt extends BaseEntity {

    /**
     * 主键。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 事件 ID。
     */
    private Long eventId;

    /**
     * 尝试序号。
     */
    private Integer attemptNo;

    /**
     * 恢复来源尝试 ID。
     */
    private Long resumeFromAttemptId;

    /**
     * 检查点状态。
     */
    private CheckpointStatus checkpointStatus;

    /**
     * 是否已提交发送意图。
     */
    private Boolean sent;

    /**
     * 实际请求 ID。
     */
    private String requestId;

    /**
     * 输入摘要。
     */
    private String inputHash;

    /**
     * 业务幂等键。
     */
    private String idempotencyKey;

    /**
     * 操作能力版本。
     */
    private String operationVersion;

    /**
     * 本地动作版本。
     */
    private String localActionVersion;

    /**
     * 本地效果键。
     */
    private String localEffectKey;

    /**
     * 证据引用。
     */
    private String evidenceRef;

    /**
     * 效果判定。
     */
    private EffectResult effectResult;

    /**
     * 失败码。
     */
    private String errorCode;

    /**
     * 失败说明。
     */
    private String errorMessage;

    /**
     * 源状态快照。
     */
    private String sourceStateCode;

    /**
     * 目标状态快照。
     */
    private String targetStateCode;

    /**
     * 认领者。
     */
    private String owner;

    /**
     * 租约代次。
     */
    private Long leaseEpoch;

    /**
     * 执行用途。
     */
    private ExecutionPurpose executionPurpose;

    /**
     * 结构化检查点。
     */
    private String checkpointJson;

    /**
     * 开始时间。
     */
    private LocalDateTime startedAt;

    /**
     * 结束时间。
     */
    private LocalDateTime endedAt;

    /**
     * 本地动作事务标识。
     */
    private String transactionId;

    /**
     * 证据记录时间。
     */
    private LocalDateTime recordedAt;

}
