/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : process 条件装配与启动门禁；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.config;

import com.innolux.iws.endpoint.catalog.EndpointCatalogReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * process 条件装配。
 *
 * <p>只有 {@code iws.process.enabled=true} 才注册本模块。启用后必须存在 endpoint 目录 Reader 和非空当前环境，
 * 缺失时启动直接失败，不能回退空目录或自动屏蔽。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProcessProperties.class)
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class ProcessConfiguration {

    /**
     * endpoint 目录实现存在时的启动门禁。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.innolux.iws.endpoint.catalog.EndpointCatalogReader")
    @ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
    static class EndpointPresentConfiguration {

        /**
         * 校验目录 Reader 和当前环境映射。
         *
         * @param context Spring 上下文
         * @param reader endpoint 目录 Reader
         * @return 启动门禁标记
         */
        @Bean
        ProcessDependencyGuard processDependencyGuard(ApplicationContext context, EndpointCatalogReader reader) {
            String environmentCode = reader.currentEnvironmentCode();
            if (environmentCode == null || environmentCode.isBlank()) {
                throw new IllegalStateException("iws.process.enabled=true 需要配置 iws.endpoint.current-environment 并指向已启用环境");
            }
            boolean enabled = reader.snapshot().environments().stream()
                .anyMatch(item -> item.environmentCode().equals(environmentCode) && item.enabled());
            if (!enabled) {
                throw new IllegalStateException("iws.process.enabled=true 的当前环境不存在或未启用: " + environmentCode);
            }
            return new ProcessDependencyGuard(environmentCode);
        }
    }

    /**
     * endpoint 目录实现缺失时的明确失败门禁。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("com.innolux.iws.endpoint.catalog.EndpointCatalogReader")
    @ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
    static class EndpointMissingConfiguration {

        /**
         * 缺失 endpoint 时阻断启动。
         *
         * @return 不返回，始终抛错
         */
        @Bean
        static org.springframework.beans.factory.config.BeanFactoryPostProcessor missingEndpointGuard() {
            return beanFactory -> {
                throw new IllegalStateException("iws.process.enabled=true 但缺少 iws-endpoint 模块或 EndpointCatalogReader Bean");
            };
        }
    }

    /**
     * 启动门禁结果，只用于确认装配完成。
     *
     * @param environmentCode 当前运行环境
     */
    public record ProcessDependencyGuard(String environmentCode) {
    }
}
