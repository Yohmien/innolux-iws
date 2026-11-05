/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Admin 监控统一身份接入
 * @Version      : V1.0
 * @Description  : 验证迟到或失败监控回调不会销毁既有人员登录或新事务；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.monitor.admin.web;

import com.innolux.iws.monitor.admin.config.IamMonitorProperties;
import com.innolux.iws.monitor.admin.service.IamMonitorCenterClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Admin 监控浏览器 SSO 事务竞态回归。
 */
@Tag("dev")
class IamMonitorSsoCallbackTransactionTest {

    @Test
    void staleOrFailedCallbackPreservesExistingLoginAndNewTransaction() throws Exception {
        IamMonitorProperties properties = new IamMonitorProperties();
        properties.setApplicationId("admin-monitor");
        properties.setFrontendId("monitor-main");
        properties.setPublicBaseUrl("http://127.0.0.1:19081/iam");
        IamMonitorCenterClient centerClient = mock(IamMonitorCenterClient.class);
        IamMonitorSsoController controller = new IamMonitorSsoController(
            properties,
            centerClient
        );

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = (MockHttpSession) request.getSession(true);
        SecurityContext existingContext = SecurityContextHolder.createEmptyContext();
        existingContext.setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated("existing", null, null)
        );
        session.setAttribute(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
            existingContext
        );

        controller.start("/admin/", request, new MockHttpServletResponse());
        String staleState = session.getAttribute("iamMonitorState").toString();
        controller.start("/admin/new", request, new MockHttpServletResponse());
        String currentState = session.getAttribute("iamMonitorState").toString();

        assertEquals(401, controller.callback(
            "stale-ticket",
            staleState,
            "admin-monitor",
            "monitor-main",
            "WEB",
            request
        ).getStatusCode().value());
        assertEquals(currentState, session.getAttribute("iamMonitorState"));
        assertSame(
            existingContext,
            session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
        );
        assertFalse(session.isInvalid());

        when(centerClient.inspect(anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("中心不可达"));
        assertEquals(401, controller.callback(
            "current-ticket",
            currentState,
            "admin-monitor",
            "monitor-main",
            "WEB",
            request
        ).getStatusCode().value());
        assertNull(session.getAttribute("iamMonitorState"));
        assertSame(
            existingContext,
            session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
        );
        assertFalse(session.isInvalid());
    }
}
