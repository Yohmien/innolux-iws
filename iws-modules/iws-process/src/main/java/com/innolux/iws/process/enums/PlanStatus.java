/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 已创建等领域枚举；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.enums;

/**
 * PlanStatus 稳定英文标识，中文展示由前端字典或内置映射提供。
 */
public enum PlanStatus {
    /** 已创建。 */
    CREATED,
    /** 运行中。 */
    RUNNING,
    /** 待人工。 */
    MANUAL_PENDING,
    /** 异常停留。 */
    EXCEPTION_HOLD,
    /** 已暂停。 */
    PAUSED,
    /** 已完成。 */
    COMPLETED,
    /** 最终失败。 */
    FAILED_FINAL,
    /** 已取消。 */
    CANCELLED
}
