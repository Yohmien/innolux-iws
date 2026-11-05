/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 验证中心离线和中心恢复时的会话租约边界；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.web.service;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamSessionStatusResponse;
import com.innolux.iws.common.auth.utils.IamCredentialFingerprint;
import com.innolux.iws.common.core.constant.SystemConstants;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.core.service.PermissionService;
import com.innolux.iws.common.mybatis.helper.DataPermissionHelper;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.system.api.model.LoginUser;
import com.innolux.iws.system.domain.vo.SysUserVo;
import com.innolux.iws.system.service.ISysUserService;
import com.innolux.iws.web.config.IamClientProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IamSessionValidationService 租约和恢复边界测试。
 */
@Tag("local")
class IamSessionValidationServiceTest {

    private static final String TOKEN = "isolated-token";
    private static final Long USER_ID = 7L;
    private static final String PASSWORD_HASH = "isolated-password-hash";
    private static final long VALIDATED_AT = 1_760_000_000_000L;

    @Test
    void centerOutageDoesNotRefreshLeaseAndExpiresAfterLease() {
        IamClientProperties properties = properties();
        IamCenterClient center = mock(IamCenterClient.class);
        when(center.status(anyString(), anyString(), anyString(), any(Long.class), anyString()))
            .thenThrow(new ServiceException("统一身份中心不可达", 503));
        SaSession session = session();
        ISysUserService users = mock(ISysUserService.class);
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.getMenuPermission(USER_ID)).thenReturn(Set.of("iam:app:iws:a"));
        SysUserVo user = activeUser();
        when(users.selectUserById(USER_ID)).thenReturn(user);

        IamSessionValidationService withinLease = service(
            properties, center, users, permissions, session, VALIDATED_AT + 20_000L
        );
        try (MockedStatic<DataPermissionHelper> ignored = ignoreDataPermission()) {
            try (MockedStatic<LoginHelper> login = loginHelper()) {
                try (MockedStatic<StpUtil> stp = stp(session)) {
                    assertTrue(withinLease.validate(TOKEN, true).valid());
                    verify(session, never()).set(argThat("iamRootValidatedAt"::equals), any());
                }
            }
        }

