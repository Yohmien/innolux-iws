/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 条件 DSL
 * @Version      : V1.0
 * @Description  : 严格类型解析与 Unicode 码点比较；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.dsl;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Set;
import java.util.regex.Pattern;

final class RuleDslValues {
    static final Set<String> TYPES = Set.of("STRING", "DECIMAL", "INTEGER", "BOOLEAN", "DATE", "DATE_TIME");
    static final Set<String> COMPARISONS = Set.of("EQ", "NE", "LT", "LE", "GT", "GE");
    private static final Pattern DECIMAL = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?");
    private static final Pattern INTEGER = Pattern.compile("-?(0|[1-9][0-9]*)");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final int PRECISION = 20;
    private static final int MAX_SCALE = 6;

    private RuleDslValues() { }

    static RuleDslException error(String code, String path, String message) {
        return new RuleDslException(code, path, message);
    }

    static void stringLimit(String text, String path, DslLimits limits) {
        if (text.codePointCount(0, text.length()) > limits.maxStringCodePoints()) {
            throw error("LIMIT_EXCEEDED", path, "字符串码点数超限");
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw error("VALUE_INVALID", path, "字符串含无效 Unicode 代理项");
                }
            } else if (Character.isLowSurrogate(ch)) {
                throw error("VALUE_INVALID", path, "字符串含无效 Unicode 代理项");
            }
        }
    }

    static void binding(RuleAttributeBinding b, String path, DslLimits limits) {
        if (b == null || blank(b.attributeKey()) || blank(b.sourceType()) || blank(b.contractVersion())
            || b.revision() < 1 || !"ACTIVE".equals(b.status())) {
            throw error("BINDING_INVALID", path, "属性快照必须完整且为 ACTIVE");
        }
        if (!TYPES.contains(b.valueType() == null ? "" : b.valueType())) {
            throw error("UNKNOWN_VALUE_TYPE", path, "属性值类型未登记");
        }
        if (!Set.of("SCALAR", "SET").contains(b.cardinality() == null ? "" : b.cardinality())) {
            throw error("BINDING_INVALID", path, "属性基数未登记");
        }
        if ("DECIMAL".equals(b.valueType())) {
            if (b.scale() == null || b.scale() < 0 || b.scale() > MAX_SCALE) {
                throw error("BINDING_INVALID", path, "DECIMAL scale 必须为 0..6");
            }
        } else if (b.scale() != null || b.unit() != null) {
            throw error("BINDING_INVALID", path, "非 DECIMAL 不接受单位或 scale");
        }
        if (b.unit() != null && blank(b.unit())) {
            throw error("BINDING_INVALID", path, "单位不能是空白文本");
        }
        if (b.operators() == null || b.operators().isEmpty()) {
            throw error("BINDING_INVALID", path, "属性缺少允许操作符");
        }
        Set<String> seen = new java.util.HashSet<>();
        for (String op : b.operators()) {
            if (op == null || !seen.add(op) || !allowed(b.valueType(), op)) {
                throw error("BINDING_INVALID", path, "属性操作符未知、重复或不适用于值类型");
            }
        }
        stringLimit(b.attributeKey(), path, limits);
        stringLimit(b.sourceType(), path, limits);
        stringLimit(b.contractVersion(), path, limits);
        if (b.unit() != null) { stringLimit(b.unit(), path, limits); }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    static boolean allowed(String type, String op) {
        return "IS_NULL".equals(op) || "IN".equals(op)
            || (COMPARISONS.contains(op) && (!"BOOLEAN".equals(type) || Set.of("EQ", "NE").contains(op)));
    }

    static Object parse(JsonNode node, RuleAttributeBinding b, String path, DslLimits limits) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            throw error("VALUE_INVALID", path, "比较字面量不得为空");
        }
        if ("BOOLEAN".equals(b.valueType())) {
            if (!node.isBoolean()) { throw error("VALUE_INVALID", path, "必须为 JSON boolean"); }
            return node.booleanValue();
        }
        if (!node.isTextual()) { throw error("VALUE_INVALID", path, "必须为 JSON 字符串"); }
        String text = node.textValue();
        stringLimit(text, path, limits);
        try {
            return switch (b.valueType()) {
                case "STRING" -> text;
                case "DECIMAL" -> decimal(text, b.scale());
                case "INTEGER" -> {
                    if (!INTEGER.matcher(text).matches()) { throw new IllegalArgumentException(); }
                    yield Long.valueOf(text);
                }
                case "DATE" -> {
                    if (!DATE.matcher(text).matches()) { throw new IllegalArgumentException(); }
                    yield LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT));
                }
                case "DATE_TIME" -> OffsetDateTime.parse(text,
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.withResolverStyle(ResolverStyle.STRICT)).toInstant();
                default -> throw error("UNKNOWN_VALUE_TYPE", path, "属性值类型未登记");
            };
        } catch (java.time.DateTimeException | IllegalArgumentException ex) {
            if (ex instanceof RuleDslException dsl) { throw dsl; }
            throw error("VALUE_INVALID", path, "值不符合属性类型、范围或精度合同");
        }
    }

    private static BigDecimal decimal(String text, int scale) {
        if (!DECIMAL.matcher(text).matches()) { throw new IllegalArgumentException(); }
        BigDecimal value = new BigDecimal(text);
        // 先检查原始 scale；归一化不能使超精度输入通过。
        if (value.scale() > scale || value.precision() - value.scale() > PRECISION - scale) {
            throw new IllegalArgumentException();
        }
        return value.stripTrailingZeros();
    }

    static int compare(Object left, Object right) {
        if (left instanceof String a && right instanceof String b) {
            int ai = 0;
            int bi = 0;
            while (ai < a.length() && bi < b.length()) {
                int ac = a.codePointAt(ai);
                int bc = b.codePointAt(bi);
                if (ac != bc) { return Integer.compare(ac, bc); }
                ai += Character.charCount(ac);
                bi += Character.charCount(bc);
            }
            return Integer.compare(a.length() - ai, b.length() - bi);
        }
        if (left instanceof BigDecimal a) { return a.compareTo((BigDecimal) right); }
        if (left instanceof Long a) { return a.compareTo((Long) right); }
        if (left instanceof Boolean a) { return a.compareTo((Boolean) right); }
        if (left instanceof LocalDate a) { return a.compareTo((LocalDate) right); }
        return ((java.time.Instant) left).compareTo((java.time.Instant) right);
    }
}
