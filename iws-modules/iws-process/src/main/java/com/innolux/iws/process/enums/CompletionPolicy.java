/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 全部选中路径完成等领域枚举；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.enums;

/**
 * CompletionPolicy 稳定英文标识，中文展示由前端字典或内置映射提供。
 */
public enum CompletionPolicy {
    /** 全部选中路径完成。 */
    ALL_SELECTED,
    /** 任一成功。 */
    ANY_SUCCESS
}
