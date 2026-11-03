/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 条件 DSL
 * @Version      : V1.0
 * @Description  : 独立表达式限额，不复用装载树或作业批量配置；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.dsl;

/** 表达式根深度为 1；节点数包含复合节点，字符串长度按 Unicode 码点计数。 */
public record DslLimits(int maxDepth, int maxNodes, int maxChildren, int maxStringCodePoints) {
    public static final DslLimits DEFAULT = new DslLimits(16, 256, 32, 4096);

    public DslLimits {
        if (maxDepth < 1 || maxNodes < 1 || maxChildren < 2 || maxStringCodePoints < 1) {
            throw new IllegalArgumentException("DSL 限额必须为正，maxChildren 至少为 2");
        }
    }
}
