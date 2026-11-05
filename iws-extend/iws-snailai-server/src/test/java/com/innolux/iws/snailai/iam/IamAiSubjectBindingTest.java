/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : AI 统一身份接入
 * @Version      : V1.0
 * @Description  : 验证原生用户签名能力不能借普通中心主体伪造管理员会话；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailai.iam;

import com.aizuda.snail.ai.admin.dto.AudienceDTO;
import com.aizuda.snail.ai.admin.interceptor.AuthenticationInterceptor;
import com.aizuda.snail.ai.common.util.JsonUtil;
import com.aizuda.snail.ai.persistence.admin.mapper.UserMapper;
import com.aizuda.snail.ai.persistence.admin.po.UserPO;
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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 中心主体与本地原生主体绑定回归。
 */
@Tag("dev")
class IamAiSubjectBindingTest {

    @Test
    void nativeAdminSignatureWithOrdinaryCenterSubjectIsRejectedByFilter() throws Exception {
        IamAiProperties properties = new IamAiProperties();
        properties.setApplicationId("snail-ai");
        properties.getAccounts().put("center-user", "mapped-local-user");

        UserPO admin = user(7L, "native-admin", "native-signing-key");
        UserPO mapped = user(9L, "mapped-local-user", "mapped-signing-key");
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectOne(any())).thenReturn(admin, mapped);

        IamAiLocalSessionService localSessionService =
            new IamAiLocalSessionService(properties, mapper);
        String forged = token(admin);

        IamAiCenterClient centerClient = mock(IamAiCenterClient.class);
        when(centerClient.status(
            "root-ordinary-center-user",
            "ai-main",
            "ai-session-ordinary-center-user",
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

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai-protected");
        request.addHeader(AuthenticationInterceptor.AUTHENTICATION, forged);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainReached = new AtomicBoolean(false);

        new IamAiSessionFilter(centerClient, localSessionService)
            .doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                chainReached.set(true));

        assertFalse(chainReached.get());
        assertTrue(response.getStatus() == 401);
        verify(mapper, times(2)).selectOne(any());
        verify(centerClient).status(
            "root-ordinary-center-user",
            "ai-main",
            "ai-session-ordinary-center-user",
            1L,
            "WEB"
        );
    }

    @Test
    void validMappingPassesSubjectBindingAndReachesOriginalAclChain() throws Exception {
        IamAiProperties properties = new IamAiProperties();
        properties.getAccounts().put("center-user", "mapped-local-user");
        UserPO mapped = user(9L, "mapped-local-user", "mapped-signing-key");
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectOne(any())).thenReturn(mapped);
        IamAiLocalSessionService localSessionService =
            new IamAiLocalSessionService(properties, mapper);
        IamAiCenterClient centerClient = activeCenterClient();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai-protected");
        request.addHeader(AuthenticationInterceptor.AUTHENTICATION, token(mapped));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainReached = new AtomicBoolean(false);

        new IamAiSessionFilter(centerClient, localSessionService)
            .doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                chainReached.set(true));

        assertTrue(chainReached.get());
        assertTrue(response.getStatus() == 200);
        verify(mapper, times(2)).selectOne(any());
        verify(centerClient).status(
            "root-ordinary-center-user",
            "ai-main",
            "ai-session-ordinary-center-user",
            1L,
            "WEB"
        );
    }

    @Test
    void reboundOrRemovedMappingInvalidatesOldTokenThroughFilter() throws Exception {
        IamAiProperties properties = new IamAiProperties();
        properties.getAccounts().put("center-user", "mapped-local-user");
        UserPO oldMapped = user(9L, "mapped-local-user", "mapped-signing-key");
        UserPO newMapped = user(10L, "new-local-user", "new-signing-key");
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectOne(any())).thenReturn(oldMapped, newMapped);
        IamAiLocalSessionService localSessionService =
            new IamAiLocalSessionService(properties, mapper);
        IamAiCenterClient centerClient = activeCenterClient();
        String oldToken = token(oldMapped);

        properties.getAccounts().put("center-user", "new-local-user");
        MockHttpServletRequest reboundRequest = new MockHttpServletRequest("GET", "/ai-protected");
        reboundRequest.addHeader(AuthenticationInterceptor.AUTHENTICATION, oldToken);
        MockHttpServletResponse reboundResponse = new MockHttpServletResponse();
        AtomicBoolean reboundChainReached = new AtomicBoolean(false);
        new IamAiSessionFilter(centerClient, localSessionService)
            .doFilter(reboundRequest, reboundResponse, (ignoredRequest, ignoredResponse) ->
                reboundChainReached.set(true));
        assertFalse(reboundChainReached.get());
        assertEquals(401, reboundResponse.getStatus());
        verify(centerClient).status(
            "root-ordinary-center-user",
            "ai-main",
            "ai-session-ordinary-center-user",
            1L,
            "WEB"
        );

        clearInvocations(centerClient);
        when(mapper.selectOne(any())).thenReturn(oldMapped);
        properties.getAccounts().clear();
        MockHttpServletRequest removedRequest = new MockHttpServletRequest("GET", "/ai-protected");
        removedRequest.addHeader(AuthenticationInterceptor.AUTHENTICATION, oldToken);
        MockHttpServletResponse removedResponse = new MockHttpServletResponse();
        AtomicBoolean removedChainReached = new AtomicBoolean(false);
        new IamAiSessionFilter(centerClient, localSessionService)
            .doFilter(removedRequest, removedResponse, (ignoredRequest, ignoredResponse) ->
                removedChainReached.set(true));
        assertFalse(removedChainReached.get());
        assertEquals(401, removedResponse.getStatus());
        verify(centerClient).status(
            "root-ordinary-center-user",
            "ai-main",
            "ai-session-ordinary-center-user",
            1L,
            "WEB"
        );

    }

    private UserPO user(Long id, String username, String signingKey) {
        UserPO user = new UserPO();
        user.setId(id);
        user.setUsername(username);
        user.setPassword(signingKey);
        return user;
    }

    private String token(UserPO user) {
        AudienceDTO audience = new AudienceDTO();
        audience.setUsername(user.getUsername());
        return JWT.create()
            .withExpiresAt(Date.from(Instant.now().plusSeconds(300)))
            .withAudience(JsonUtil.toJsonString(audience))
            .withClaim(IamProtocolConstants.SUBJECT_USER_ID_CLAIM, 42L)
            .withClaim(IamProtocolConstants.ROOT_SID_SESSION_KEY, "root-ordinary-center-user")
            .withClaim(IamProtocolConstants.FRONTEND_ID_SESSION_KEY, "ai-main")
            .withClaim(
                IamProtocolConstants.APPLICATION_SESSION_ID_KEY,
                "ai-session-ordinary-center-user"
            )
            .withClaim(IamProtocolConstants.APPLICATION_SESSION_GENERATION_KEY, 1L)
            .withClaim(IamProtocolConstants.TERMINAL_SESSION_KEY, "WEB")
            .sign(Algorithm.HMAC256(user.getPassword()));
    }

    private IamAiCenterClient activeCenterClient() {
        IamAiCenterClient centerClient = mock(IamAiCenterClient.class);
        when(centerClient.status(
            "root-ordinary-center-user",
            "ai-main",
            "ai-session-ordinary-center-user",
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
