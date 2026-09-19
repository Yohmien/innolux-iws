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
 * 抄送
 *
 * @param userId   抄送用户 ID
 * @param nickName 抄送用户昵称
 * @author YONGJIE.PENG
 */
public record FlowCopyDTO(
    Long userId,
    String nickName
) {
}
