/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 下游应用校验根会话和应用占位的请求；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.domain;

/**
 * 应用会话状态查询。
 *
 * @param rootSid          中心根会话标识
 * @param applicationId    目标应用标识
 * @param frontendId       受信前端入口
 * @param sessionId        应用会话标识
 * @param sessionGeneration 应用会话代次
 * @param terminal         终端类型
 */
public record IamSessionStatusRequest(
    String rootSid,
    String applicationId,
    String frontendId,
    String sessionId,
    long sessionGeneration,
    String terminal
) {
}
