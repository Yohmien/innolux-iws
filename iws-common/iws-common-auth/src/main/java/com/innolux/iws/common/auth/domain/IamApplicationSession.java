/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 同一权威账号与应用类型的唯一有效应用会话；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.domain;

/**
 * 唯一有效应用会话。
 *
 * @param issuer            权威签发方
 * @param applicationId     目标应用标识
 * @param applicationType   应用类型
 * @param frontendId        受信前端入口
 * @param userId            原系统用户主键
 * @param rootSid           中心根会话标识
 * @param sessionId         应用会话标识
 * @param sessionGeneration 应用会话代次
 * @param terminal          终端类型
 * @param transactionId     幂等登录事务标识
 * @param activatedAt       激活时间戳
 * @param expiresAt         过期时间戳
 */
public record IamApplicationSession(
    String issuer,
    String applicationId,
    String applicationType,
    String frontendId,
    Long userId,
    String rootSid,
    String sessionId,
    long sessionGeneration,
    String terminal,
    String transactionId,
    long activatedAt,
    long expiresAt
) {
}