        IamSessionValidationService afterLease = service(
            properties, center, users, permissions, session, VALIDATED_AT + 31_000L
        );
        try (MockedStatic<DataPermissionHelper> ignored = ignoreDataPermission()) {
            try (MockedStatic<LoginHelper> login = loginHelper()) {
                try (MockedStatic<StpUtil> stp = stp(session)) {
                    IamSessionValidationService.ValidationResult result =
                        afterLease.validate(TOKEN, true);
                    assertFalse(result.valid());
                    assertEquals(
                        IamSessionValidationService.ValidationResult.Status.CENTER_UNAVAILABLE,
                        result.status()
                    );
                    verify(session, never()).set(argThat("iamRootValidatedAt"::equals), any());
                }
            }
        }
    }

    @Test
    void successfulCenterAuthenticationRefreshesLease() {
        IamClientProperties properties = properties();
        IamCenterClient center = mock(IamCenterClient.class);
        when(center.status(anyString(), anyString(), anyString(), any(Long.class), anyString()))
            .thenReturn(new IamSessionStatusResponse(
                true,
                IamProtocolConstants.ISSUER,
                USER_ID,
                "isolated",
                VALIDATED_AT + 60_000L,
                null
            ));
        SaSession session = session();
        ISysUserService users = mock(ISysUserService.class);
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.getMenuPermission(USER_ID)).thenReturn(Set.of("iam:app:iws:a"));
        SysUserVo user = activeUser();
        when(users.selectUserById(USER_ID)).thenReturn(user);

        IamSessionValidationService service = service(
            properties, center, users, permissions, session, VALIDATED_AT + 1_000L
        );
        try (MockedStatic<DataPermissionHelper> ignored = ignoreDataPermission()) {
            try (MockedStatic<LoginHelper> login = loginHelper()) {
                try (MockedStatic<StpUtil> stp = stp(session)) {
                    assertTrue(service.validate(TOKEN, true).valid());
                    verify(session).set("iamRootValidatedAt", VALIDATED_AT + 1_000L);
                }
            }
        }
    }

    @Test
    void leaseStillChecksLocalPasswordAndPermission() {
        SaSession session = session();
        IamClientProperties properties = properties();
        IamCenterClient center = mock(IamCenterClient.class);
        when(center.status(anyString(), anyString(), anyString(), any(Long.class), anyString()))
            .thenThrow(new ServiceException("统一身份中心不可达", 503));
        ISysUserService users = mock(ISysUserService.class);
        PermissionService permissions = mock(PermissionService.class);
        SysUserVo user = activeUser();
        when(users.selectUserById(USER_ID)).thenReturn(user);
        when(permissions.getMenuPermission(USER_ID)).thenReturn(Set.of("other:permission"));
        IamSessionValidationService service = service(
            properties, center, users, permissions, session, VALIDATED_AT + 2_000L
        );

        try (MockedStatic<DataPermissionHelper> ignored = ignoreDataPermission()) {
            try (MockedStatic<LoginHelper> login = loginHelper()) {
                try (MockedStatic<StpUtil> stp = stp(session)) {
                    IamSessionValidationService.ValidationResult result =
                        service.validate(TOKEN, true);
                    assertFalse(result.valid());
                    assertEquals(
                        IamSessionValidationService.ValidationResult.Status.INVALID,
                        result.status()
                    );
                }
            }
        }
    }

    private IamSessionValidationService service(
        IamClientProperties properties,
        IamCenterClient center,
        ISysUserService users,
        PermissionService permissions,
        SaSession session,
        long now
    ) {
        return new IamSessionValidationService(
            center,
            properties,
            users,
            permissions,
            Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
        );
    }

    private IamClientProperties properties() {
        IamClientProperties properties = new IamClientProperties();
        properties.setApplicationId("iws-a");
        properties.setApplicationType("IWS");
        properties.setSessionNamespace("iam-iws-a-test");
        properties.setAccessPermission("iam:app:iws:a");
        properties.setRootValidationLeaseSeconds(30);
        return properties;
    }

    private SaSession session() {
        Map<String, Object> values = new HashMap<>();
        values.put(IamProtocolConstants.ROOT_SID_SESSION_KEY, "root-1");
        values.put(IamProtocolConstants.FRONTEND_ID_SESSION_KEY, "web-main");
        values.put(IamProtocolConstants.APPLICATION_ID_SESSION_KEY, "iws-a");
        values.put(IamProtocolConstants.APPLICATION_TYPE_SESSION_KEY, "IWS");
        values.put(IamProtocolConstants.APPLICATION_SESSION_ID_KEY, "session-1");
        values.put(IamProtocolConstants.APPLICATION_SESSION_GENERATION_KEY, 1L);
        values.put(IamProtocolConstants.TERMINAL_SESSION_KEY, "WEB");
        values.put("iamSessionNamespace", "iam-iws-a-test");
        values.put("iamRootValidatedAt", VALIDATED_AT);
        values.put(
            IamProtocolConstants.CREDENTIAL_FINGERPRINT_KEY,
            IamCredentialFingerprint.of(PASSWORD_HASH)
        );
        SaSession session = mock(SaSession.class);
        when(session.get(anyString())).thenAnswer(invocation -> values.get(invocation.getArgument(0)));
        return session;
    }

    private SysUserVo activeUser() {
        SysUserVo user = mock(SysUserVo.class);
        when(user.getStatus()).thenReturn(SystemConstants.NORMAL);
        when(user.getPassword()).thenReturn(PASSWORD_HASH);
        return user;
    }

    private MockedStatic<StpUtil> stp(SaSession session) {
        MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
        stp.when(() -> StpUtil.getLoginIdByToken(TOKEN)).thenReturn("sys_user:" + USER_ID);
        stp.when(() -> StpUtil.getTokenSessionByToken(TOKEN)).thenReturn(session);
        return stp;
    }

    private MockedStatic<LoginHelper> loginHelper() {
        LoginUser loginUser = mock(LoginUser.class);
        when(loginUser.getUserId()).thenReturn(USER_ID);
        MockedStatic<LoginHelper> helper = mockStatic(LoginHelper.class);
        helper.when(() -> LoginHelper.getLoginUser(TOKEN)).thenReturn(loginUser);
        return helper;
    }

    private MockedStatic<DataPermissionHelper> ignoreDataPermission() {
        MockedStatic<DataPermissionHelper> ignored = mockStatic(DataPermissionHelper.class);
        ignored.when(() -> DataPermissionHelper.ignore(any(Supplier.class)))
            .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
        return ignored;
    }
}
