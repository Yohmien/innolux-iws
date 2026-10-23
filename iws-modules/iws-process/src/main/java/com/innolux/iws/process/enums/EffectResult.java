/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 成功等领域枚举；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.enums;

/**
 * EffectResult 稳定英文标识，中文展示由前端字典或内置映射提供。
 */
public enum EffectResult {
    /** 成功。 */
    SUCCEEDED,
    /** 已受理。 */
    ACCEPTED,
    /** 无效果。 */
    NO_EFFECT,
    /** 已跳过。 */
    SKIPPED,
    /** 已屏蔽。 */
    SKIPPED_MODE,
    /** 结果未知。 */
    UNKNOWN,
    /** 失败。 */
    FAILED
}
