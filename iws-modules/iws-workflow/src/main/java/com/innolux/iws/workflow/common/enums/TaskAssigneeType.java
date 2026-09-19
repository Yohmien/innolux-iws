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

package com.innolux.iws.workflow.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 人员类型
 *
 * @author YONGJIE.PENG
 */
@Getter
@AllArgsConstructor
public enum TaskAssigneeType {

    /**
     * 待办任务的审批人权限
     * <p>该权限表示用户是待办任务的审批人，负责审核任务的执行情况。</p>
     */
    APPROVER("1", "待办任务的审批人权限"),

    /**
     * 待办任务的转办人权限
     * <p>该权限表示用户是待办任务的转办人，负责将任务分配给其他人员。</p>
     */
    TRANSFER("2", "待办任务的转办人权限"),

    /**
     * 待办任务的委托人权限
     * <p>该权限表示用户是待办任务的委托人，能够委托其他人代为处理任务。</p>
     */
    DELEGATE("3", "待办任务的委托人权限"),

    /**
     * 待办任务的抄送人权限
     * <p>该权限表示用户是待办任务的抄送人，仅接收任务信息的通知，不参与任务的审批或处理。</p>
     */
    COPY("4", "待办任务的抄送人权限");

    /**
     * 类型
     */
    private final String code;

    /**
     * 描述
     */
    private final String description;

}
