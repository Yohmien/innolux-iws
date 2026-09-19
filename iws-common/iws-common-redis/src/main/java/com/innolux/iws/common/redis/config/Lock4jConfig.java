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

package com.innolux.iws.common.redis.config;

import cn.hutool.http.HttpStatus;
import com.baomidou.lock.LockFailureStrategy;
import com.baomidou.lock.spring.boot.autoconfigure.LockAutoConfiguration;
import com.innolux.iws.common.core.exception.ServiceException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;

/**
 * Lock4j 配置
 *
 * @author YONGJIE.PENG
 */
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
@AutoConfiguration(before = LockAutoConfiguration.class)
public class Lock4jConfig {

    /**
     * Lock4j 获取锁失败策略。
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public LockFailureStrategy defaultLockFailureStrategy() {
        return (key, method, arguments) -> {
            throw new ServiceException("业务处理中，请稍后再试...", HttpStatus.HTTP_UNAVAILABLE);
        };
    }

}
