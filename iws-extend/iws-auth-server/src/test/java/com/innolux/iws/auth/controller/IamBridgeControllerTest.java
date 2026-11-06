package com.innolux.iws.auth.controller;

import cn.hutool.extra.spring.SpringUtil;
import com.innolux.iws.auth.config.IamProperties;
import com.innolux.iws.auth.service.IamApplicationPreparation;
import com.innolux.iws.auth.service.IamServiceRequestVerifier;
import com.innolux.iws.auth.service.IamSessionRegistry;
import com.innolux.iws.auth.service.IamTicketService;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamAuthorizationResponse;
import com.innolux.iws.common.auth.domain.IamBridgeIssueRequest;
import com.innolux.iws.common.auth.domain.IamBridgeTicketResponse;
import com.innolux.iws.common.auth.domain.IamSessionStatusResponse;
import com.innolux.iws.common.core.constant.SystemConstants;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.core.service.PermissionService;
import com.innolux.iws.common.json.utils.JsonUtils;
import com.innolux.iws.system.api.model.LoginUser;
import com.innolux.iws.system.domain.vo.SysUserVo;
import com.innolux.iws.system.service.ISysUserService;
import com.innolux.iws.web.service.SysLoginService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 嵌入应用桥接票据控制器测试。
 */
@Tag("dev")
class IamBridgeControllerTest {

    @BeforeAll
    static void initializeJsonMapper() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("jsonMapper", JsonMapper.builder().build());
        new SpringUtil().postProcessBeanFactory(beanFactory);
    }

    @Test
    void issuesTicketForBoundTargetWebApplication() {
        IamProperties properties = properties();
        IamSessionRegistry sessions = mock(IamSessionRegistry.class);
        IamTicketService tickets = mock(IamTicketService.class);
        ISysUserService users = mock(ISysUserService.class);
        PermissionService permissions = mock(PermissionService.class);
        SysLoginService loginService = mock(SysLoginService.class);
        IamServiceRequestVerifier verifier = mock(IamServiceRequestVerifier.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        SysUserVo user = new SysUserVo();
        user.setUserId(100L);
        user.setUserName("admin");
        user.setStatus(SystemConstants.NORMAL);
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(100L);
        IamApplicationPreparation preparation = new IamApplicationPreparation(
            IamProtocolConstants.ISSUER,
            "snail-ai",
            "SNAIL_AI",
            "ai-main",
            100L,
            "admin",
            "root-a",
            "session-a",
            1,
            IamProtocolConstants.TERMINAL_WEB,
            "transaction-a",
            1,
            2
        );
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/iam/bridge/issue");
        when(sessions.status("root-a", "iws-a", "web-main", "session-source", 2, "WEB"))
            .thenReturn(new IamSessionStatusResponse(
                true,
                IamProtocolConstants.ISSUER,
                100L,
                "admin",
                999,
                null
            ));
        when(users.selectUserById(100L)).thenReturn(user);
        when(permissions.getMenuPermission(100L)).thenReturn(Set.of("monitor:snailai:list"));
        when(loginService.buildLoginUser(user)).thenReturn(loginUser);
        when(tickets.prepareAuthorization(anyString(), anyString()))
            .thenReturn(new IamTicketService.PreparedAuthorization("state-a", "/"));
        when(sessions.prepareApplication(
            any(), anyString(), any(), anyString(), anyString(), anyString(), anyString()
        )).thenReturn(preparation);
        when(tickets.issue(
            any(), anyString(), anyString(), any(), anyString(), any()
        )).thenReturn(new IamAuthorizationResponse("ticket-a", "/callback", 999));

        IamBridgeController controller = new IamBridgeController(
            properties,
            verifier,
            sessions,
            tickets,
            users,
            permissions,
            loginService
        );
        String body = JsonUtils.toJsonString(new IamBridgeIssueRequest(
            "snail-ai",
            "web-main",
            "session-source",
            2,
            "WEB",
            "root-a",
            "/"
        ));

        R<IamBridgeTicketResponse> response = controller.issue(
            "iws-a",
            "iws-a-1",
            "timestamp",
            "nonce",
            "signature",
            body,
            request
        );

        assertEquals(200, response.getCode());
        assertEquals("ticket-a", response.getData().ticket());
        assertEquals("state-a", response.getData().state());
        assertEquals("snail-ai", response.getData().applicationId());
        assertEquals("ai-main", response.getData().frontendId());
    }

    @Test
    void rejectsPdaSourceBeforeReadingSession() {
        IamProperties properties = properties();
        IamSessionRegistry sessions = mock(IamSessionRegistry.class);
        IamServiceRequestVerifier verifier = mock(IamServiceRequestVerifier.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        IamBridgeController controller = new IamBridgeController(
            properties,
            verifier,
            sessions,
            mock(IamTicketService.class),
            mock(ISysUserService.class),
            mock(PermissionService.class),
            mock(SysLoginService.class)
        );
        String body = JsonUtils.toJsonString(new IamBridgeIssueRequest(
            "snail-ai",
            "pda-main",
            "session-source",
            2,
            "PDA",
            "root-a",
            "/"
        ));

        Exception error = assertThrows(Exception.class, () -> controller.issue(
            "iws-a",
            "iws-a-1",
            "timestamp",
            "nonce",
            "signature",
            body,
            request
        ));

        assertEquals("仅 Web 应用会话可申请嵌入桥接", error.getMessage());
        verifyNoInteractions(sessions);
    }

    private IamProperties properties() {
        IamProperties properties = new IamProperties();
        IamProperties.Application source = application("IWS", Set.of("web-main"), "iam:app:iws:a");
        IamProperties.Application target = application(
            "SNAIL_AI",
            Set.of("ai-main"),
            "monitor:snailai:list"
        );
        properties.getApplications().put("iws-a", source);
        properties.getApplications().put("snail-ai", target);
        properties.getFrontends().put("web-main", frontend("WEB"));
        properties.getFrontends().put("ai-main", frontend("WEB"));
        return properties;
    }

    private IamProperties.Application application(
        String type,
        Set<String> frontendIds,
        String permission
    ) {
        IamProperties.Application application = new IamProperties.Application();
        application.setEnabled(true);
        application.setType(type);
        application.setName(type);
        application.setTerminals(Set.of("WEB"));
        application.setAllowedFrontendIds(frontendIds);
        application.setAccessPermission(permission);
        application.setClientSecret("secret");
        application.setAllowedInstanceIds(Set.of("iws-a-1"));
        application.setSessionNamespace(type);
        return application;
    }

    private IamProperties.Frontend frontend(String terminal) {
        IamProperties.Frontend frontend = new IamProperties.Frontend();
        frontend.setEnabled(true);
        frontend.setTerminal(terminal);
        frontend.setClientMode("BROWSER");
        frontend.setPublicBaseUrl("http://127.0.0.1:18000");
        frontend.setLaunchUri("http://127.0.0.1:18000/");
        frontend.setRedirectUri("http://127.0.0.1:18000/sso/callback");
        frontend.setLogoutUri("http://127.0.0.1:18000/login");
        return frontend;
    }
}
