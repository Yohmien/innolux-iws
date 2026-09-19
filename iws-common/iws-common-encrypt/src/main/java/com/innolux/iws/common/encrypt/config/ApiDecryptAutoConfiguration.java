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

package com.innolux.iws.common.encrypt.config;

import jakarta.servlet.DispatcherType;
import com.innolux.iws.common.encrypt.filter.CryptoFilter;
import com.innolux.iws.common.encrypt.properties.ApiDecryptProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * api 解密自动配置
 *
 * @author YONGJIE.PENG
 */
@AutoConfiguration
@EnableConfigurationProperties(ApiDecryptProperties.class)
@ConditionalOnProperty(value = "api-decrypt.enabled", havingValue = "true")
public class ApiDecryptAutoConfiguration {

    /**
     * 注册 API 加解密过滤器。
     *
     * @param properties                   API 解密配置
     * @param requestMappingHandlerMapping 请求映射处理器
     * @param handlerExceptionResolver     异常处理器
     * @return API 加解密过滤器
     */
    @Bean
    @FilterRegistration(
        name = "cryptoFilter",
        urlPatterns = "/*",
        order = FilterRegistrationBean.HIGHEST_PRECEDENCE,
        dispatcherTypes = DispatcherType.REQUEST
    )
    public CryptoFilter cryptoFilter(ApiDecryptProperties properties,
                                     RequestMappingHandlerMapping requestMappingHandlerMapping,
                                     HandlerExceptionResolver handlerExceptionResolver) {
        return new CryptoFilter(properties, requestMappingHandlerMapping, handlerExceptionResolver);
    }

}
