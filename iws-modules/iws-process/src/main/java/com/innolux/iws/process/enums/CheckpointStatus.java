/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 已认领等领域枚举；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.enums;

/**
 * CheckpointStatus 稳定英文标识，中文展示由前端字典或内置映射提供。
 */
public enum CheckpointStatus {
    /** 已认领。 */
    CLAIMED,
    /** 已提交发送意图。 */
    SEND_INTENT,
    /** 成功。 */
    SUCCEEDED,
    /** 已受理。 */
    ACCEPTED,
    /** 已屏蔽。 */
    SKIPPED_MODE,
    /** 无效果。 */
    NO_EFFECT,
    /** 结果未知。 */
    UNKNOWN,
    /** 失败。 */
    FAILED,
    /** 已跳过。 */
    SKIPPED
}
