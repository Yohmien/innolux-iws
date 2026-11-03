/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 条件 DSL
 * @Version      : V1.0
 * @Description  : 含稳定错误码和 JSON Pointer 的纯内核异常；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.dsl;

/** 调用适配器负责映射 HTTP 状态；内核不依赖 Web 或业务写入服务。 */
public final class RuleDslException extends IllegalArgumentException {
    private final String code;
    private final String pointer;

    RuleDslException(String code, String pointer, String message) {
        super("RULE_DSL_" + code + ": " + pointer + " " + message);
        this.code = "RULE_DSL_" + code;
        this.pointer = pointer;
    }

    public String code() { return code; }

    public String pointer() { return pointer; }
}
