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
import lombok.Data;
import com.innolux.iws.common.core.validate.AddGroup;
import com.innolux.iws.common.core.validate.EditGroup;
import com.innolux.iws.workflow.domain.FlowSpel;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 流程spel表达式定义业务对象 flow_spel
 *
 * @author YONGJIE.PENG
 * @date 2025-07-04
 */
@Data
@AutoMapper(target = FlowSpel.class, reverseConvertGenerate = false)
public class FlowSpelBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    private Long id;

    /**
     * 组件名称
     */
    private String componentName;

    /**
     * 方法名
     */
    private String methodName;

    /**
     * 参数
     */
    private String methodParams;

    /**
     * 预览spel值
     */
    @NotBlank(message = "预览spel值不能为空", groups = {AddGroup.class, EditGroup.class})
    private String viewSpel;

    /**
     * 状态（0正常 1停用）
     */
    @NotBlank(message = "状态（0正常 1停用）不能为空", groups = {AddGroup.class, EditGroup.class})
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 请求参数
     */
    private Map<String, Object> params = new HashMap<>();

}
