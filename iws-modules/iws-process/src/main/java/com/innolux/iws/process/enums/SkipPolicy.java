/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 要求真实入站等领域枚举；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.enums;

/**
 * SkipPolicy 稳定英文标识，中文展示由前端字典或内置映射提供。
 */
public enum SkipPolicy {
    /** 要求真实入站。 */
    REQUIRE_REAL_INBOUND,
    /** 允许跳过。 */
    ALLOW,
    /** 禁止跳过。 */
    FORBID
}
