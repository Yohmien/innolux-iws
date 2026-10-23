/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 待关联等领域枚举；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.enums;

/**
 * ProcessingStatus 稳定英文标识，中文展示由前端字典或内置映射提供。
 */
public enum ProcessingStatus {
    /** 待关联。 */
    PENDING_LINK,
    /** 已应用。 */
    APPLIED,
    /** 重复。 */
    DUPLICATE,
    /** 冲突。 */
    CONFLICT,
    /** 拒绝。 */
    REJECTED
}
