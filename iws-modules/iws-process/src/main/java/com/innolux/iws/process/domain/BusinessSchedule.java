/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 业务统一调度分组实体。；
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

/**
 * 业务统一调度分组实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iws_business_schedule")
public class BusinessSchedule extends BaseEntity {

    /**
     * 主键。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 业务稳定编码。
     */
    private String businessCode;

    /**
     * 业务中文名。
     */
    private String businessName;

    /**
     * 是否允许新准入。
     */
    private Boolean enabled;

    /**
     * 公平保底窗口 K。
     */
    private Integer fairnessWindowSize;

    /**
     * 聚合乐观锁版本。
     */
    private Long revision;

    /**
     * 说明。
     */
    private String remark;

    /**
     * 逻辑删除标志。
     */
    @TableLogic
    private String delFlag;

}
