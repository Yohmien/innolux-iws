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

package com.innolux.iws.common.web.config;

import com.innolux.iws.common.web.core.I18nLocaleResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.LocaleResolver;

/**
 * 国际化配置
 *
 * @author YONGJIE.PENG
 */
@AutoConfiguration(before = WebMvcAutoConfiguration.class)
public class I18nConfig {

    /**
     * 注册自定义国际化区域解析器。
     *
     * @return Locale 解析器实例
     */
    @Bean
    public LocaleResolver localeResolver() {
        return new I18nLocaleResolver();
    }

}
