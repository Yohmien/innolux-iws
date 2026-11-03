/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则命令的入参规范化和稳定错误码构造；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpStatus;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.rule.WmsRuleContract;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 规则命令入参校验。
 *
 * <p>标识、编码和名称去除首尾空白后保存；条件与结果 JSON 不在此处改写，由服务原样持久化。</p>
 *
 * @author YONGJIE.PENG
 */
public final class WmsRuleValidation {

    private static final String DIGEST_PATTERN = "^[0-9a-f]{64}$";

    private WmsRuleValidation() {
    }

    /**
     * 校验并规范化命令键。
     *
     * @param commandKey 命令键
     * @return 规范化命令键
     */
    public static String requireCommandKey(String commandKey) {
        String text = StrUtil.trimToNull(commandKey);
        if (text == null || text.length() > 64) {
            throw invalid("commandKey 必填且不超过 64 字符");
        }
        return text;
    }

    /**
     * 校验并规范化必填文本。
     *
     * @param value     原值
     * @param field     字段名
     * @param maxLength 最大长度
     * @return 规范化文本
     */
    public static String requiredText(String value, String field, int maxLength) {
        String text = StrUtil.trimToNull(value);
        if (text == null) {
            throw invalid(field + " 必填");
        }
        if (text.length() > maxLength) {
            throw invalid(field + " 不得超过 " + maxLength + " 字符");
        }
        return text;
    }

    /**
     * 校验必填正整数 ID。
     *
     * @param value 原值
     * @param field 字段名
     * @return 原值
     */
    public static Long requireId(Long value, String field) {
        if (value == null || value <= 0) {
            throw invalid(field + " 必须为正整数");
        }
        return value;
    }

    /**
     * 校验必填 JSON 文档。
     *
     * @param value 原值
     * @param field 字段名
     * @return 原值
     */
    public static JsonNode requireJson(JsonNode value, String field) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            throw invalid(field + " 必填且必须是合法 JSON");
        }
        return value;
    }

    /**
     * 校验规范化摘要格式。
     *
     * @param value 原值
     * @param field 字段名
     * @return 原值
     */
    public static String requireDigest(String value, String field) {
        String text = StrUtil.trimToNull(value);
        if (text == null || !text.matches(DIGEST_PATTERN)) {
            throw invalid(field + " 必须为 64 位小写十六进制 SHA-256");
        }
        return text;
    }

    /**
     * 校验作用域类型。
     *
     * @param scopeType 作用域类型
     * @return 规范化作用域类型
     */
    public static String requireScopeType(String scopeType) {
        return requiredText(scopeType, "scopeType", 32);
    }

    /**
     * 校验储位快照集合。
     *
     * @param storageIds 储位 ID 集合，允许为 null
     * @return 只读副本
     */
    public static List<Long> requireStorageIds(List<Long> storageIds) {
        if (storageIds == null) {
            return List.of();
        }
        for (Long storageId : storageIds) {
            if (storageId == null || storageId <= 0) {
                throw invalid("storageIds 只能包含正整数");
            }
        }
        return List.copyOf(storageIds);
    }

    /**
     * 构造命令入参错误。
     *
     * @param message 错误说明
     * @return 400 业务异常
     */
    public static ServiceException invalid(String message) {
        return new ServiceException(
            WmsRuleContract.ERROR_INVALID_COMMAND + ": " + message,
            HttpStatus.HTTP_BAD_REQUEST
        );
    }
}
