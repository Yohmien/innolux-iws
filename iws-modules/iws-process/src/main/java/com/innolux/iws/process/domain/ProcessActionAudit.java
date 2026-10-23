/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 追加式领域动作审计与命令回执锚点。；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.innolux.iws.process.enums.ActionSource;
import com.innolux.iws.process.enums.AuditResult;
import java.time.LocalDateTime;

/**
 * 追加式领域动作审计与命令回执锚点。
 */
@Data
@TableName("iws_process_action_audit")
public class ProcessActionAudit {

    /**
     * 主键。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 动作编码。
     */
    private String actionCode;

    /**
     * 动作来源。
     */
    private ActionSource actionSource;

    /**
     * 命令幂等键。
     */
    private String commandKey;

    /**
     * 唯一命令回执锚点。
     */
    private String receiptKey;

    /**
     * 操作者用户 ID。
     */
    private Long actorUserId;

    /**
     * 操作者名称。
     */
    private String actorName;

    /**
     * 追踪标识。
     */
    private String traceId;

    /**
     * 业务调度 ID。
     */
    private Long scheduleId;

    /**
     * 定义版本 ID。
     */
    private Long definitionVersionId;

    /**
     * 计划实例 ID。
     */
    private Long instanceId;

    /**
     * token ID。
     */
    private Long tokenId;

    /**
     * 事件 ID。
     */
    private Long eventId;

    /**
     * 尝试 ID。
     */
    private Long attemptId;

    /**
     * 监督实例 ID。
     */
    private Long supervisorInstanceId;

    /**
     * 监督轮次号。
     */
    private Long roundNo;

    /**
     * 调度记录标识。
     */
    private String dispatchId;

    /**
     * 子计划实例 ID。
     */
    private Long childInstanceId;

    /**
     * 请求 ID。
     */
    private String requestId;

    /**
     * 变更前状态。
     */
    private String beforeStatus;

    /**
     * 变更前版本。
     */
    private Long beforeVersion;

    /**
     * 变更后状态。
     */
    private String afterStatus;

    /**
     * 变更后版本。
     */
    private Long afterVersion;

    /**
     * 动作结果。
     */
    private AuditResult result;

    /**
     * 原因说明。
     */
    private String reason;

    /**
     * 脱敏参数摘要。
     */
    private String payloadSummary;

    /**
     * 决策摘要。
     */
    private String decisionSummary;

    /**
     * 名额变化前占用数。
     */
    private Integer slotBefore;

    /**
     * 名额变化后占用数。
     */
    private Integer slotAfter;

    /**
     * 控制代次。
     */
    private Long epoch;

    /**
     * 控制修订号。
     */
    private Long controlRevision;

    /**
     * 平台任务 ID。
     */
    private String platformJobId;

    /**
     * 平台批次 ID。
     */
    private String jobBatchId;

    /**
     * 发生时间。
     */
    private LocalDateTime occurredAt;

    /**
     * 耗时毫秒。
     */
    private Long durationMs;

    /**
     * 请求开始时间。
     */
    private LocalDateTime requestStartedAt;

    /**
     * 请求结束时间。
     */
    private LocalDateTime requestFinishedAt;

    /**
     * 客户端 IP。
     */
    private String clientIp;

    /**
     * User-Agent。
     */
    private String userAgent;

    /**
     * 入口路径。
     */
    private String entryPath;

}
