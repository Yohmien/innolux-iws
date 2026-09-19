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

package com.innolux.iws.workflow.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Node 扩展属性解析结果 VO
 * <p>
 * 用于封装从扩展属性 JSON 中解析出的各类信息，包括按钮权限、抄送对象和自定义参数。
 *
 * @author YONGJIE.PENG
 */
@Data
public class NodeExtVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 按钮权限列表
     * <p>
     * 根据扩展属性中 ButtonPermissionEnum 类型的数据生成，每个元素表示一个按钮及其是否勾选。
     */
    private List<ButtonPermissionVo> buttonPermissions;

    /**
     * 抄送对象 ID 集合
     * <p>
     * 根据扩展属性中 CopySettingEnum 类型的数据生成，存储需要抄送的对象 ID
     */
    private Set<String> copySettings;

    /**
     * 自定义参数 Map
     * <p>
     * 根据扩展属性中 VariablesEnum 类型的数据生成，存储 key=value 格式的自定义参数
     */
    private Map<String, String> variables;

}
