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
 * 推送消息类型枚举
 *
 * @author YONGJIE.PENG
 */
@Getter
@AllArgsConstructor
public enum PushTypeEnum {

    /**
     * 通用消息
     */
    MESSAGE("message"),

    /**
     * 通知公告
     */
    NOTICE("notice"),

    /**
     * 大模型消息
     */
    LLM("llm"),

    /**
     * 自定义消息
     */
    CUSTOM("custom");

    /**
     * 消息类型标识。
     */
    private final String type;
}
