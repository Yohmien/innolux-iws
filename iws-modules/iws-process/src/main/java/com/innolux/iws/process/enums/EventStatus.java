/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 等待前驱等领域枚举；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.enums;

/**
 * EventStatus 稳定英文标识，中文展示由前端字典或内置映射提供。
 */
public enum EventStatus {
    /** 等待前驱。 */
    WAIT_PREDECESSOR,
    /** 等待外部触发。 */
    WAIT_EXTERNAL_TRIGGER,
    /** 待人工。 */
    MANUAL_PENDING,
    /** 待执行。 */
    PENDING,
    /** 执行中。 */
    PROCESSING,
    /** 待重试。 */
    RETRY_WAIT,
    /** 成功。 */
    SUCCESS,
    /** 已跳过。 */
    SKIPPED,
    /** 未选中。 */
    NOT_SELECTED,
    /** 最终失败。 */
    FAILED_FINAL
}
