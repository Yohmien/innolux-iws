/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : process HTTP fixture 测试专用会话与菜单接线；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.processfixture.http;

import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.system.api.model.LoginUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试专用 getInfo/getRouters 最小接线。
 *
 * <p>菜单与当前 process 管理页、隐藏实例详情页保持一致，不连接生产用户或菜单表。</p>
 */
@RestController
@RequestMapping("/system")
public class ProcessHttpFixtureSessionController {

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
     * 返回 process 管理页和隐藏页菜单。
     *
     * @return 路由树
     */
    @GetMapping("/menu/getRouters")
    public R<List<Map<String, Object>>> getRouters() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", "Process");
        root.put("path", "/process");
        root.put("component", "Layout");
        root.put("redirect", "noRedirect");
        root.put("alwaysShow", true);
        root.put("meta", meta("计划事件", "tree-table", null));
        List<Map<String, Object>> children = new ArrayList<>();
        children.add(route("ProcessSchedule", "schedule", "process/schedule/index",
            "业务调度", "calendar", false, null));
        children.add(route("ProcessDefinition", "definition", "process/definition/index",
            "计划定义", "tree", false, null));
        children.add(route("ProcessExecution", "execution", "process/execution/index",
            "执行总览", "dashboard", false, null));
        children.add(route("ProcessInstanceDetail", "instance/detail", "process/instance/detail",
            "计划详情", "#", true, "/process/execution"));
        children.add(route("ProcessDefinitionEdit", "definition/edit", "process/definition/edit",
            "计划配置编辑", "#", true, "/process/definition"));
        root.put("children", children);
        return R.ok(List.of(root));
    }

    private Map<String, Object> route(String name, String path, String component, String title,
                                      String icon, boolean hidden, String activeMenu) {
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("name", name);
        route.put("path", path);
        route.put("component", component);
        if (hidden) {
            route.put("hidden", true);
        }
        route.put("meta", meta(title, icon, activeMenu));
        return route;
    }

    private Map<String, Object> meta(String title, String icon, String activeMenu) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("title", title);
        meta.put("icon", icon);
        if (activeMenu != null) {
            meta.put("activeMenu", activeMenu);
        }
        return meta;
    }
}
