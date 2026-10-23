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
 * WaitReason 稳定英文标识，中文展示由前端字典或内置映射提供。
 */
public enum WaitReason {
    /** 等待前驱。 */
    PREDECESSOR,
    /** 等待首次确认。 */
    FIRST_CONFIRM,
    /** 等待业务触发。 */
    BUSINESS_TRIGGER,
    /** 等待结果回调。 */
    RESULT_CALLBACK,
    /** 执行失败。 */
    EXECUTION_FAILED,
    /** 等待计划名额。 */
    PLAN_SLOT,
    /** 等待资源。 */
    RESOURCE,
    /** 准入前挂起。 */
    HELD_BEFORE_START,
    /** 人工暂停。 */
    PAUSED,
    /** 取消待收敛。 */
    CANCEL_REQUESTED,
    /** 等待恢复。 */
    RECOVERY_PENDING
}
