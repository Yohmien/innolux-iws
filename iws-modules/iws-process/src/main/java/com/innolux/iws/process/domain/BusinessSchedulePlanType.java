/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 业务调度允许的计划类型。；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.innolux.iws.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 业务调度允许的计划类型。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iws_business_schedule_plan_type")
public class BusinessSchedulePlanType extends BaseEntity {

    /**
     * 主键。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 业务调度 ID。
     */
    private Long scheduleId;

    /**
     * 计划类型编码。
     */
    private String planType;

    /**
     * 计划类型中文名。
     */
    private String planTypeName;

    /**
     * 类型优先级。
     */
    private Integer priority;

    /**
     * 是否允许新准入。
     */
    private Boolean enabled;

}
