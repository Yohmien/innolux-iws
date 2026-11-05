/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Admin 监控统一身份接入
 * @Version      : V1.0
 * @Description  : Admin 监控人员会话中的中心权威身份；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.monitor.admin.security;

import java.security.Principal;

/**
 * Admin 监控人员主体。
 *
 * @param userId         中心核验的原 user_id
 * @param username       原账号名
 * @param rootSid        中心根会话标识
 * @param applicationId  SSO 应用组标识
 * @param frontendId     受信前端标识
 * @param sessionId      应用会话标识
 * @param sessionGeneration 应用会话代次
 * @param terminal       终端类型
 * @param instanceId     当前节点标识
 * @param centerExpiresAt 中心根会话过期时间戳
 */
public record MonitorPrincipal(
    Long userId,
    String username,
    String rootSid,
    String applicationId,
    String frontendId,
    String sessionId,
    long sessionGeneration,
    String terminal,
    String instanceId,
    long centerExpiresAt
) implements Principal {

    /**
     * 返回页面可展示的原账号名，不暴露中心会话标识。
     *
     * @return 原账号名
     */
    @Override
    public String getName() {
        return username;
    }

    /**
     * 避免框架默认记录完整会话字段。
     *
     * @return 原账号名
     */
    @Override
    public String toString() {
        return username;
    }
}
