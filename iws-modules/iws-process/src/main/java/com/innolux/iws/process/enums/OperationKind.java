/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 外部抛账等领域枚举；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.enums;

/**
 * OperationKind 稳定英文标识，中文展示由前端字典或内置映射提供。
 */
public enum OperationKind {
    /** 外部抛账。 */
    EXTERNAL_POSTING,
    /** 查询。 */
    QUERY,
    /** 控制。 */
    CONTROL,
    /** 入站触发。 */
    INBOUND_TRIGGER,
    /** 结果回调。 */
    RESULT_CALLBACK
}
