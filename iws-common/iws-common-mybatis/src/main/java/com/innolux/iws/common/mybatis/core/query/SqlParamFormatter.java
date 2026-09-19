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
 * SQL 参数格式化器。
 *
 * @author YONGJIE.PENG
 */
@FunctionalInterface
public interface SqlParamFormatter {

    /**
     * 格式化 SQL 参数。
     *
     * @param value 参数值
     * @return MyBatis 参数占位符
     */
    String format(Object value);

}
