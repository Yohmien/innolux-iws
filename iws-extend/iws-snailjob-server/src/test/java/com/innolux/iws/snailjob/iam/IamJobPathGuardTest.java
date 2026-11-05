/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Job 统一身份接入
 * @Version      : V1.0
 * @Description  : 验证 matrix 参数和扩展名不能把动态接口伪装成静态资源；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailjob.iam;

import com.aizuda.snailjob.server.web.interceptor.AuthenticationInterceptor;
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
 * Job IAM 路径守卫回归。
 */
@Tag("dev")
class IamJobPathGuardTest {

    @Test
    void matrixVariantOfDynamicApiIsNotTreatedAsStaticResource() throws Exception {
        MockHttpServletRequest request = request("GET", "/snail-job/user/info;review=.js");
        request.addHeader(AuthenticationInterceptor.AUTHENTICATION, "invalid-native-token");
        IamJobLocalSessionService localSessionService = mock(IamJobLocalSessionService.class);
        when(localSessionService.verify(anyString()))
            .thenThrow(new IllegalStateException("invalid"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainReached = new AtomicBoolean(false);

        new IamJobSessionFilter(mock(IamJobCenterClient.class), localSessionService)
            .doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                chainReached.set(true));

        assertEquals("/user/info", IamJobSessionFilter.applicationPath(request));
        assertFalse(chainReached.get());
        assertEquals(401, response.getStatus());
    }

    @Test
    void matrixVariantOfNativeLoginIsBlockedByUnifiedEntryGuard() throws Exception {
        MockHttpServletRequest request = request("POST", "/snail-job/auth/login;review=x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainReached = new AtomicBoolean(false);

        new IamJobSessionFilter(
            mock(IamJobCenterClient.class),
            mock(IamJobLocalSessionService.class)
        ).doFilter(request, response, (ignoredRequest, ignoredResponse) ->
            chainReached.set(true));

        assertEquals("/auth/login", IamJobSessionFilter.applicationPath(request));
        assertFalse(chainReached.get());
        assertEquals(403, response.getStatus());
    }

    @Test
    void realAssetPathRemainsPublic() throws Exception {
        MockHttpServletRequest request = request("GET", "/snail-job/assets/app.js;v=1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainReached = new AtomicBoolean(false);

        new IamJobSessionFilter(
            mock(IamJobCenterClient.class),
            mock(IamJobLocalSessionService.class)
        ).doFilter(request, response, (ignoredRequest, ignoredResponse) ->
            chainReached.set(true));

        assertTrue(chainReached.get());
        assertEquals(200, response.getStatus());
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setContextPath("/snail-job");
        return request;
    }
}
