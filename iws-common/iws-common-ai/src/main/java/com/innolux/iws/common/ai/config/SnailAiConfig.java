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

package com.innolux.iws.common.ai.config;

import com.aizuda.snail.ai.agent.starter.EnableSnailAiAgent;
import com.aizuda.snail.ai.openapi.client.starter.EnableSnailAiOpenApi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Snail AI 自动配置
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "snail-ai", name = "enabled", havingValue = "true")
@EnableSnailAiAgent
@EnableSnailAiOpenApi
public class SnailAiConfig {
}
