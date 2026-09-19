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

package com.innolux.iws.common.mybatis.annotation;

import java.lang.annotation.*;

/**
 * 数据权限组注解，用于标记数据权限配置数组
 *
 * @author YONGJIE.PENG
 * @version 3.5.0
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DataPermission {

    /**
     * 数据权限配置数组，用于指定数据权限的占位符关键字和替换值
     *
     * @return 数据权限配置数组
     */
    DataColumn[] value();

    /**
     * 权限拼接标识符(用于指定连接语句的sql符号)
     * 如不填 默认 select 用 OR 其他语句用 AND
     * 内容 OR 或者 AND
     */
    String joinStr() default "";

}
