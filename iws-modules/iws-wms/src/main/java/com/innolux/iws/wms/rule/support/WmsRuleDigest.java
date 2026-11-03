/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则内容、规则包与空间快照的规范化 JSON 摘要；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.support;

import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 规则摘要计算。
 *
 * <p>摘要输入使用自实现的规范化 JSON：对象键按 Unicode 码点升序、无多余空白、字符串统一转义，
 * 因此同一文档不受 MySQL JSON 列回读时的键序和空白差异影响。三个摘要的输入定义为：</p>
 * <ul>
 *   <li>规则版本：{@code {"condition":<条件>,"result":<结果>}}，见
 *       {@link #ruleVersionDigest(JsonNode, JsonNode)}；</li>
 *   <li>规则包：{@code {"packageCode":<编码>,"members":[{"purpose":..,"ruleVersionId":..,
 *       "ruleDigest":..}]}}，成员按 purpose 升序，见 {@link #packageDigest}; </li>
 *   <li>空间快照：去重升序的储位 ID 数组，见 {@link #spaceSnapshotDigest}。</li>
 * </ul>
 *
 * <p>结果固定为 64 位小写十六进制，满足 DDL 的 {@code ^[0-9a-f]{64}$} 约束。</p>
 *
 * @author YONGJIE.PENG
 */
public final class WmsRuleDigest {

    private WmsRuleDigest() {
    }

    /**
     * 规则包成员摘要输入。
     *
     * @param purpose       成员用途
     * @param ruleVersionId 规则版本 ID
     * @param ruleDigest    规则版本规范化摘要
     */
    public record PackageMemberDigest(String purpose, Long ruleVersionId, String ruleDigest) {
    }

    /**
     * 计算规则版本规范化摘要。
     *
     * @param condition 条件 JSON
     * @param result    结果 JSON
     * @return 64 位小写 SHA-256
     */
    public static String ruleVersionDigest(JsonNode condition, JsonNode result) {
        return sha256("{\"condition\":" + canonical(condition)
            + ",\"result\":" + canonical(result) + "}");
    }

    /** v2 明确覆盖服务端绑定快照；null 仅用于草稿。 */
    public static String ruleVersionDigest(JsonNode condition, JsonNode result, JsonNode bindingSnapshot) {
        return sha256("{\"schema\":\"iws.rule.digest/2\",\"condition\":" + canonical(condition)
            + ",\"result\":" + canonical(result) + ",\"attributeBindings\":" + canonical(bindingSnapshot) + "}");
    }

    /**
     * 计算规则包规范化摘要。
     *
     * @param packageCode 规则包编码
     * @param members     成员摘要输入
     * @return 64 位小写 SHA-256
     */
    public static String packageDigest(String packageCode, List<PackageMemberDigest> members) {
        List<PackageMemberDigest> ordered = new ArrayList<>(members);
        ordered.sort(Comparator.comparing(PackageMemberDigest::purpose));
        StringBuilder content = new StringBuilder("{\"packageCode\":")
            .append(jsonString(packageCode))
            .append(",\"members\":[");
        for (int i = 0; i < ordered.size(); i++) {
            PackageMemberDigest member = ordered.get(i);
            if (i > 0) {
                content.append(',');
            }
            content.append("{\"purpose\":").append(jsonString(member.purpose()))
                .append(",\"ruleVersionId\":").append(member.ruleVersionId())
                .append(",\"ruleDigest\":").append(jsonString(member.ruleDigest()))
                .append('}');
        }
        content.append("]}");
        return sha256(content.toString());
    }

    /**
     * 计算逻辑归属空间快照摘要。
     *
     * @param storageIds 储位 ID 集合，允许重复和乱序
     * @return 64 位小写 SHA-256
     */
    public static String spaceSnapshotDigest(List<Long> storageIds) {
        List<Long> ordered = new ArrayList<>(new LinkedHashSet<>(storageIds));
        ordered.sort(Long::compareTo);
        StringBuilder content = new StringBuilder("[");
        for (int i = 0; i < ordered.size(); i++) {
            if (i > 0) {
                content.append(',');
            }
            content.append(ordered.get(i));
        }
        content.append(']');
        return sha256(content.toString());
    }

    /**
     * 生成规范化 JSON 文本。
     *
     * @param node JSON 节点
     * @return 规范化文本；null 节点输出 {@code null}
     */
    public static String canonical(JsonNode node) {
        StringBuilder out = new StringBuilder();
        appendCanonical(node, out);
        return out.toString();
    }

    /**
     * 计算 SHA-256 小写十六进制。
     *
     * @param text UTF-8 文本
     * @return 64 位小写十六进制
     */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256不可用", error);
        }
    }

    private static void appendCanonical(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull()) {
            out.append("null");
            return;
        }
        if (node.isObject()) {
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(node.properties());
            entries.sort(Map.Entry.comparingByKey());
            out.append('{');
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(jsonString(entries.get(i).getKey())).append(':');
                appendCanonical(entries.get(i).getValue(), out);
            }
            out.append('}');
            return;
        }
        if (node.isArray()) {
            out.append('[');
            int index = 0;
            for (JsonNode child : node) {
                if (index++ > 0) {
                    out.append(',');
                }
                appendCanonical(child, out);
            }
            out.append(']');
            return;
        }
        if (node.isTextual()) {
            out.append(jsonString(node.textValue()));
            return;
        }
        out.append(node.asText());
    }

    private static String jsonString(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (current < 0x20) {
                        out.append(String.format("\\u%04x", (int) current));
                    } else {
                        out.append(current);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
