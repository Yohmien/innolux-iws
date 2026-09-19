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

package com.innolux.iws.workflow.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import com.innolux.iws.common.core.validate.AddGroup;
import com.innolux.iws.common.core.validate.EditGroup;
import com.innolux.iws.workflow.domain.FlowCategory;

import java.io.Serial;
import java.io.Serializable;

/**
 * 流程分类业务对象 wf_category
 *
 * @author YONGJIE.PENG
 * @date 2023-06-27
 */
@Data
@AutoMapper(target = FlowCategory.class, reverseConvertGenerate = false)
public class FlowCategoryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 流程分类ID
     */
    @NotNull(message = "流程分类ID不能为空", groups = {EditGroup.class})
    private Long categoryId;

    /**
     * 父流程分类id
     */
    @NotNull(message = "父流程分类id不能为空", groups = {AddGroup.class, EditGroup.class})
    private Long parentId;

    /**
     * 流程分类名称
     */
    @NotBlank(message = "流程分类名称不能为空", groups = {AddGroup.class, EditGroup.class})
    private String categoryName;

    /**
     * 显示顺序
     */
    private Long orderNum;

}
