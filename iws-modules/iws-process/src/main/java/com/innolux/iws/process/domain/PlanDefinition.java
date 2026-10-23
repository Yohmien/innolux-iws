/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划定义版本聚合根。；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.innolux.iws.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import com.innolux.iws.process.enums.DefinitionStatus;
import com.innolux.iws.process.enums.CompletionPolicy;
import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.enums.PlanRole;
import java.time.LocalDateTime;

/**
 * 计划定义版本聚合根。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iws_process_definition")
public class PlanDefinition extends BaseEntity {

    /**
     * 主键。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 计划稳定编码。
     */
    private String planCode;

    /**
     * 业务版本号。
     */
    private Integer planVersion;

    /**
     * 计划类型。
     */
    private String planType;

    /**
     * 业务类型。
     */
    private String businessType;

    /**
     * 所属业务调度编码。
     */
    private String businessScheduleCode;

    /**
     * 计划中文名。
     */
    private String planName;

    /**
     * 计划角色。
     */
    private PlanRole planRole;

    /**
     * 版本状态。
     */
    private DefinitionStatus status;

    /**
     * 聚合乐观锁。
     */
    private Long revision;

    /**
     * 最新草稿唯一键，仅最新草稿非空。
     */
    private String latestKey;

    /**
     * 生效版本唯一键，仅 ACTIVE 非空。
     */
    private String activeKey;

    /**
     * 本系统编码。
     */
    private String selfSystemCode;

    /**
     * 监督模式。
     */
    private PlanMode mode;

    /**
     * 监督计划名额上限。
     */
    private Integer activePlanLimit;

    /**
     * 监督公平窗口。
     */
    private Integer fairnessWindowSize;

    /**
     * 即时计划执行期限。
     */
    private Long maxExecutionMs;

    /**
     * 完成策略。
     */
    private CompletionPolicy completionPolicy;

    /**
     * 统一异常状态码。
     */
    private String exceptionStateCode;

    /**
     * 监督单轮事件上限。
     */
    private Integer roundBatchLimit;

    /**
     * 监督单轮时长上限。
     */
    private Long roundMaxDurationMs;

    /**
     * 监督任务固定间隔毫秒。
     */
    private Long scheduleIntervalMs;

    /**
     * 监督任务 CRON 表达式。
     */
    private String scheduleCron;

    /**
     * 平台单次触发超时毫秒。
     */
    private Long jobTimeoutMs;

    /**
     * 平台自动重试次数，领域默认 0。
     */
    private Integer jobMaxRetryTimes;

    /**
     * 事件 worker 单轮上限。
     */
    private Integer workerBatchLimit;

    /**
     * 事件 worker 单轮时长上限。
     */
    private Long workerMaxDurationMs;

    /**
     * 事件租约时长。
     */
    private Long leaseMs;

    /**
     * 自动模式有限重试上限。
     */
    private Integer retryMax;

    /**
     * 自动模式退避间隔。
     */
    private Long retryBackoffMs;

    /**
     * 发布人。
     */
    private Long publishedBy;

    /**
     * 发布时间。
     */
    private LocalDateTime publishedAt;

    /**
     * 发布摘要。
     */
    private String publishedSummary;

    /**
     * 计划说明。
     */
    private String description;

    /**
     * 逻辑删除标志。
     */
    @TableLogic
    private String delFlag;

}
