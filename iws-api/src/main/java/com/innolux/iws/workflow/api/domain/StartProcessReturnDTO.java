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

package com.innolux.iws.workflow.api.domain;

/**
 * 启动流程后的返回结果对象。
 *
 * @param processInstanceId 流程实例 ID
 * @param taskId            首个任务 ID
 * @author YONGJIE.PENG
 */
public record StartProcessReturnDTO(
    Long processInstanceId,
    Long taskId
) {
}
