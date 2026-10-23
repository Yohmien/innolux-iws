/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 实例运行查询与命令 DTO；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 分支 token 摘要。
 *
 * @param tokenId token ID
 * @param tokenRole token 角色
 * @param tokenStatus token 状态
 * @param stateCode 当前状态
 * @param scopeKey 范围键
 * @param branchKey 分支键
 * @param blockedEventId 阻塞事件
 */
public record TokenSummaryVO(String tokenId, String tokenRole, String tokenStatus, String stateCode,
                             String scopeKey, String branchKey, String blockedEventId) {
}
