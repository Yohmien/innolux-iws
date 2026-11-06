/*
 * @Date         : 2026-11-06
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 验证同源恢复凭据只在中心会话仍有效时重建应用 Token；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.web.service;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamSessionStatusResponse;
import com.innolux.iws.common.auth.domain.IamTicketExchangeResponse;
import com.innolux.iws.common.core.constant.SystemConstants;
import com.innolux.iws.common.core.service.PermissionService;
import com.innolux.iws.system.api.model.LoginUser;
import com.innolux.iws.system.domain.vo.SysClientVo;
import com.innolux.iws.system.domain.vo.SysUserVo;
import com.innolux.iws.system.service.ISysClientService;
import com.innolux.iws.system.service.ISysUserService;
import com.innolux.iws.web.config.IamClientProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IamSessionResumeService 测试。
 */
@Tag("local")
class IamSessionResumeServiceTest {

    private static final String RESUME_TOKEN = "resume-token";
    private static final String CLIENT_ID = "e5cd7e4891bf95d1d19206ce24a7b32e";
    private static final Long USER_ID = 7L;

    @Test
    void resumeRevalidatesCenterAndPublishesCurrentApplicationContext() {
        Fixture fixture = fixture(activeRecord());
        when(fixture.center.status(
            "root-1", "web-main", "session-1", 3L, "WEB"
        )).thenReturn(activeStatus());
        SysClientVo client = new SysClientVo();
        client.setClientId(CLIENT_ID);
        client.setStatus(SystemConstants.NORMAL);
        when(fixture.clients.queryByClientId(CLIENT_ID)).thenReturn(client);
        SysUserVo user = activeUser();
        when(fixture.users.selectUserById(USER_ID)).thenReturn(user);
        when(fixture.permissions.getMenuPermission(USER_ID))
            .thenReturn(Set.of("iam:app:iws:a"));
        LoginUser loginUser = mock(LoginUser.class);
        when(fixture.loginService.buildLoginUser(user)).thenReturn(loginUser);
        when(fixture.loginSessions.login(loginUser, client, "root-1"))
            .thenReturn(new LoginSessionService.LocalSession("local-token", 1800L, CLIENT_ID));

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getTokenSession).thenReturn(fixture.tokenSession);
            IamSessionResumeService.ResumeSession resumed = fixture.service.resume(RESUME_TOKEN);

