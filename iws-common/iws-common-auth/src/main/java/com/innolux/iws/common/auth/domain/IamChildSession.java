/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 根会话关联的下游应用节点会话；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.domain;

/**
 * 下游应用节点会话。
 *
 * @param applicationId 目标应用标识
 * @param applicationType 应用类型
 * @param frontendId    受信前端入口
 * @param userId        原系统用户主键
 * @param instanceId    目标节点标识
 * @param rootSid       中心根会话标识
 * @param sessionId     应用会话标识
 * @param sessionGeneration 应用会话代次
 * @param terminal      终端类型
 * @param expiresAt     应用会话过期时间戳
 * @param registeredAt  登记时间戳
 */
public record IamChildSession(
    String applicationId,
    String applicationType,
    String frontendId,
    Long userId,
    String instanceId,
    String rootSid,
    String sessionId,
    long sessionGeneration,
    String terminal,
    long expiresAt,
    long registeredAt
) {
}
