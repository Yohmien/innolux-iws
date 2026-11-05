/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Job 统一身份接入
 * @Version      : V1.0
 * @Description  : 验证 Job 无效本地 Token 只清理本地，不猜测中心 rootSid；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailjob.iam;

import com.aizuda.snailjob.common.core.util.JsonUtil;
import com.aizuda.snailjob.server.web.model.response.SystemUserResponseVO;
import com.aizuda.snailjob.server.web.service.SystemUserService;
import com.aizuda.snailjob.template.datasource.persistence.mapper.SystemUserMapper;
import com.aizuda.snailjob.template.datasource.persistence.po.SystemUser;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.snailjob.iam.IamJobLocalSessionService.IamTokenClaims;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class IamJobSsoLogoutTest {

    @Test
    void invalidLocalCredentialDoesNotProbeCenterRoot() {
        IamJobProperties properties = properties();
        SystemUserMapper mapper = mock(SystemUserMapper.class);
        SystemUser user = new SystemUser();
        user.setId(42L);
        user.setUsername("local-job-user");
        user.setPassword("local-key");
        when(mapper.selectById(42L)).thenReturn(user);
        IamJobLocalSessionService local = new IamJobLocalSessionService(
            properties,
            mapper,
            mock(SystemUserService.class)
        );
        IamJobCenterClient center = mock(IamJobCenterClient.class);
        IamJobSsoController controller = new IamJobSsoController(properties, center, local);

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

        when(mapper.selectById(42L)).thenReturn(null);
        assertEquals(ResponseEntity.noContent().build().getStatusCode(),
            controller.logout(token(user, "local-key", Instant.now().plusSeconds(60))).getStatusCode());
        verifyNoInteractions(center);
    }

    @Test
    void validLocalCredentialReportsCenterRevocationResult() {
        IamJobProperties properties = properties();
        SystemUserMapper mapper = mock(SystemUserMapper.class);
        SystemUser user = new SystemUser();
        user.setId(42L);
        user.setUsername("local-job-user");
        user.setPassword("local-key");
        when(mapper.selectById(42L)).thenReturn(user);
        IamJobLocalSessionService local = new IamJobLocalSessionService(
            properties,
            mapper,
            mock(SystemUserService.class)
        );
        IamJobCenterClient center = mock(IamJobCenterClient.class);
        when(center.revokeRoot("root-1", "job-main", "session-1", 1L, "WEB"))
            .thenReturn(true);

        ResponseEntity<Void> response = new IamJobSsoController(properties, center, local)
            .logout(token(user, "local-key", Instant.now().plusSeconds(60)));

        assertEquals(204, response.getStatusCode().value());
        assertEquals("true",
            response.getHeaders().getFirst(IamProtocolConstants.LOGOUT_LOCAL_VALID_HEADER));
        assertEquals("true",
            response.getHeaders().getFirst(IamProtocolConstants.LOGOUT_CENTER_REVOKED_HEADER));
        verify(center).revokeRoot("root-1", "job-main", "session-1", 1L, "WEB");
    }

    private IamJobProperties properties() {
        IamJobProperties properties = new IamJobProperties();
        properties.setApplicationId("snail-job");
        properties.setPublicBaseUrl("http://127.0.0.1");
        properties.setCenterBaseUrl("http://127.0.0.1");
        properties.setApplicationType("SNAIL_JOB");
        properties.setFrontendId("job-main");
        properties.setInstanceId("snail-job-test");
        properties.setClientSecret("isolated-secret");
        return properties;
    }

    private String token(SystemUser user, String key, Instant expiresAt) {
        return JWT.create()
            .withExpiresAt(Date.from(expiresAt))
            .withAudience(JsonUtil.toJsonString(response(user)))
            .withClaim(IamProtocolConstants.SUBJECT_USER_ID_CLAIM, 42L)
            .withClaim(IamProtocolConstants.ROOT_SID_SESSION_KEY, "root-1")
            .withClaim(IamProtocolConstants.FRONTEND_ID_SESSION_KEY, "job-main")
            .withClaim(IamProtocolConstants.APPLICATION_SESSION_ID_KEY, "session-1")
            .withClaim(IamProtocolConstants.APPLICATION_SESSION_GENERATION_KEY, 1L)
            .withClaim(IamProtocolConstants.TERMINAL_SESSION_KEY, "WEB")
            .sign(Algorithm.HMAC256(key));
    }

    private SystemUserResponseVO response(SystemUser user) {
        SystemUserResponseVO response = new SystemUserResponseVO();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        return response;
    }
}
