/*
 * @Date         : 2026-11-06
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 使用同源 HttpOnly 恢复凭据静默恢复当前 IWS 应用会话；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.web.service;

import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamSessionStatusResponse;
import com.innolux.iws.common.auth.domain.IamTicketExchangeResponse;
import com.innolux.iws.common.auth.utils.IamCredentialFingerprint;
import com.innolux.iws.common.auth.utils.IamSecureTokens;
import com.innolux.iws.common.core.constant.SystemConstants;
import com.innolux.iws.common.core.service.PermissionService;
import com.innolux.iws.system.api.model.LoginUser;
import com.innolux.iws.system.domain.vo.SysClientVo;
import com.innolux.iws.system.domain.vo.SysUserVo;
import com.innolux.iws.system.service.ISysClientService;
import com.innolux.iws.system.service.ISysUserService;
import com.innolux.iws.web.config.IamClientProperties;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/**
 * IWS 应用会话静默恢复服务。
 */
@Service
@RequiredArgsConstructor
public class IamSessionResumeService {

    private static final String COOKIE_NAME_PREFIX = "IWS_SSO_RESUME_";
    private static final String RESUME_KEY_PREFIX = "iam:client-session-resume:";

    private final IamClientProperties properties;
    private final IamCenterClient centerClient;
    private final ISysUserService userService;
    private final ISysClientService clientService;
    private final PermissionService permissionService;
    private final SysLoginService loginService;
    private final LoginSessionService loginSessionService;
    private final RedissonClient redis;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /**
     * 为刚完成的中心票据核销登记可恢复会话。
     *
     * @param identity 中心核销结果
     * @param clientId 原登录渠道
     * @return 写入 Cookie 的随机恢复标识
     */
    public String issue(IamTicketExchangeResponse identity, String clientId) {
        String resumeToken = IamSecureTokens.randomToken();
        SessionRecord record = new SessionRecord(
            properties.getApplicationId(),
            identity.frontendId(),
            identity.rootSid(),
            identity.sessionId(),
            identity.sessionGeneration(),
            identity.terminal(),
            identity.userId(),
            clientId
        );
        redis.getBucket(RESUME_KEY_PREFIX + resumeToken)
            .set(jsonMapper.writeValueAsString(record), properties.getSessionResumeTtl());
        return resumeToken;
    }

    /**
     * 恢复指定凭据仍指向的当前应用会话。
     *
     * @param resumeToken 恢复 Cookie 值
     * @return 新本地会话；凭据或中心会话无效时返回 null
     */
    public ResumeSession resume(String resumeToken) {
        String recordKey = resumeKey(resumeToken);
        SessionRecord record = readRecord(recordKey);
        if (record == null || !matchesCurrentApplication(record)) {
            redis.getBucket(recordKey).delete();
            return null;
        }
        IamSessionStatusResponse status = centerClient.status(
            record.rootSid(),
            record.frontendId(),
            record.sessionId(),
            record.sessionGeneration(),
            record.terminal()
        );
        if (!status.active() || !record.userId().equals(status.userId())) {
            redis.getBucket(recordKey).delete();
            return null;
        }
        SysClientVo client = clientService.queryByClientId(record.clientId());
        if (client == null || !SystemConstants.NORMAL.equals(client.getStatus())) {
            redis.getBucket(recordKey).delete();
            return null;
        }
        SysUserVo user = userService.selectUserById(record.userId());
        if (user == null || SystemConstants.DISABLE.equals(user.getStatus())
            || !hasApplicationAccess(permissionService.getMenuPermission(user.getUserId()))) {
            redis.getBucket(recordKey).delete();
            return null;
        }
        LoginUser loginUser = loginService.buildLoginUser(user);
        LoginSessionService.LocalSession localSession = loginSessionService.login(
            loginUser, client, record.rootSid()
        );
        try {
            publishSessionContext(record, user);
        } catch (RuntimeException error) {
            loginService.logout();
            throw error;
        }
        IamTicketExchangeResponse identity = new IamTicketExchangeResponse(
            IamProtocolConstants.ISSUER,
            record.applicationId(),
            properties.getApplicationType(),
            record.frontendId(),
            record.userId(),
            user.getUserName(),
            record.rootSid(),
            record.sessionId(),
            record.sessionGeneration(),
            record.terminal(),
            properties.getInstanceId(),
            "/",
            status.expiresAt()
        );
        return new ResumeSession(identity, localSession, record.clientId());
    }

