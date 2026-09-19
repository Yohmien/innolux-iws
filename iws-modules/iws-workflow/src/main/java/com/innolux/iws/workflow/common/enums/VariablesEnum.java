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

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 节点扩展变量枚举，占位用于后续补充可配置变量定义。
 *
 * @author YONGJIE.PENG
 */
@Getter
@AllArgsConstructor
public enum VariablesEnum implements NodeExtEnum {
    ;

    /**
     * 展示名称。
     */
    private final String label;

    /**
     * 变量值。
     */
    private final String value;

    /**
     * 是否选中。
     */
    private final boolean selected;

}

