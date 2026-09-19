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

package com.innolux.iws.common.json.enhance;

import tools.jackson.databind.introspect.AnnotatedMember;

import java.lang.annotation.Annotation;

/**
 * 响应字段上下文。
 */
public record JsonFieldContext(Object owner, String propertyName, AnnotatedMember member, Object value) {

    /**
     * 获取字段上的指定注解。
     *
     * @param annotationType 注解类型
     * @param <A>            注解类型
     * @return 注解对象
     */
    public <A extends Annotation> A getAnnotation(Class<A> annotationType) {
        return member == null ? null : member.getAnnotation(annotationType);
    }

}
