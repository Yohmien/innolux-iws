/*
 * @Date         : 2026-09-19
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.workflow.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.innolux.iws.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;

/**
 * 流程分类对象 wf_category
 *
 * @author YONGJIE.PENG
 * @date 2023-06-27
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("flow_category")
public class FlowCategory extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 流程分类ID
     */
    @TableId(value = "category_id")
    private Long categoryId;

    /**
     * 父流程分类id
     */
    private Long parentId;

    /**
     * 祖级列表
     */
    private String ancestors;

    /**
     * 流程分类名称
     */
    private String categoryName;

    /**
     * 显示顺序
     */
    private Long orderNum;

    /**
     * 删除标志（0代表存在 1代表删除）
     */
    @TableLogic
    private String delFlag;

    /**
     * 子菜单
     */
    @TableField(exist = false)
    private List<FlowCategory> children = new ArrayList<>();

}
