/*
 * @Date         : 2026-11-06
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 嵌入应用一次性桥接票据结果；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.domain;

/**
 * 嵌入应用一次性桥接票据。
 *
 * @param ticket 中心一次性票据
 * @param state 中心绑定状态
 * @param applicationId 目标应用标识
 * @param frontendId 目标受信前端
 * @param terminal 目标终端
 * @param returnPath 目标应用返回路径
 * @param expiresAt 过期时间戳
 */
public record IamBridgeTicketResponse(
    String ticket,
    String state,
    String applicationId,
    String frontendId,
    String terminal,
    String returnPath,
    long expiresAt
) {
}
