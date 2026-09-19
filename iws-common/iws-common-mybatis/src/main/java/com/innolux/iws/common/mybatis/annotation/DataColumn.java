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
 * 数据权限注解，用于标记数据权限的占位符关键字和替换值
 * <p>
 * 一个注解只能对应一个模板
 * </p>
 *
 * @author YONGJIE.PENG
 * @version 3.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DataColumn {

    /**
     * 数据权限模板的占位符关键字，默认为 "deptName"
     *
     * @return 占位符关键字数组
     */
    String[] key() default "deptName";

    /**
     * 数据权限模板的占位符替换值，默认为 "dept_id"
     *
     * @return 占位符替换值数组
     */
    String[] value() default "dept_id";

}
