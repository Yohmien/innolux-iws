package com.innolux.iws.auth.controller;

import com.innolux.iws.auth.config.IamProperties;
import com.innolux.iws.auth.service.IamServiceRequestVerifier;
import com.innolux.iws.auth.service.IamSessionRegistry;
import com.innolux.iws.auth.service.IamTicketService;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.core.service.PermissionService;
import com.innolux.iws.system.service.ISysUserService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * 认证中心登录前前端配置接口测试。
 */
@Tag("dev")
class IamSsoControllerTest {

    @Test
    void returnsRegisteredFrontendAddressesForBrowserPreflight() {
        IamProperties properties = new IamProperties();
        properties.setPublicBaseUrl("http://10.189.88.118:18081");
        properties.getFrontends().put("web-main", frontend());
        IamSsoController controller = new IamSsoController(
            properties,
            mock(IamSessionRegistry.class),
            mock(IamTicketService.class),
            mock(IamServiceRequestVerifier.class),
            mock(PermissionService.class),
            mock(ISysUserService.class)
        );

        R<IamSsoController.LoginFrontendOption> response =
            controller.loginFrontend("web-main", "WEB");

        assertEquals(200, response.getCode());
        assertEquals("web-main", response.getData().frontendId());
        assertEquals("BROWSER", response.getData().clientMode());
        assertEquals("http://10.189.88.204:18000", response.getData().publicBaseUrl());
        assertEquals(
            "http://10.189.88.204:18000/sso/callback",
            response.getData().redirectUri()
        );
    }

    private IamProperties.Frontend frontend() {
        IamProperties.Frontend frontend = new IamProperties.Frontend();
        frontend.setTerminal("WEB");
        frontend.setClientMode("BROWSER");
        frontend.setPublicBaseUrl("http://10.189.88.204:18000");
        frontend.setLaunchUri("http://10.189.88.204:18000/");
        frontend.setRedirectUri("http://10.189.88.204:18000/sso/callback");
        frontend.setLogoutUri("http://10.189.88.204:18000/login");
        return frontend;
    }
}
