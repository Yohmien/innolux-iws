/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 为统一身份会话租约提供独立可替换时钟；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 统一身份时钟配置。
 */
@Configuration
public class IamClockConfig {

    /**
     * 提供 UTC 系统时钟；测试可构造独立配置替换。
     *
     * @return UTC 系统时钟
     */
    @Bean
    public Clock iamSessionClock() {
        return Clock.systemUTC();
    }
}
