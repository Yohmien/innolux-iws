/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 端点目录命令校验与幂等摘要；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.util;

import com.innolux.iws.common.core.exception.ServiceException;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 端点目录写命令的边界校验。
 */
public final class EndpointValidator {

    /**
     * 创建对象的期望版本标记。
     */
    public static final String ABSENT_VERSION = "ABSENT";

    private static final Pattern CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9_.:-]{0,63}$");
    private static final Pattern REFERENCE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$");
    private static final Pattern HOST_LABEL = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$");
    private static final Pattern CREDENTIAL_REFERENCE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$");

    private EndpointValidator() {
    }

    /**
     * 校验稳定编码并返回去除首尾空白的值。
     *
     * @param value 原始编码
     * @param field 中文字段名
     * @return 稳定编码
     */
    public static String code(String value, String field) {
        String normalized = required(value, field, 64);
        if (!CODE.matcher(normalized).matches()) {
            throw new ServiceException(field + "只能包含字母、数字、点、下划线、冒号或连字符，且必须以字母开头");
        }
        return normalized;
    }

    /**
     * 校验地址引用编码。
     *
     * @param value 原始引用
     * @return 稳定引用
     */
    public static String endpointReference(String value) {
        String normalized = required(value, "地址引用", 64);
        if (!REFERENCE.matcher(normalized).matches()) {
            throw new ServiceException("地址引用只能包含字母、数字、点、下划线或连字符");
        }
        return normalized;
    }

    /**
     * 校验中文展示名。
     *
     * @param value 原始名称
     * @param field 中文字段名
     * @return 去除首尾空白的名称
     */
    public static String displayName(String value, String field) {
        return required(value, field, 128);
    }

    /**
     * 校验命令键。
     *
     * @param value 原始命令键
     * @return 命令键
     */
    public static String commandKey(String value) {
        return required(value, "命令键", 128);
    }

    /**
     * 校验修改原因。
     *
     * @param value 原始原因
     * @return 原因文本
     */
    public static String reason(String value) {
        return required(value, "修改原因", 500);
    }

    /**
     * 校验创建对象必须使用 ABSENT 版本。
     *
     * @param expectedVersion 期望版本
     */
    public static void requireAbsentVersion(String expectedVersion) {
        if (!ABSENT_VERSION.equals(optional(expectedVersion))) {
            throw new ServiceException("创建对象的 expectedVersion 必须为 ABSENT");
        }
    }

    /**
     * 解析修改命令中的数值版本。
     *
     * @param expectedVersion 期望版本
     * @return 数值版本
     */
    public static long revision(String expectedVersion) {
        String normalized = required(expectedVersion, "expectedVersion", 20);
        if (ABSENT_VERSION.equals(normalized)) {
            throw new ServiceException("修改对象不能使用 ABSENT 版本");
        }
        try {
            long revision = Long.parseLong(normalized);
            if (revision < 0) {
                throw new NumberFormatException();
            }
            return revision;
        } catch (NumberFormatException exception) {
            throw new ServiceException("expectedVersion 必须是非负整数");
        }
    }

    /**
     * 解析字符串形式的主键。
     *
     * @param value 字符串主键
     * @param field 中文字段名
     * @return 主键
     */
    public static long id(String value, String field) {
        try {
            long id = Long.parseLong(required(value, field, 20));
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new ServiceException(field + "必须是正整数");
        }
    }

    /**
     * 校验并规范化 HTTP 或 HTTPS 协议。
     *
     * @param value 协议
     * @return 小写协议
     */
    public static String protocol(String value) {
        String normalized = required(value, "协议", 8).toLowerCase(Locale.ROOT);
        if (!"http".equals(normalized) && !"https".equals(normalized)) {
            throw new ServiceException("协议只允许 http 或 https");
        }
        return normalized;
    }

    /**
     * 校验主机字段，不接受协议、路径、查询串或内嵌凭据。
     *
     * @param value 域名、IPv4 或 IPv6
     * @return 规范化主机
     */
    public static String host(String value) {
        String host = required(value, "主机", 253).toLowerCase(Locale.ROOT);
        if (host.startsWith("[") || host.endsWith("]") || host.contains("://") || host.contains("/")
            || host.contains("\\") || host.contains("?") || host.contains("#") || host.contains("@")
            || host.chars().anyMatch(Character::isWhitespace) || host.contains("%")) {
            throw new ServiceException("主机字段只允许域名、IPv4 或 IPv6，不能包含协议、路径、查询串、凭据或协议占位符");
        }
        if (host.contains(":")) {
            try {
                if (!(InetAddress.getByName(host) instanceof Inet6Address)) {
                    throw new ServiceException("IPv6 主机格式不正确");
                }
            } catch (UnknownHostException exception) {
                throw new ServiceException("IPv6 主机格式不正确");
            }
            return host;
        }
        if (IPV4.matcher(host).matches()) {
            validateIpv4(host);
            return host;
        }
        if (host.length() > 253) {
            throw new ServiceException("域名长度不能超过 253 个字符");
        }
        for (String label : host.split("\\.")) {
            if (!HOST_LABEL.matcher(label).matches()) {
                throw new ServiceException("域名主机格式不正确");
            }
        }
        return host;
    }

    /**
     * 校验并规范化基础路径。
     *
     * @param value 基础路径
     * @return 以斜线开头且无尾斜线的相对根路径；根路径返回空串
     */
    public static String basePath(String value) {
        String path = optional(value);
        if (path == null || "/".equals(path)) {
            return "";
        }
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("?") || path.contains("#")
            || path.contains("\\") || path.contains("//") || path.chars().anyMatch(Character::isWhitespace)) {
            throw new ServiceException("基础路径必须以单个斜线开头，且不能包含查询串、片段、反斜线或空白");
        }
        for (String segment : path.split("/")) {
            if ("..".equals(segment) || ".".equals(segment)) {
                throw new ServiceException("基础路径不能包含相对路径片段");
            }
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.length() > 512) {
            throw new ServiceException("基础路径不能超过 512 个字符");
        }
        return path;
    }

    /**
     * 校验凭证引用标识。
     *
     * @param value 凭证引用
     * @return 去除首尾空白的凭证引用，未配置时返回 null
     */
    public static String credentialReference(String value) {
        String normalized = optional(value);
        if (normalized != null && !CREDENTIAL_REFERENCE.matcher(normalized).matches()) {
            throw new ServiceException("凭证引用只允许字母、数字、点、下划线、冒号、斜线或连字符");
        }
        return normalized;
    }

    /**
     * 校验可选端口。
     *
     * @param value 端口
     * @return 合法端口或 null
     */
    public static Integer port(Integer value) {
        if (value != null && (value < 1 || value > 65535)) {
            throw new ServiceException("端口必须在 1 到 65535 之间");
        }
        return value;
    }

    /**
     * 校验必填启停状态。
     *
     * @param value 启停状态
     * @return 布尔状态
     */
    public static boolean requiredFlag(Boolean value) {
        if (value == null) {
            throw new ServiceException("启停状态不能为空");
        }
        return value;
    }

    /**
     * 校验可选长文本。
     *
     * @param value 原始文本
     * @param field 中文字段名
     * @param maxLength 最大长度
     * @return 去除首尾空白的文本，空值返回 null
     */
    public static String optionalText(String value, String field, int maxLength) {
        String normalized = optional(value);
        if (normalized != null && normalized.length() > maxLength) {
            throw new ServiceException(field + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    /**
     * 计算命令语义摘要，长度前缀避免字段拼接歧义。
     *
     * @param values 已规范化的命令语义字段
     * @return SHA-256 十六进制摘要
     */
    public static String fingerprint(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                String part = value == null ? "<null>" : String.valueOf(value);
                digest.update(Integer.toString(part.length()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ';');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }

    /**
     * 去除首尾空白，空值返回 null。
     *
     * @param value 原始值
     * @return 规范化值
     */
    public static String optional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String required(String value, String field, int maxLength) {
        String normalized = optional(value);
        if (normalized == null) {
            throw new ServiceException(field + "不能为空");
        }
        if (normalized.length() > maxLength) {
            throw new ServiceException(field + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private static void validateIpv4(String host) {
        for (String octet : host.split("\\.")) {
            if (Integer.parseInt(octet) > 255) {
                throw new ServiceException("IPv4 主机格式不正确");
            }
        }
    }
}
