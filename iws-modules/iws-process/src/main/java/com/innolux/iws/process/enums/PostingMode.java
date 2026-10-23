/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 自动过账等领域枚举；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.enums;

/**
 * PostingMode 稳定英文标识，中文展示由前端字典或内置映射提供。
 */
public enum PostingMode {
    /** 自动过账。 */
    AUTOMATIC,
    /** 人工确认。 */
    MANUAL_CONFIRM,
    /** 屏蔽过账。 */
    BYPASS
}
