/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 验证动态菜单部署能力过滤；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.service.menu;

import com.innolux.iws.system.domain.SysMenu;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 动态菜单部署能力过滤测试。
 *
 * @author YONGJIE.PENG
 */
@DisplayName("动态菜单部署能力过滤")
@Tag("dev")
class MenuDeploymentFilterTest {

    private static final Set<Long> UNRELATED_MENUS = Set.of(1L, 2L, 3L, 4L);

    private static final Set<Long> ENDPOINT_MENUS = Set.of(100L, 101L, 102L, 103L, 104L, 110L, 111L);

    private static final Set<Long> PROCESS_MENUS = Set.of(200L, 201L, 202L, 203L, 204L, 210L, 211L);

    /**
     * 部署开关与模块类路径组合。
     *
     * @return 参数化场景
     */
    static Stream<Arguments> deploymentScenarios() {
        return Stream.of(
            Arguments.of("两个开关默认关闭时移除两个模块的完整子树", false, false, false, false, UNRELATED_MENUS),
            Arguments.of("仅 endpoint 开启时保留 endpoint 并移除 process", true, false, true, false, union(ENDPOINT_MENUS)),
            Arguments.of("仅 process 开启时保留 process 并移除 endpoint", false, true, false, true, union(PROCESS_MENUS)),
            Arguments.of("endpoint 开关开启但缺模块时移除 endpoint", true, false, false, false, UNRELATED_MENUS),
            Arguments.of("process 开关开启但缺模块时移除 process", false, true, false, false, UNRELATED_MENUS),
            Arguments.of("两个模块均已装配时保留全部菜单", true, true, true, true, union(ENDPOINT_MENUS, PROCESS_MENUS)),
            Arguments.of("开关为关闭时即使模块存在也不返回模块菜单", false, false, true, true, UNRELATED_MENUS),
            Arguments.of("开关开启但模块缺失时无关菜单不受影响", true, true, false, false, UNRELATED_MENUS)
        );
    }

    /**
     * 验证按部署能力过滤菜单及完整子树。
     *
     * @param scenarioName      场景说明
     * @param endpointProperty  endpoint 开关
     * @param processProperty   process 开关
     * @param endpointPresent   endpoint 模块是否存在
     * @param processPresent    process 模块是否存在
     * @param expectedMenuIds   预期保留的菜单标识
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("deploymentScenarios")
    void filtersMenusByDeploymentAvailability(String scenarioName, boolean endpointProperty, boolean processProperty,
                                              boolean endpointPresent, boolean processPresent, Set<Long> expectedMenuIds) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (endpointProperty) {
            properties.put(MenuDeploymentFilter.ENDPOINT_ENABLED_PROPERTY, "true");
        }
        if (processProperty) {
            properties.put(MenuDeploymentFilter.PROCESS_ENABLED_PROPERTY, "true");
        }
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("deployment-test", properties));
        MenuDeploymentFilter filter = new MenuDeploymentFilter(environment) {
            @Override
            boolean isClassPresent(String className) {
                return (endpointPresent && MenuDeploymentFilter.ENDPOINT_MARKER_CLASS.equals(className))
                    || (processPresent && MenuDeploymentFilter.PROCESS_MARKER_CLASS.equals(className));
            }
        };

        List<SysMenu> filtered = filter.filter(menuFixture());

        Set<Long> actualMenuIds = filtered.stream().map(SysMenu::getMenuId).collect(Collectors.toSet());
        assertEquals(expectedMenuIds, actualMenuIds);
    }

    /**
     * 构造包含模块根、普通子菜单、按钮和无模块标识后代的菜单集合。
     *
     * @return 扁平菜单
     */
    private static List<SysMenu> menuFixture() {
        return List.of(
            menu(1L, 0L, "system", null, null),
            menu(2L, 1L, "user", "system/user/index", "system:user:list"),
            menu(3L, 0L, "processing", "processing/dashboard/index", null),
            menu(4L, 3L, "process-report", null, null),

            menu(100L, 0L, "endpoint", null, null),
            menu(101L, 100L, "system", "endpoint/system/index", "endpoint:system:list"),
            menu(102L, 101L, "", null, "endpoint:system:add"),
            menu(103L, 100L, "shared", null, null),
            menu(104L, 103L, "detail", "views/shared/detail", null),
            menu(110L, 0L, "iws-endpoint", null, null),
            menu(111L, 110L, "alias", null, null),

            menu(200L, 0L, "process", null, null),
            menu(201L, 200L, "definition", "process/definition/index", "process:definition:list"),
            menu(202L, 201L, "", null, "process:definition:edit"),
            menu(203L, 200L, "misc", null, null),
            menu(204L, 203L, "detail", "views/process-misc/detail", null),
            menu(210L, 0L, "iws-process", null, null),
            menu(211L, 210L, "alias", "iws-process/misc/index", null)
        );
    }

    /**
     * 创建测试菜单节点。
     *
     * @param menuId    菜单标识
     * @param parentId  父菜单标识
     * @param path      路由地址
     * @param component 组件路径
     * @param perms     权限标识
     * @return 菜单节点
     */
    private static SysMenu menu(Long menuId, Long parentId, String path, String component, String perms) {
        SysMenu menu = new SysMenu();
        menu.setMenuId(menuId);
        menu.setParentId(parentId);
        menu.setPath(path);
        menu.setComponent(component);
        menu.setPerms(perms);
        return menu;
    }

    /**
     * 合并无关菜单与指定模块菜单。
     *
     * @param moduleMenus 模块菜单
     * @return 预期保留菜单
     */
    private static Set<Long> union(Set<Long> moduleMenus) {
        return union(moduleMenus, Set.of());
    }

    /**
     * 合并无关菜单与两个模块菜单。
     *
     * @param first  第一个模块菜单
     * @param second 第二个模块菜单
     * @return 预期保留菜单
     */
    private static Set<Long> union(Set<Long> first, Set<Long> second) {
        return Stream.of(UNRELATED_MENUS, first, second)
            .flatMap(Set::stream)
            .collect(Collectors.toUnmodifiableSet());
    }
}
