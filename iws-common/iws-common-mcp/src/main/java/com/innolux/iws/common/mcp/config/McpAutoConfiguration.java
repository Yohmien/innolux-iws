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

package com.innolux.iws.common.mcp.config;

import io.modelcontextprotocol.client.McpSyncClient;
import com.innolux.iws.common.mcp.core.McpClientTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * MCP 公共模块自动配置。
 *
 * @author YONGJIE.PENG
 */
@AutoConfiguration
public class McpAutoConfiguration {

    /**
     * MCP Client 通用操作模板。
     */
    @Bean
    @ConditionalOnBean(McpSyncClient.class)
    @ConditionalOnMissingBean
    public McpClientTemplate mcpClientTemplate(List<McpSyncClient> mcpSyncClients) {
        return new McpClientTemplate(mcpSyncClients);
    }
}
