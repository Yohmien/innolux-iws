/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : AI 统一身份接入
 * @Version      : V1.0
 * @Description  : 验证无效本地 Token 只清理本地，不猜测中心 rootSid；
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class IamAiSsoLogoutTest {

    @Test
    void expiredOrDeletedLocalCredentialDoesNotProbeCenterRoot() {
        IamAiProperties properties = properties();
        UserMapper mapper = mock(UserMapper.class);
        UserPO user = new UserPO();
        user.setUsername("local-user");
        user.setPassword("local-key");
        when(mapper.selectOne(any())).thenReturn(user);
        IamAiLocalSessionService local = new IamAiLocalSessionService(properties, mapper);
        IamAiCenterClient center = mock(IamAiCenterClient.class);
        IamAiSsoController controller = new IamAiSsoController(properties, center, local);

        String expired = token(user, "local-key", Instant.now().minusSeconds(60));
        String wrongKey = token(user, "old-key", Instant.now().plusSeconds(60));

        ResponseEntity<Void> expiredResponse = controller.logout(expired);
        assertEquals(204, expiredResponse.getStatusCode().value());
        assertEquals("false",
            expiredResponse.getHeaders().getFirst(IamProtocolConstants.LOGOUT_LOCAL_VALID_HEADER));
        assertEquals("false",
            expiredResponse.getHeaders().getFirst(IamProtocolConstants.LOGOUT_CENTER_REVOKED_HEADER));
        ResponseEntity<Void> wrongKeyResponse = controller.logout(wrongKey);
        assertEquals(204, wrongKeyResponse.getStatusCode().value());
        assertEquals("false",
            wrongKeyResponse.getHeaders().getFirst(IamProtocolConstants.LOGOUT_LOCAL_VALID_HEADER));
        verifyNoInteractions(center);

        when(mapper.selectOne(any())).thenReturn(null);
        assertEquals(ResponseEntity.noContent().build().getStatusCode(),
            controller.logout(token(user, "local-key", Instant.now().plusSeconds(60))).getStatusCode());
        verifyNoInteractions(center);
    }

    @Test
    void validLocalCredentialReportsCenterRevocationResult() {
        IamAiProperties properties = properties();
        UserMapper mapper = mock(UserMapper.class);
        UserPO user = new UserPO();
        user.setId(42L);
        user.setUsername("local-user");
        user.setPassword("local-key");
        when(mapper.selectOne(any())).thenReturn(user);
        IamAiLocalSessionService local = new IamAiLocalSessionService(properties, mapper);
        IamAiCenterClient center = mock(IamAiCenterClient.class);
        when(center.revokeRoot("root-1", "ai-main", "session-1", 1L, "WEB"))
            .thenReturn(true);

        ResponseEntity<Void> response = new IamAiSsoController(properties, center, local)
            .logout(token(user, "local-key", Instant.now().plusSeconds(60)));

        assertEquals(204, response.getStatusCode().value());
        assertEquals("true",
            response.getHeaders().getFirst(IamProtocolConstants.LOGOUT_LOCAL_VALID_HEADER));
        assertEquals("true",
            response.getHeaders().getFirst(IamProtocolConstants.LOGOUT_CENTER_REVOKED_HEADER));
        verify(center).revokeRoot("root-1", "ai-main", "session-1", 1L, "WEB");
    }

    private IamAiProperties properties() {
        IamAiProperties properties = new IamAiProperties();
        properties.setApplicationId("snail-ai");
        properties.setPublicBaseUrl("http://127.0.0.1");
        properties.setCenterBaseUrl("http://127.0.0.1");
        properties.setApplicationType("SNAIL_AI");
        properties.setFrontendId("ai-main");
        properties.setInstanceId("snail-ai-test");
        properties.setClientSecret("isolated-secret");
        return properties;
    }

    private String token(UserPO user, String key, Instant expiresAt) {
        AudienceDTO audience = new AudienceDTO();
        audience.setUsername(user.getUsername());
        return JWT.create()
            .withExpiresAt(Date.from(expiresAt))
            .withAudience(JsonUtil.toJsonString(audience))
            .withClaim(IamProtocolConstants.SUBJECT_USER_ID_CLAIM, 42L)
            .withClaim(IamProtocolConstants.ROOT_SID_SESSION_KEY, "root-1")
            .withClaim(IamProtocolConstants.FRONTEND_ID_SESSION_KEY, "ai-main")
            .withClaim(IamProtocolConstants.APPLICATION_SESSION_ID_KEY, "session-1")
            .withClaim(IamProtocolConstants.APPLICATION_SESSION_GENERATION_KEY, 1L)
            .withClaim(IamProtocolConstants.TERMINAL_SESSION_KEY, "WEB")
            .sign(Algorithm.HMAC256(key));
    }
}
