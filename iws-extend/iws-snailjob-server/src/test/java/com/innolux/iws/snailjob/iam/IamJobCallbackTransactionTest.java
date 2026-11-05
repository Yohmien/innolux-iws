/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Job 统一身份接入
 * @Version      : V1.0
 * @Description  : 验证迟到或失败回调不会消费、覆盖或销毁更新的浏览器事务；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailjob.iam;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Job 浏览器 SSO 事务竞态回归。
 */
@Tag("dev")
class IamJobCallbackTransactionTest {

    @Test
    void guardScriptKeepsPercentOperatorsAndInstallsUnifiedLogoutHook() {
        IamJobProperties properties = new IamJobProperties();
        properties.setPublicBaseUrl("http://127.0.0.1:19081/iam");
        IamJobSsoController controller = new IamJobSsoController(
            properties,
            mock(IamJobCenterClient.class),
            mock(IamJobLocalSessionService.class)
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContextPath("/snail-job");

        String script = controller.guard(request).getBody();

        assertTrue(script.contains("Storage.prototype.removeItem"));
        assertTrue(script.contains("XMLHttpRequest.prototype.send"));
        assertTrue(script.contains("window.fetch"));
        assertTrue(script.contains("% 4"));
        assertTrue(script.contains("X-IAM-Local-Credential-Valid"));
        assertTrue(script.contains("const requestExplicitLogout"));
        assertTrue(script.contains("const reauthenticate"));
        assertTrue(script.contains("explicitLogoutRequested = true"));
        assertTrue(script.contains("确认退出登录吗？"));
        assertTrue(script.contains("if (!sameCurrentToken(token)) return"));
        assertTrue(script.contains("const context = '/snail-job'"));
        assertTrue(script.contains("context + '/iam/sso/logout'"));
        assertTrue(script.contains("http://127.0.0.1:19081/iam/login"));
        assertFalse(script.contains("@@IWS_"));
    }

    @Test
    void staleOrFailedCallbackDoesNotConsumeOrInvalidateNewTransaction() throws Exception {
        IamJobProperties properties = new IamJobProperties();
        properties.setApplicationId("snail-job");
        properties.setFrontendId("job-main");
        properties.setPublicBaseUrl("http://127.0.0.1:19081/iam");
        IamJobCenterClient centerClient = mock(IamJobCenterClient.class);
        IamJobSsoController controller = new IamJobSsoController(
            properties,
            centerClient,
            mock(IamJobLocalSessionService.class)
        );

        MockHttpServletRequest request = new MockHttpServletRequest();
        controller.start("/", request, new MockHttpServletResponse());
        String staleState = request.getSession(false)
            .getAttribute("iamJobState").toString();

        controller.start("/new", request, new MockHttpServletResponse());
        MockHttpSession session = (MockHttpSession) request.getSession(false);
        String currentState = session.getAttribute("iamJobState").toString();

        MockHttpServletResponse staleResponse = new MockHttpServletResponse();
        controller.callback(
            "stale-ticket",
            staleState,
            "snail-job",
            "job-main",
            "WEB",
            request,
            staleResponse
        );
        assertEquals(401, staleResponse.getStatus());
        assertEquals(currentState, session.getAttribute("iamJobState"));
        assertFalse(session.isInvalid());

        when(centerClient.inspect(anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("中心不可达"));
        MockHttpServletResponse failedResponse = new MockHttpServletResponse();
        controller.callback(
            "current-ticket",
            currentState,
            "snail-job",
            "job-main",
            "WEB",
            request,
            failedResponse
        );
        assertEquals(401, failedResponse.getStatus());
        assertNull(session.getAttribute("iamJobState"));
        assertFalse(session.isInvalid());
    }
}
