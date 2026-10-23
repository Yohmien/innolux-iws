/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 关闭 process 时移除全局 Mapper 扫描注册的 process Bean；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.config;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.context.EnvironmentAware;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * process 关闭时清除自身 BeanDefinition。
 *
 * <p>全局 {@code @MapperScan(com.innolux.iws.**.mapper)} 会把 process Mapper 注册成 Bean；
 * 本过滤器在注册阶段之后、实例化之前移除 process 命名空间的 Bean，避免关闭模块时留下 Mapper 或半装配组件。</p>
 */
@Component
public class ProcessDisabledBeanFilter implements BeanFactoryPostProcessor, Ordered, EnvironmentAware {

    private static final String PROCESS_PACKAGE = "com.innolux.iws.process.";

    private Environment environment;

    /**
     * 默认构造，保证 BFPP 可在自动装配前实例化。
     */
    public ProcessDisabledBeanFilter() {
    }

    /**
     * 由 Spring 早期注入 Environment。
     *
     * @param environment 环境属性
     */
    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    /**
     * 关闭时移除 process BeanDefinition。
     *
     * @param beanFactory Bean 工厂
     */
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        Environment resolved = environment == null ? beanFactory.getBean(Environment.class) : environment;
        if (Boolean.parseBoolean(resolved.getProperty("iws.process.enabled", "false"))) {
            return;
        }
        if (!(beanFactory instanceof DefaultListableBeanFactory registry)) {
            return;
        }
        List<String> remove = new ArrayList<>();
        for (String name : registry.getBeanDefinitionNames()) {
            BeanDefinition definition = registry.getBeanDefinition(name);
            String className = definition.getBeanClassName();
            if (className != null && className.startsWith(PROCESS_PACKAGE)) {
                remove.add(name);
                continue;
            }
            Object mapperInterface = definition.getPropertyValues().get("mapperInterface");
            if (mapperInterface instanceof String mapperName && mapperName.startsWith(PROCESS_PACKAGE)) {
                remove.add(name);
            }
        }
        for (String name : remove) {
            if (registry.containsBeanDefinition(name)) {
                registry.removeBeanDefinition(name);
            }
        }
    }

    /**
     * 最后执行，确保已看到全局 Mapper 扫描结果。
     *
     * @return 最低优先级
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
