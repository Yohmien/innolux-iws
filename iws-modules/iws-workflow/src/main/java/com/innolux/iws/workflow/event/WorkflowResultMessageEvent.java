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

package com.innolux.iws.workflow.event;

import cn.hutool.core.collection.CollUtil;

import java.util.List;

/**
 * 工作流结果消息事件。
 *
 * @param flowName    流程名称
 * @param status      流程状态
 * @param createBy    发起人用户 ID
 * @param messageType 消息类型
 */
public record WorkflowResultMessageEvent(String flowName, String status, String createBy, List<String> messageType) {

    public WorkflowResultMessageEvent {
        if (CollUtil.isNotEmpty(messageType)) {
            messageType = List.copyOf(messageType);
        }
    }

}
