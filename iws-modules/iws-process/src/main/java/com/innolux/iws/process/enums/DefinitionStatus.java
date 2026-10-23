/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 草稿等领域枚举；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.enums;

/**
 * DefinitionStatus 稳定英文标识，中文展示由前端字典或内置映射提供。
 */
public enum DefinitionStatus {
    /** 草稿。 */
    DRAFT,
    /** 生效。 */
    ACTIVE,
    /** 已退役。 */
    RETIRED
}
