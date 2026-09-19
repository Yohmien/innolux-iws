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

package com.aizuda.snailjob.server.starter.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 权限安全配置
 *
 * @author YONGJIE.PENG
 */
@Configuration
public class SecurityConfig {

    /**
     * 认证用户名。
     */
    @Value("${spring.boot.admin.client.username}")
    private String username;
    /**
     * 认证密码。
     */
    @Value("${spring.boot.admin.client.password}")
    private String password;

    /**
     * 注册 Actuator Basic Auth 过滤器。
     *
     * @return Actuator 过滤器注册对象
     */
    @Bean
    public FilterRegistrationBean<ActuatorAuthFilter> actuatorFilterRegistrationBean() {
        FilterRegistrationBean<ActuatorAuthFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new ActuatorAuthFilter(username, password));
        registrationBean.addUrlPatterns("/actuator", "/actuator/*");
        return registrationBean;
    }

}
