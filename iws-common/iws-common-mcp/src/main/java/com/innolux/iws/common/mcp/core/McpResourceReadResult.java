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

package com.innolux.iws.common.mcp.core;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

/**
 * MCP 资源读取结果。
 *
 * @author YONGJIE.PENG
 */
public record McpResourceReadResult(
    String serverName,
    List<McpSchema.ResourceContents> contents
) {

    /**
     * 将 MCP SDK 资源读取结果转换为带服务端名称的项目结果对象。
     *
     * @param serverName MCP 服务端名称
     * @param result     MCP SDK 资源读取结果
     * @return MCP 资源读取结果
     */
    public static McpResourceReadResult of(String serverName, McpSchema.ReadResourceResult result) {
        return new McpResourceReadResult(serverName, result.contents());
    }
}
