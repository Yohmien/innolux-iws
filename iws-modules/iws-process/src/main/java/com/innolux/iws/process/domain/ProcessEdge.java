/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 事件节点之间的显式依赖与分支边。；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.innolux.iws.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import com.innolux.iws.process.enums.BranchKind;

/**
 * 事件节点之间的显式依赖与分支边。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iws_process_edge")
public class ProcessEdge extends BaseEntity {

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
     * 前驱事件编码。
     */
    private String predecessorEventCode;

    /**
     * 后继事件编码。
     */
    private String successorEventCode;

    /**
     * 分支键。
     */
    private String branchKey;

    /**
     * 分支类型。
     */
    private BranchKind branchKind;

    /**
     * 条件能力键。
     */
    private String conditionKey;

    /**
     * 条件版本。
     */
    private String conditionVersion;

    /**
     * 是否默认分支。
     */
    private Boolean defaultBranch;

    /**
     * 汇合编码。
     */
    private String joinCode;

    /**
     * 分支优先级。
     */
    private Integer priority;

}
