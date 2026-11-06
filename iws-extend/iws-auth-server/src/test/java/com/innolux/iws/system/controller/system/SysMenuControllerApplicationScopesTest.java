/*
 * @Date         : 2026-11-06
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 验证角色菜单应用范围选项过滤；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.controller.system;

import com.innolux.iws.auth.config.IamProperties;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.system.service.ISysMenuService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

@Tag("dev")
class SysMenuControllerApplicationScopesTest {

    @Test
    void returnsOnlyEnabledIwsApplications() {
        IamProperties properties = new IamProperties();
        LinkedHashMap<String, IamProperties.Application> applications = new LinkedHashMap<>();
        applications.put("iws-a", application("IWS", "IWS+ A", true));
        applications.put("iws-b", application("IWS", "IWS+ B", false));
        applications.put("snail-ai", application("SNAIL_AI", "AI 控制台", true));
        properties.setApplications(applications);

        SysMenuController controller = new SysMenuController(
            mock(ISysMenuService.class),
            properties
        );
        R<List<SysMenuController.MenuApplicationScopeOption>> response =
            controller.applicationScopes();

        assertEquals(
            List.of(new SysMenuController.MenuApplicationScopeOption("iws-a", "IWS+ A")),
            response.getData()
        );
    }

    private IamProperties.Application application(String type, String name, boolean enabled) {
        IamProperties.Application application = new IamProperties.Application();
        application.setType(type);
        application.setName(name);
        application.setEnabled(enabled);
        return application;
    }
}
