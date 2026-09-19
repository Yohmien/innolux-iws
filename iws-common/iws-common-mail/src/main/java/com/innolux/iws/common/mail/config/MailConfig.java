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

package com.innolux.iws.common.mail.config;

import cn.hutool.extra.mail.MailAccount;
import com.innolux.iws.common.mail.config.properties.MailProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * JavaMail 配置
 *
 * @author YONGJIE.PENG
 */
@AutoConfiguration
@EnableConfigurationProperties(MailProperties.class)
public class MailConfig {

    /**
     * 创建邮件账户配置。
     *
     * @param mailProperties 邮件配置属性
     * @return 邮件账户
     */
    @Bean
    @ConditionalOnProperty(value = "mail.enabled", havingValue = "true")
    public MailAccount mailAccount(MailProperties mailProperties) {
        return mailProperties.toMailAccount();
    }

}
