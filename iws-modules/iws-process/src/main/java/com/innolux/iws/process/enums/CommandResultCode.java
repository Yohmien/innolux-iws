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
 * CommandResultCode 稳定英文标识，中文展示由前端字典或内置映射提供。
 */
public enum CommandResultCode {
    /** 成功。 */
    SUCCEEDED,
    /** 失败。 */
    FAILED,
    /** 失败停留。 */
    FAILED_HOLD,
    /** 忙碌。 */
    BUSY,
    /** 监督忙碌。 */
    SUPERVISOR_BUSY,
    /** 未就绪。 */
    NOT_READY,
    /** 监督已暂停。 */
    SUPERVISOR_PAUSED,
    /** 模式不符。 */
    MODE_MISMATCH,
    /** 结果未知。 */
    RESULT_UNKNOWN,
    /** 响应超时。 */
    RESPONSE_TIMEOUT,
    /** 处理中。 */
    PROCESSING
}
