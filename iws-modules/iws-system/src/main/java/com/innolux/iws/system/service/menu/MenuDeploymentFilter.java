/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 按部署能力过滤动态菜单；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.service.menu;

import com.innolux.iws.system.domain.SysMenu;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 按 endpoint/process 部署能力过滤动态菜单。
 *
 * <p>两个开关默认关闭；开关开启后仍要求模块标记类位于当前类路径。
 * 被关闭模块的根菜单连同全部后代一起移除，避免子路由残留。</p>
 */
@Component
public class MenuDeploymentFilter {

    /**
     * endpoint 模块部署开关。
     */
    static final String ENDPOINT_ENABLED_PROPERTY = "iws.endpoint.enabled";

    /**
     * process 模块部署开关。
     */
    static final String PROCESS_ENABLED_PROPERTY = "iws.process.enabled";

    /**
     * endpoint 模块装配标记类。
     */
    static final String ENDPOINT_MARKER_CLASS = "com.innolux.iws.endpoint.config.EndpointProperties";

    /**
     * process 模块装配标记类。
     */
    static final String PROCESS_MARKER_CLASS = "com.innolux.iws.process.config.ProcessProperties";

    private final Environment environment;

    /**
     * 创建部署菜单过滤器。
     *
     * @param environment 部署配置
     */
    public MenuDeploymentFilter(Environment environment) {
        this.environment = environment;
    }

    /**
     * 判断模块标记类是否位于当前类路径。
     *
     * @param className 标记类名
     * @return 是否存在
     */
    boolean isClassPresent(String className) {
        return ClassUtils.isPresent(className, MenuDeploymentFilter.class.getClassLoader());
    }

    /**
     * 过滤当前部署不可用的模块菜单及其子菜单。
     *
     * @param menus 数据库查询得到的扁平菜单
     * @return 过滤后的扁平菜单
     */
    public List<SysMenu> filter(List<SysMenu> menus) {
        if (menus == null || menus.isEmpty()) {
            return menus;
        }
        boolean endpointEnabled = isEnabled(DeployableModule.ENDPOINT);
        boolean processEnabled = isEnabled(DeployableModule.PROCESS);
        if (endpointEnabled && processEnabled) {
            return menus;
        }
        Set<Long> removedIds = removedMenuIds(menus, endpointEnabled, processEnabled);
        if (removedIds.isEmpty()) {
            return menus;
        }
        return menus.stream()
            .filter(menu -> !removedIds.contains(menu.getMenuId()))
            .toList();
    }

    /**
     * 判断模块开关是否开启且模块已装配。
     *
     * @param module 部署模块
     * @return 模块菜单是否可用
     */
    private boolean isEnabled(DeployableModule module) {
        Boolean enabled = environment.getProperty(module.propertyKey, Boolean.class);
        return Boolean.TRUE.equals(enabled) && isClassPresent(module.markerClass);
    }

    /**
     * 收集应移除的菜单及其全部后代标识。
     *
     * @param menus           扁平菜单
     * @param endpointEnabled endpoint 是否可用
     * @param processEnabled  process 是否可用
     * @return 应移除的菜单标识
     */
    private static Set<Long> removedMenuIds(List<SysMenu> menus, boolean endpointEnabled, boolean processEnabled) {
        Map<Long, List<SysMenu>> childrenByParent = new HashMap<>();
        for (SysMenu menu : menus) {
            if (menu.getMenuId() != null && menu.getParentId() != null) {
                childrenByParent.computeIfAbsent(menu.getParentId(), key -> new ArrayList<>()).add(menu);
            }
        }
        Deque<Long> pending = new ArrayDeque<>();
        for (SysMenu menu : menus) {
            if (menu.getMenuId() == null) {
                continue;
            }
            if ((!endpointEnabled && DeployableModule.ENDPOINT.owns(menu))
                || (!processEnabled && DeployableModule.PROCESS.owns(menu))) {
                pending.addLast(menu.getMenuId());
            }
        }
        Set<Long> removedIds = new HashSet<>();
        while (!pending.isEmpty()) {
            Long menuId = pending.removeFirst();
            if (!removedIds.add(menuId)) {
                continue;
            }
            for (SysMenu child : childrenByParent.getOrDefault(menuId, List.of())) {
                pending.addLast(child.getMenuId());
            }
        }
        return removedIds;
    }

    /**
     * 可部署模块及其菜单命名空间。
     */
    private enum DeployableModule {

        /**
         * 系统接入配置模块。
         */
        ENDPOINT(ENDPOINT_ENABLED_PROPERTY, ENDPOINT_MARKER_CLASS, Set.of("endpoint", "iws-endpoint")),

        /**
         * 计划事件模块。
         */
        PROCESS(PROCESS_ENABLED_PROPERTY, PROCESS_MARKER_CLASS, Set.of("process", "iws-process"));

        private final String propertyKey;
        private final String markerClass;
        private final Set<String> namespaces;

        DeployableModule(String propertyKey, String markerClass, Set<String> namespaces) {
            this.propertyKey = propertyKey;
            this.markerClass = markerClass;
            this.namespaces = namespaces;
        }

        /**
         * 判断菜单是否属于当前模块。
         *
         * @param menu 菜单
         * @return 是否属于当前模块
         */
        private boolean owns(SysMenu menu) {
            return namespaces.contains(firstSegment(menu.getPath(), '/'))
                || namespaces.contains(firstSegment(menu.getComponent(), '/'))
                || namespaces.contains(firstSegment(menu.getPerms(), ':'));
        }
    }

    /**
     * 读取命名空间首段并统一大小写。
     *
     * @param value     原始值
     * @param separator 分隔符
     * @return 规范化后的首段
     */
    private static String firstSegment(String value, char separator) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/');
        int start = 0;
        while (start < normalized.length() && normalized.charAt(start) == '/') {
            start++;
        }
        int end = normalized.indexOf(separator, start);
        String segment = end < 0 ? normalized.substring(start) : normalized.substring(start, end);
        return segment.toLowerCase(Locale.ROOT);
    }
}
