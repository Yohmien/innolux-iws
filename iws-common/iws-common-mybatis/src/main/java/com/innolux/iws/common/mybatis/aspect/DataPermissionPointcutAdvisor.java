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

package com.innolux.iws.common.mybatis.aspect;

import org.aopalliance.aop.Advice;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractPointcutAdvisor;

/**
 * 数据权限注解切面定义
 *
 * @author YONGJIE.PENG
 */
@SuppressWarnings("all")
public class DataPermissionPointcutAdvisor extends AbstractPointcutAdvisor {

    /**
     * 数据权限通知逻辑。
     */
    private final Advice advice;

    /**
     * 数据权限切点匹配器。
     */
    private final Pointcut pointcut;

    /**
     * 构造数据权限切面定义。
     */
    public DataPermissionPointcutAdvisor() {
        this.advice = new DataPermissionAdvice();
        this.pointcut = new DataPermissionPointcut();
    }

    /**
     * 获取数据权限切点。
     *
     * @return 数据权限切点
     */
    @Override
    public Pointcut getPointcut() {
        return this.pointcut;
    }

    /**
     * 获取数据权限通知。
     *
     * @return 数据权限通知
     */
    @Override
    public Advice getAdvice() {
        return this.advice;
    }

}
