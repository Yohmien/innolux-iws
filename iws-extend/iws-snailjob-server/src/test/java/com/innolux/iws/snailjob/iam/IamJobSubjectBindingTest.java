/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Job 统一身份接入
 * @Version      : V1.0
 * @Description  : 验证原生用户签名能力不能借普通中心主体伪造管理员会话；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailjob.iam;

import com.aizuda.snailjob.common.core.util.JsonUtil;
import com.aizuda.snailjob.server.web.interceptor.AuthenticationInterceptor;
import com.aizuda.snailjob.server.web.model.response.SystemUserResponseVO;
import com.aizuda.snailjob.server.web.service.SystemUserService;
import com.aizuda.snailjob.template.datasource.persistence.mapper.SystemUserMapper;
import com.aizuda.snailjob.template.datasource.persistence.po.SystemUser;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamSessionStatusResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Job 中心主体与本地原生主体绑定回归。
 */
@Tag("dev")
class IamJobSubjectBindingTest {

    @Test
    void nativeAdminSignatureWithOrdinaryCenterSubjectIsRejectedByFilter() throws Exception {
        IamJobProperties properties = new IamJobProperties();
        properties.setApplicationId("snail-job");
        properties.getAccounts().put("center-user", "mapped-local-user");

        SystemUser admin = user(7L, "native-admin", "native-signing-key");
        SystemUser mapped = user(9L, "mapped-local-user", "mapped-signing-key");
        SystemUserMapper mapper = mock(SystemUserMapper.class);
        when(mapper.selectById(7L)).thenReturn(admin);
        when(mapper.selectOne(any())).thenReturn(mapped);

        IamJobLocalSessionService localSessionService =
            new IamJobLocalSessionService(properties, mapper, mock(SystemUserService.class));
        String forged = token(admin);

        IamJobCenterClient centerClient = mock(IamJobCenterClient.class);
        when(centerClient.status(
            "root-ordinary-center-user",
            "job-main",
            "job-session-ordinary-center-user",
            1L,
            "WEB"
        )).thenReturn(new IamSessionStatusResponse(
            true,
            IamProtocolConstants.ISSUER,
            42L,
            "center-user",
            Instant.now().plusSeconds(60).toEpochMilli(),
            null
        ));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/job-protected");
        request.addHeader(AuthenticationInterceptor.AUTHENTICATION, forged);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainReached = new AtomicBoolean(false);

        new IamJobSessionFilter(centerClient, localSessionService)
            .doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                chainReached.set(true));

