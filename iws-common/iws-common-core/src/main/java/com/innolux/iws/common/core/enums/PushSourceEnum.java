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

package com.innolux.iws.common.core.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 推送消息来源枚举
 *
 * @author YONGJIE.PENG
 */
@Getter
@AllArgsConstructor
public enum PushSourceEnum {

    /**
     * 后端系统消息
     */
    BACKEND("backend"),

    /**
     * 通知公告
     */
    NOTICE("notice"),

    /**
     * 工作流
     */
    WORKFLOW("workflow"),

    /**
     * 大模型
     */
    LLM("llm"),

    /**
     * 客户端消息
     */
    CLIENT("client");

    /**
     * 消息来源标识。
     */
    private final String source;
}
