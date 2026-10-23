/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划版本内状态定义。；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.innolux.iws.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import com.innolux.iws.process.enums.StateKind;

/**
 * 计划版本内状态定义。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iws_process_state")
public class ProcessState extends BaseEntity {

    /**
     * 主键。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 定义版本 ID。
     */
    private Long definitionVersionId;

    /**
     * 状态编码。
     */
    private String stateCode;

    /**
     * 状态中文名。
     */
    private String displayName;

    /**
     * 状态类别。
     */
    private StateKind stateKind;

    /**
     * 校验器能力键。
     */
    private String validatorKey;

    /**
     * 校验器版本。
     */
    private String validatorVersion;

    /**
     * 校验条件说明。
     */
    private String conditionDescription;

    /**
     * 是否终点。
     */
    private Boolean terminal;

    /**
     * 展示顺序。
     */
    private Integer sortOrder;

}