        assertFalse(chainReached.get());
        assertTrue(response.getStatus() == 401);
        verify(mapper).selectById(7L);
        verify(centerClient).status(
            "root-ordinary-center-user",
            "job-main",
            "job-session-ordinary-center-user",
            1L,
            "WEB"
        );
    }

    @Test
    void validMappingPassesSubjectBindingAndReachesOriginalAclChain() throws Exception {
        IamJobProperties properties = new IamJobProperties();
        properties.getAccounts().put("center-user", "mapped-local-user");
        SystemUser mapped = user(9L, "mapped-local-user", "mapped-signing-key");
        SystemUserMapper mapper = mock(SystemUserMapper.class);
        when(mapper.selectById(9L)).thenReturn(mapped);
        when(mapper.selectOne(any())).thenReturn(mapped);
        IamJobLocalSessionService localSessionService =
            new IamJobLocalSessionService(properties, mapper, mock(SystemUserService.class));
        IamJobCenterClient centerClient = activeCenterClient();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/job-protected");
        request.addHeader(AuthenticationInterceptor.AUTHENTICATION, token(mapped));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainReached = new AtomicBoolean(false);

        new IamJobSessionFilter(centerClient, localSessionService)
            .doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                chainReached.set(true));

        assertTrue(chainReached.get());
        assertTrue(response.getStatus() == 200);
        verify(mapper).selectById(9L);
        verify(centerClient).status(
            "root-ordinary-center-user",
            "job-main",
            "job-session-ordinary-center-user",
            1L,
            "WEB"
        );
    }

    @Test
    void reboundOrRemovedMappingInvalidatesOldTokenThroughFilter() throws Exception {
        IamJobProperties properties = new IamJobProperties();
        properties.getAccounts().put("center-user", "mapped-local-user");
        SystemUser oldMapped = user(9L, "mapped-local-user", "mapped-signing-key");
        SystemUserMapper mapper = mock(SystemUserMapper.class);
        when(mapper.selectById(9L)).thenReturn(oldMapped);
        SystemUser newMapped = user(10L, "new-local-user", "new-signing-key");
        when(mapper.selectOne(any())).thenReturn(newMapped);
        IamJobLocalSessionService localSessionService =
            new IamJobLocalSessionService(properties, mapper, mock(SystemUserService.class));
        IamJobCenterClient centerClient = activeCenterClient();
        String oldToken = token(oldMapped);

        properties.getAccounts().put("center-user", "new-local-user");
        MockHttpServletRequest reboundRequest = new MockHttpServletRequest("GET", "/job-protected");
        reboundRequest.addHeader(AuthenticationInterceptor.AUTHENTICATION, oldToken);
        MockHttpServletResponse reboundResponse = new MockHttpServletResponse();
        AtomicBoolean reboundChainReached = new AtomicBoolean(false);
        new IamJobSessionFilter(centerClient, localSessionService)
            .doFilter(reboundRequest, reboundResponse, (ignoredRequest, ignoredResponse) ->
                reboundChainReached.set(true));
        assertFalse(reboundChainReached.get());
        assertEquals(401, reboundResponse.getStatus());
        verify(mapper).selectById(9L);
        verify(centerClient).status(
            "root-ordinary-center-user",
            "job-main",
            "job-session-ordinary-center-user",
            1L,
            "WEB"
        );

        clearInvocations(centerClient);
        properties.getAccounts().clear();
        MockHttpServletRequest removedRequest = new MockHttpServletRequest("GET", "/job-protected");
        removedRequest.addHeader(AuthenticationInterceptor.AUTHENTICATION, oldToken);
        MockHttpServletResponse removedResponse = new MockHttpServletResponse();
        AtomicBoolean removedChainReached = new AtomicBoolean(false);
        new IamJobSessionFilter(centerClient, localSessionService)
            .doFilter(removedRequest, removedResponse, (ignoredRequest, ignoredResponse) ->
                removedChainReached.set(true));
        assertFalse(removedChainReached.get());
        assertEquals(401, removedResponse.getStatus());
        verify(mapper, times(2)).selectById(9L);
        verify(centerClient).status(
            "root-ordinary-center-user",
            "job-main",
            "job-session-ordinary-center-user",
            1L,
            "WEB"
        );
    }

    private SystemUser user(Long id, String username, String signingKey) {
        SystemUser user = new SystemUser();
        user.setId(id);
        user.setUsername(username);
        user.setPassword(signingKey);
        return user;
    }

    private String token(SystemUser user) {
        SystemUserResponseVO audience = new SystemUserResponseVO();
        audience.setId(user.getId());
        audience.setUsername(user.getUsername());
        return JWT.create()
            .withExpiresAt(Date.from(Instant.now().plusSeconds(300)))
            .withAudience(JsonUtil.toJsonString(audience))
            .withClaim(IamProtocolConstants.SUBJECT_USER_ID_CLAIM, 42L)
            .withClaim(IamProtocolConstants.ROOT_SID_SESSION_KEY, "root-ordinary-center-user")
            .withClaim(IamProtocolConstants.FRONTEND_ID_SESSION_KEY, "job-main")
            .withClaim(
                IamProtocolConstants.APPLICATION_SESSION_ID_KEY,
                "job-session-ordinary-center-user"
            )
            .withClaim(IamProtocolConstants.APPLICATION_SESSION_GENERATION_KEY, 1L)
            .withClaim(IamProtocolConstants.TERMINAL_SESSION_KEY, "WEB")
            .sign(Algorithm.HMAC256(user.getPassword()));
    }

    private IamJobCenterClient activeCenterClient() {
        IamJobCenterClient centerClient = mock(IamJobCenterClient.class);
        when(centerClient.status(
            "root-ordinary-center-user",
            "job-main",
            "job-session-ordinary-center-user",
            1L,
            "WEB"
        )).thenReturn(new IamSessionStatusResponse(
            true,
            IamProtocolConstants.ISSUER,
            42L,
            "center-user",
            Instant.now().plusSeconds(60).toEpochMilli(),
            null
        ));
        return centerClient;
    }
}
