/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : AI 统一身份接入
 * @Version      : V1.0
 * @Description  : 验证迟到或失败回调不会消费、覆盖或销毁更新的浏览器事务；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailai.iam;

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
 * AI 浏览器 SSO 事务竞态回归。
 */
@Tag("dev")
class IamAiCallbackTransactionTest {

    @Test
    void guardScriptKeepsPercentOperatorsAndInstallsUnifiedLogoutHook() {
        IamAiProperties properties = new IamAiProperties();
        properties.setPublicBaseUrl("http://127.0.0.1:19081/iam");
        IamAiSsoController controller = new IamAiSsoController(
            properties,
            mock(IamAiCenterClient.class),
            mock(IamAiLocalSessionService.class)
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContextPath("/snail-ai");

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
        assertTrue(script.contains("const context = '/snail-ai'"));
        assertTrue(script.contains("context + '/iam/sso/logout'"));
        assertTrue(script.contains("http://127.0.0.1:19081/iam/login"));
        assertFalse(script.contains("@@IWS_"));
    }

    @Test
    void staleOrFailedCallbackDoesNotConsumeOrInvalidateNewTransaction() throws Exception {
        IamAiProperties properties = new IamAiProperties();
        properties.setApplicationId("snail-ai");
        properties.setFrontendId("ai-main");
        properties.setPublicBaseUrl("http://127.0.0.1:19081/iam");
        IamAiCenterClient centerClient = mock(IamAiCenterClient.class);
        IamAiSsoController controller = new IamAiSsoController(
            properties,
            centerClient,
            mock(IamAiLocalSessionService.class)
        );

        MockHttpServletRequest request = new MockHttpServletRequest();
        controller.start("/", request, new MockHttpServletResponse());
        String staleState = request.getSession(false)
            .getAttribute("iamAiState").toString();

        controller.start("/new", request, new MockHttpServletResponse());
        MockHttpSession session = (MockHttpSession) request.getSession(false);
        String currentState = session.getAttribute("iamAiState").toString();

        MockHttpServletResponse staleResponse = new MockHttpServletResponse();
        controller.callback(
            "stale-ticket",
            staleState,
            "snail-ai",
            "ai-main",
            "WEB",
            request,
            staleResponse
        );
        assertEquals(401, staleResponse.getStatus());
        assertEquals(currentState, session.getAttribute("iamAiState"));
        assertFalse(session.isInvalid());

        when(centerClient.inspect(anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("中心不可达"));
        MockHttpServletResponse failedResponse = new MockHttpServletResponse();
        controller.callback(
            "current-ticket",
            currentState,
            "snail-ai",
            "ai-main",
            "WEB",
            request,
            failedResponse
        );
        assertEquals(401, failedResponse.getStatus());
        assertNull(session.getAttribute("iamAiState"));
        assertFalse(session.isInvalid());
    }
}
