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

package com.innolux.iws.common.sensitive.annotation;

import com.innolux.iws.common.sensitive.core.SensitiveStrategy;

import java.lang.annotation.*;

/**
 * 数据脱敏注解
 *
 * @author YONGJIE.PENG
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@Documented
public @interface Sensitive {

    /**
     * 脱敏策略。
     *
     * @return 脱敏策略
     */
    SensitiveStrategy strategy();

    /**
     * 角色标识符 多个角色满足一个即可
     */
    String[] roleKey() default {};

    /**
     * 权限标识符 多个权限满足一个即可
     */
    String[] perms() default {};
}
