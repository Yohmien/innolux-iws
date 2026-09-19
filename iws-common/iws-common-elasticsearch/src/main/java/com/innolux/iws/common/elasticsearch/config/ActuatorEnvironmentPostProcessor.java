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

package com.innolux.iws.common.elasticsearch.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 健康检查配置注入
 *
 * @author YONGJIE.PENG
 */
public class ActuatorEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /**
     * 根据 easy-es 开关同步设置 Elasticsearch 健康检查开关。
     *
     * @param environment Spring 环境配置
     * @param application Spring 应用实例
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String enable = environment.getProperty("easy-es.enable", "false");
        System.setProperty("management.health.elasticsearch.enabled", enable);
    }

    /**
     * 返回环境后处理器执行顺序。
     *
     * @return 最高优先级
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

}
