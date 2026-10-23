/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : process 文本、枚举和摘要工具；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.support;

import com.innolux.iws.common.core.exception.ServiceException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 小型稳定文本工具，不承载业务判断。
 */
public final class ProcessText {

    private ProcessText() {
    }

    /**
     * 要求非空文本。
     *
     * @param value 值
     * @param name 字段名
     * @return 去空白后的值
     */
    public static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ServiceException(name + "不能为空");
        }
        return value.trim();
    }

    /**
     * 将字符串解析为稳定枚举。
     *
     * @param type 枚举类型
     * @param field 字段名
     * @param value 值
     * @param errors 错误收集
     * @param <E> 枚举类型
     * @return 解析结果，失败返回 null
     */
    public static <E extends Enum<E>> E parse(Class<E> type, String field, String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(field + "不能为空");
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            errors.add(field + "不是合法枚举: " + value);
            return null;
        }
    }

    /**
     * 计算 SHA-256 十六进制摘要。
     *
     * @param value 原文
     * @return 摘要
     */
    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 缺少 SHA-256", ex);
        }
    }

    /**
     * 将 ID 转为字符串。
     *
     * @param value ID
     * @return 字符串，null 返回 null
     */
    public static String id(Long value) {
        return value == null ? null : value.toString();
    }
}
