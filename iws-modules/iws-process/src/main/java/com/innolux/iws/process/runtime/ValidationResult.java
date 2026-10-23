/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 单动作与状态校验能力契约；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

/**
 * 校验结果。
 *
 * @param passed 是否通过
 * @param code 结果码
 * @param message 中文说明
 */
public record ValidationResult(boolean passed, String code, String message) {

    /**
     * 通过结果。
     *
     * @return 通过
     */
    public static ValidationResult pass() {
        return new ValidationResult(true, "PASS", "通过");
    }

    /**
     * 失败结果。
     *
     * @param code 结果码
     * @param message 说明
     * @return 失败
     */
    public static ValidationResult fail(String code, String message) {
        return new ValidationResult(false, code, message);
    }
}
