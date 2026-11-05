/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : AI 统一身份接入
 * @Version      : V1.0
 * @Description  : 验证 matrix 参数和扩展名不能把动态接口伪装成静态资源；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailai.iam;

import com.aizuda.snail.ai.admin.interceptor.AuthenticationInterceptor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI IAM 路径守卫回归。
 */
@Tag("dev")
class IamAiPathGuardTest {

    @Test
    void matrixVariantOfProtectedApiIsNotTreatedAsStaticResource() throws Exception {
        MockHttpServletRequest request = request("GET", "/snail-ai/user;review=.ico");
        request.addHeader(AuthenticationInterceptor.AUTHENTICATION, "invalid-native-token");
        IamAiLocalSessionService localSessionService = mock(IamAiLocalSessionService.class);
        when(localSessionService.verify(anyString()))
            .thenThrow(new IllegalStateException("invalid"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainReached = new AtomicBoolean(false);

        new IamAiSessionFilter(mock(IamAiCenterClient.class), localSessionService)
            .doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                chainReached.set(true));

        assertEquals("/user", IamAiSessionFilter.applicationPath(request));
        assertFalse(chainReached.get());
        assertEquals(401, response.getStatus());
    }

    @Test
    void matrixVariantOfNativeLoginIsBlockedByUnifiedEntryGuard() throws Exception {
        MockHttpServletRequest request = request("POST", "/snail-ai/user/login;review=x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainReached = new AtomicBoolean(false);

        new IamAiSessionFilter(
            mock(IamAiCenterClient.class),
            mock(IamAiLocalSessionService.class)
        ).doFilter(request, response, (ignoredRequest, ignoredResponse) ->
            chainReached.set(true));

        assertEquals("/user/login", IamAiSessionFilter.applicationPath(request));
        assertFalse(chainReached.get());
        assertEquals(403, response.getStatus());
    }

    @Test
    void realAssetPathRemainsPublic() throws Exception {
        MockHttpServletRequest request = request("GET", "/snail-ai/assets/app.js;v=1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainReached = new AtomicBoolean(false);

        new IamAiSessionFilter(
            mock(IamAiCenterClient.class),
            mock(IamAiLocalSessionService.class)
        ).doFilter(request, response, (ignoredRequest, ignoredResponse) ->
            chainReached.set(true));

        assertTrue(chainReached.get());
        assertEquals(200, response.getStatus());
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setContextPath("/snail-ai");
        return request;
    }
}