            assertEquals("local-token", resumed.localSession().accessToken());
            assertEquals("session-1", resumed.identity().sessionId());
            verify(fixture.tokenSession).set(
                IamProtocolConstants.APPLICATION_SESSION_ID_KEY, "session-1"
            );
            verify(fixture.tokenSession).set(
                IamProtocolConstants.APPLICATION_SESSION_GENERATION_KEY, 3L
            );
            verify(fixture.tokenSession).set("iamSessionNamespace", "innolux-iws-a");
        }
    }

    @Test
    void missingResumeRecordDoesNotIssueToken() {
        Fixture fixture = fixture(null);

        assertNull(fixture.service.resume(RESUME_TOKEN));
        verify(fixture.loginSessions, never()).login(any(), any(), anyString());
    }

    @Test
    void inactiveCenterSessionDeletesRecordWithoutIssuingToken() {
        Fixture fixture = fixture(activeRecord());
        when(fixture.center.status(
            "root-1", "web-main", "session-1", 3L, "WEB"
        )).thenReturn(new IamSessionStatusResponse(
            false,
            IamProtocolConstants.ISSUER,
            USER_ID,
            "operator",
            Instant.now().toEpochMilli(),
            "ROOT_SESSION_EXPIRED_OR_REVOKED"
        ));

        assertNull(fixture.service.resume(RESUME_TOKEN));
        verify(fixture.bucket).delete();
        verify(fixture.loginSessions, never()).login(any(), any(), anyString());
    }

    @Test
    void resumeRecordRejectsAnotherApplication() {
        IamSessionResumeService.SessionRecord record = new IamSessionResumeService.SessionRecord(
            "iws-b", "web-main", "root-1", "session-1", 3L, "WEB", USER_ID, CLIENT_ID
        );
        Fixture fixture = fixture(record);

        assertNull(fixture.service.resume(RESUME_TOKEN));
        verify(fixture.bucket).delete();
        verify(fixture.center, never()).status(anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void issueStoresOpaqueResumeCredentialAndUsesApplicationCookieName() {
        Fixture fixture = fixture(null);

        String token = fixture.service.issue(resumedIdentity(), CLIENT_ID);

        assertEquals("IWS_SSO_RESUME_IWS_A", fixture.service.cookieName());
        verify(fixture.bucket).set(anyString(), eq(Duration.ofHours(8)));
        assertFalse(token.isBlank());
    }

    private Fixture fixture(IamSessionResumeService.SessionRecord record) {
        IamClientProperties properties = new IamClientProperties();
        properties.setApplicationId("iws-a");
        properties.setApplicationType("IWS");
        properties.setInstanceId("iws-a-1");
        properties.setSessionNamespace("innolux-iws-a");
        properties.setAccessPermission("iam:app:iws:a");
        properties.setSessionResumeTtl(Duration.ofHours(8));
        IamCenterClient center = mock(IamCenterClient.class);
        ISysUserService users = mock(ISysUserService.class);
        ISysClientService clients = mock(ISysClientService.class);
        PermissionService permissions = mock(PermissionService.class);
        SysLoginService loginService = mock(SysLoginService.class);
        LoginSessionService loginSessions = mock(LoginSessionService.class);
        RedissonClient redis = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<String> bucket = mock(RBucket.class);
        when(redis.<String>getBucket(anyString())).thenReturn(bucket);
        JsonMapper jsonMapper = JsonMapper.builder().build();
        when(bucket.get()).thenReturn(
            record == null ? null : jsonMapper.writeValueAsString(record)
        );
        SaSession tokenSession = mock(SaSession.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-06T07:00:00Z"), ZoneOffset.UTC);
        IamSessionResumeService service = new IamSessionResumeService(
            properties,
            center,
            users,
            clients,
            permissions,
            loginService,
            loginSessions,
            redis,
            jsonMapper,
            clock
        );
        return new Fixture(
            service, properties, center, users, clients, permissions,
            loginService, loginSessions, bucket, tokenSession
        );
    }

    private IamSessionResumeService.SessionRecord activeRecord() {
        return new IamSessionResumeService.SessionRecord(
            "iws-a", "web-main", "root-1", "session-1", 3L, "WEB", USER_ID, CLIENT_ID
        );
    }

    private IamSessionStatusResponse activeStatus() {
        return new IamSessionStatusResponse(
            true,
            IamProtocolConstants.ISSUER,
            USER_ID,
            "operator",
            Instant.now().plusSeconds(3600).toEpochMilli(),
            null
        );
    }

    private SysUserVo activeUser() {
        SysUserVo user = mock(SysUserVo.class);
        when(user.getUserId()).thenReturn(USER_ID);
        when(user.getUserName()).thenReturn("operator");
        when(user.getStatus()).thenReturn(SystemConstants.NORMAL);
        when(user.getPassword()).thenReturn("encoded-password");
        return user;
    }

    private IamTicketExchangeResponse resumedIdentity() {
        return new IamTicketExchangeResponse(
            IamProtocolConstants.ISSUER,
            "iws-a",
            "IWS",
            "web-main",
            USER_ID,
            "operator",
            "root-1",
            "session-1",
            3L,
            "WEB",
            "iws-a-1",
            "/",
            Instant.now().plusSeconds(3600).toEpochMilli()
        );
    }

    private record Fixture(
        IamSessionResumeService service,
        IamClientProperties properties,
        IamCenterClient center,
        ISysUserService users,
        ISysClientService clients,
        PermissionService permissions,
        SysLoginService loginService,
        LoginSessionService loginSessions,
        RBucket<String> bucket,
        SaSession tokenSession
    ) {
    }
}
