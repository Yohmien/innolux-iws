/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 业务控制、监督轮次、计划名额与调用许可控制行。；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.innolux.iws.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import com.innolux.iws.process.enums.ControlState;
import com.innolux.iws.process.enums.PermitState;
import com.innolux.iws.process.enums.RoundStatus;
import com.innolux.iws.process.enums.ScheduleControlScope;
import com.innolux.iws.process.enums.SlotState;
import java.time.LocalDateTime;

/**
 * 业务控制、监督轮次、计划名额与调用许可控制行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iws_process_schedule_control")
public class ProcessScheduleControl extends BaseEntity {

    /**
     * 主键。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 控制范围类型。
     */
    private ScheduleControlScope scopeType;

    /**
     * 范围键。
     */
    private String scopeKey;

    /**
     * 职责键。
     */
    private String duty;

    /**
     * 控制状态。
     */
    private ControlState controlState;

    /**
     * 期望控制状态。
     */
    private String desiredState;

    /**
     * 已观测控制状态。
     */
    private String observedState;

    /**
     * 控制修订号。
     */
    private Long revision;

    /**
     * 租约/围栏代次。
     */
    private Long epoch;

    /**
     * 当前持有者。
     */
    private String owner;

    /**
     * 租约到期时间。
     */
    private LocalDateTime leaseUntil;

    /**
     * 是否存在待处理唤醒。
     */
    private Boolean wakePending;

    /**
     * 监督轮次号。
     */
    private Long roundNo;

    /**
     * 轮次状态。
     */
    private RoundStatus roundStatus;

    /**
     * 监督实例 ID。
     */
    private Long supervisorInstanceId;

    /**
     * 监督定义版本。
     */
    private Long definitionVersionId;

    /**
     * 子计划实例 ID。
     */
    private Long childInstanceId;

    /**
     * 计划名额状态。
     */
    private SlotState slotState;

    /**
     * 调用许可状态。
     */
    private PermitState permitState;

    /**
     * 资源键。
     */
    private String resourceKey;

    /**
     * 当前公平窗口计数。
     */
    private Integer fairnessCount;

    /**
     * 最近准入时间。
     */
    private LocalDateTime lastAdmittedAt;

    /**
     * 最近活动时间。
     */
    private LocalDateTime lastActivityAt;

    /**
     * 最近错误。
     */
    private String errorMessage;

}
