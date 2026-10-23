/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 业务计划或长期监督计划实例。；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.innolux.iws.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.PlanStatus;
import java.time.LocalDateTime;

/**
 * 业务计划或长期监督计划实例。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iws_process_instance")
public class ProcessInstance extends BaseEntity {

    /**
     * 主键。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 计划角色。
     */
    private PlanRole planRole;

    /**
     * 冻结定义版本。
     */
    private Long definitionVersionId;

    /**
     * 冻结定义修订号。
     */
    private Long definitionRevision;

    /**
     * 业务调度 ID。
     */
    private Long scheduleId;

    /**
     * 计划类型。
     */
    private String planType;

    /**
     * 业务调度编码。
     */
    private String businessScheduleCode;

    /**
     * 绑定的监督实例 ID。
     */
    private Long supervisorInstanceId;

    /**
     * 监督唯一键：scheduleId:planType:scope；仅 SUPERVISOR 非空。
     */
    private String supervisorKey;

    /**
     * 业务任务唯一键：businessType:taskKey；仅 BUSINESS 非空。
     */
    private String businessTaskKey;

    /**
     * 业务类型。
     */
    private String businessType;

    /**
     * 业务标识。
     */
    private String businessId;

    /**
     * 业务任务唯一键。
     */
    private String taskKey;

    /**
     * 调度范围。
     */
    private String scheduleScope;

    /**
     * 模式快照。
     */
    private PlanMode modeSnapshot;

    /**
     * 是否准入前挂起。
     */
    private Boolean holdStart;

    /**
     * 首次准入时间。
     */
    private LocalDateTime firstAdmittedAt;

    /**
     * 计划运行态。
     */
    private PlanStatus planStatus;

    /**
     * 实例优先级。
     */
    private Integer instancePriority;

    /**
     * 冻结起点集合。
     */
    private String selectedRootSet;

    /**
     * 关键输入摘要。
     */
    private String inputHash;

    /**
     * 即时计划绝对执行期限。
     */
    private LocalDateTime executionDeadline;

    /**
     * 监督轮次号。
     */
    private Long roundNo;

    /**
     * 是否请求停止新发送。
     */
    private Boolean stopRequested;

    /**
     * 是否请求取消。
     */
    private Boolean cancelRequested;

    /**
     * 当前异常摘要。
     */
    private String exceptionSummary;

    /**
     * 开始时间。
     */
    private LocalDateTime startedAt;

    /**
     * 结束时间。
     */
    private LocalDateTime endedAt;

    /**
     * 实例并发版本。
     */
    private Long instanceVersion;

}
