/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 目标应用核销票据后获得的身份结果；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.domain;

/**
 * 一次性票据核销结果。
 *
 * @param issuer     权威签发方
 * @param applicationId 目标应用标识
 * @param applicationType 应用类型
 * @param frontendId 受信前端入口
 * @param userId     原系统用户主键
 * @param username   原系统账号
 * @param rootSid    中心根会话标识
 * @param sessionId  应用会话标识
 * @param sessionGeneration 应用会话代次
 * @param terminal   终端类型
 * @param instanceId 本次核销节点标识
 * @param returnPath 经中心校验的应用内返回路径
 * @param expiresAt  中心根会话过期时间戳
 */
public record IamTicketExchangeResponse(
    String issuer,
    String applicationId,
    String applicationType,
    String frontendId,
    Long userId,
    String username,
    String rootSid,
    String sessionId,
    long sessionGeneration,
    String terminal,
    String instanceId,
    String returnPath,
    long expiresAt
) {
}
