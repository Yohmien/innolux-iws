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

package com.innolux.iws.workflow.common.enums;

/**
 * 节点扩展属性枚举通用接口，约束扩展选项的展示名、值和默认勾选状态。
 *
 * @author YONGJIE.PENG
 */
public interface NodeExtEnum {

    /**
     * 获取选项展示名称。
     *
     * @return 选项label
     */
    String getLabel();

    /**
     * 获取选项值。
     *
     * @return 选项值
     */
    String getValue();

    /**
     * 是否默认选中。
     *
     * @return 是否默认选中
     */
    boolean isSelected();

}

