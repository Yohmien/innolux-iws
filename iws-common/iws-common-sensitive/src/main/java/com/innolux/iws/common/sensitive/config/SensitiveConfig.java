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

package com.innolux.iws.common.sensitive.config;

import com.innolux.iws.common.sensitive.handler.SensitiveJsonFieldProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 脱敏模块配置。
 */
@AutoConfiguration
public class SensitiveConfig {

    /**
     * 创建脱敏 JSON 字段处理器。
     *
     * @return 脱敏 JSON 字段处理器
     */
    @Bean
    public SensitiveJsonFieldProcessor sensitiveJsonFieldProcessor() {
        return new SensitiveJsonFieldProcessor();
    }

}
