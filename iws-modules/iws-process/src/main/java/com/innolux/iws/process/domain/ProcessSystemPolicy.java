/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划版本的逐系统单值过账模式。；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.innolux.iws.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import com.innolux.iws.process.enums.PostingMode;

/**
 * 计划版本的逐系统单值过账模式。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iws_process_system_policy")
public class ProcessSystemPolicy extends BaseEntity {

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
     * 政策系统编码。
     */
    private String systemCode;

    /**
     * 过账模式。
     */
    private PostingMode postingMode;

    /**
     * 首次确认策略。
     */
    private String firstConfirmPolicy;

    /**
     * 失败策略。
     */
    private String failurePolicy;

    /**
     * 说明。
     */
    private String remark;

}
