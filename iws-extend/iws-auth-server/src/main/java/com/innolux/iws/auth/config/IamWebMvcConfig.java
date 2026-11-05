/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 提供中心独立前端静态资源与 SPA 回退；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.auth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 中心前端资源映射。
 */
@Configuration
public class IamWebMvcConfig implements WebMvcConfigurer {

    /**
     * 将 /iam 前端路由映射到独立构建产物。
     *
     * @param registry 资源注册表
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/iam/**")
            .addResourceLocations("classpath:/static/iam/")
            .resourceChain(true);
    }
}
