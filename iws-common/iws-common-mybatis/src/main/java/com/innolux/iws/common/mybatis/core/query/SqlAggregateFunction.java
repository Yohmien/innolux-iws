/*
 * @Date         : 2026-09-19
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.mybatis.core.query;

/**
 * SQL 标准聚合函数。
 *
 * @author YONGJIE.PENG
 */
public enum SqlAggregateFunction {

    /**
     * 求和。
     */
    SUM("SUM"),

    /**
     * 最大值。
     */
    MAX("MAX"),

    /**
     * 最小值。
     */
    MIN("MIN"),

    /**
     * 平均值。
     */
    AVG("AVG"),

    /**
     * 计数。
     */
    COUNT("COUNT");

    /**
     * 聚合函数名称。
     */
    private final String name;

    /**
     * 构造 SQL 聚合函数。
     *
     * @param name 聚合函数名称
     */
    SqlAggregateFunction(String name) {
        this.name = name;
    }

    /**
     * 生成聚合函数 SQL 片段。
     *
     * @param expression 函数入参表达式
     * @return 聚合函数 SQL
     */
    public String format(String expression) {
        return name + "(" + expression + ")";
    }

}