    /**
     * 退出时删除恢复凭据。
     *
     * @param resumeToken 恢复 Cookie 值
     */
    public void revoke(String resumeToken) {
        if (resumeToken != null && !resumeToken.isBlank()) {
            redis.getBucket(resumeKey(resumeToken)).delete();
        }
    }

    /**
     * 返回当前应用独立的恢复 Cookie 名称。
     *
     * @return Cookie 名称
     */
    public String cookieName() {
        return COOKIE_NAME_PREFIX
            + properties.getApplicationId().replaceAll("[^A-Za-z0-9]", "_").toUpperCase();
    }

    private SessionRecord readRecord(String recordKey) {
        RBucket<String> bucket = redis.getBucket(recordKey);
        String payload = bucket.get();
        if (payload == null) {
            return null;
        }
        try {
            return jsonMapper.readValue(payload, SessionRecord.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean matchesCurrentApplication(SessionRecord record) {
        return properties.getApplicationId().equals(record.applicationId())
            && record.userId() != null
            && record.frontendId() != null
            && !record.frontendId().isBlank()
            && record.rootSid() != null
            && !record.rootSid().isBlank()
            && record.sessionId() != null
            && !record.sessionId().isBlank()
            && record.sessionGeneration() > 0
            && record.terminal() != null
            && !record.terminal().isBlank()
            && record.clientId() != null
            && !record.clientId().isBlank();
    }

    private boolean hasApplicationAccess(Set<String> permissions) {
        String required = properties.getAccessPermission();
        return required == null || required.isBlank()
            || permissions.contains("*:*:*")
            || permissions.contains(required);
    }

    private void publishSessionContext(SessionRecord record, SysUserVo user) {
        StpUtil.getTokenSession().set(
            "iamRootValidatedAt", Instant.now(clock).toEpochMilli()
        );
        StpUtil.getTokenSession().set(
            IamProtocolConstants.CREDENTIAL_FINGERPRINT_KEY,
            IamCredentialFingerprint.of(user.getPassword())
        );
        StpUtil.getTokenSession().set(
            IamProtocolConstants.APPLICATION_ID_SESSION_KEY, properties.getApplicationId()
        );
        StpUtil.getTokenSession().set(
            IamProtocolConstants.APPLICATION_TYPE_SESSION_KEY, properties.getApplicationType()
        );
        StpUtil.getTokenSession().set(
            IamProtocolConstants.FRONTEND_ID_SESSION_KEY, record.frontendId()
        );
        StpUtil.getTokenSession().set(
            IamProtocolConstants.INSTANCE_ID_SESSION_KEY, properties.getInstanceId()
        );
        StpUtil.getTokenSession().set(
            IamProtocolConstants.APPLICATION_SESSION_ID_KEY, record.sessionId()
        );
        StpUtil.getTokenSession().set(
            IamProtocolConstants.APPLICATION_SESSION_GENERATION_KEY, record.sessionGeneration()
        );
        StpUtil.getTokenSession().set(
            IamProtocolConstants.TERMINAL_SESSION_KEY, record.terminal()
        );
        StpUtil.getTokenSession().set("iamSessionNamespace", properties.getSessionNamespace());
    }

    private String resumeKey(String resumeToken) {
        return RESUME_KEY_PREFIX + resumeToken;
    }

    /**
     * 可恢复会话结果。
     *
     * @param identity     中心会话身份
     * @param localSession 新 IWS 本地会话
     * @param clientId     原登录渠道
     */
    public record ResumeSession(
        IamTicketExchangeResponse identity,
        LoginSessionService.LocalSession localSession,
        String clientId
    ) {
    }

    /**
     * Redis 中的服务端恢复记录。
     */
    record SessionRecord(
        String applicationId,
        String frontendId,
        String rootSid,
        String sessionId,
        long sessionGeneration,
        String terminal,
        Long userId,
        String clientId
    ) {
    }
}
