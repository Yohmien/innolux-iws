/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : endpoint HTTP fixture 测试专用会话与路由接线；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.http;

import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.system.api.model.LoginUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试专用 getInfo/getRouters 最小接线。
 *
 * <p>用于让 Web 使用真实 token、clientid 和 SaToken 登录态访问 endpoint 页面；
 * 不连接生产用户与菜单表，也不冒充生产完整接口。</p>
 */
@RestController
@RequestMapping("/system")
public class EndpointHttpFixtureSessionController {

    /**
     * 返回当前 fixture 登录用户。
     *
     * @return 用户、角色和权限
     */
    @GetMapping("/user/getInfo")
    public R<Map<String, Object>> getInfo() {
        LoginUser loginUser = LoginHelper.getLoginUser();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("userId", loginUser.getUserId());
        user.put("userName", loginUser.getUsername());
        user.put("nickName", loginUser.getNickname());
        user.put("avatarUrl", "");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", user);
        result.put("roles", loginUser.getRolePermission());
        result.put("permissions", loginUser.getMenuPermission());
        return R.ok(result);
    }

    /**
     * 返回最小 endpoint 路由树。
     *
     * @return 路由树
     */
    @GetMapping("/menu/getRouters")
    public R<List<Map<String, Object>>> getRouters() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", "Endpoint");
        root.put("path", "/endpoint");
        root.put("component", "Layout");
        root.put("redirect", "noRedirect");
        root.put("alwaysShow", true);
        root.put("meta", Map.of("title", "系统接入配置", "icon", "tool"));
        root.put("children", List.of(
            route("EndpointSystem", "system", "endpoint/system/index", "系统定义"),
            route("EndpointEnvironment", "environment", "endpoint/environment/index", "环境配置"),
            route("EndpointAddress", "address", "endpoint/address/index", "系统地址")
        ));
        return R.ok(List.of(root));
    }

    private Map<String, Object> route(String name, String path, String component, String title) {
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("name", name);
        route.put("path", path);
        route.put("component", component);
        route.put("meta", Map.of("title", title, "icon", "tool"));
        return route;
    }
}
