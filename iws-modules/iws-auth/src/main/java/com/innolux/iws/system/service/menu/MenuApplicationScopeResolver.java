/*
 * @Date         : 2026-11-06
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 按当前应用解析角色菜单授权，并保留全局授权回退；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.service.menu;

import com.innolux.iws.common.core.constant.SystemConstants;
import com.innolux.iws.common.core.utils.StringUtils;
import com.innolux.iws.system.domain.SysMenu;
import com.innolux.iws.system.domain.SysRoleMenu;
import com.innolux.iws.system.domain.SysRoleMenuApplication;
import com.innolux.iws.system.domain.SysRoleMenuScope;
import com.innolux.iws.system.domain.vo.SysRoleVo;
import com.innolux.iws.system.mapper.SysMenuMapper;
import com.innolux.iws.system.mapper.SysRoleMapper;
import com.innolux.iws.system.mapper.SysRoleMenuApplicationMapper;
import com.innolux.iws.system.mapper.SysRoleMenuMapper;
import com.innolux.iws.system.mapper.SysRoleMenuScopeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 角色菜单应用作用域解析器。
 *
 * <p>应用已配置独立菜单时只使用应用授权；没有独立配置时沿用全局
 * sys_role_menu。认证中心未声明自身 applicationId，因此读取权限时合并
 * 全局及全部应用授权，以保留门户对各应用接入资格的判断。</p>
 */
@Component
@RequiredArgsConstructor
public class MenuApplicationScopeResolver {

    private static final String APPLICATION_ID_PROPERTY = "iam.client.application-id";

    private final Environment environment;
    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysRoleMenuApplicationMapper applicationMenuMapper;
    private final SysRoleMenuScopeMapper applicationScopeMapper;
    private final SysMenuMapper menuMapper;

    /**
     * 当前进程绑定的统一身份应用标识。
     *
     * @return 业务 IWS 返回 applicationId；认证中心等未声明进程返回 null
     */
    public String currentApplicationId() {
        String value = environment.getProperty(APPLICATION_ID_PROPERTY);
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    /**
     * 按当前应用解析用户菜单权限。
     *
     * @param userId 用户ID
     * @return 当前应用有效权限；未声明应用的认证中心合并全部授权
     */
    public Set<String> effectiveMenuPermissions(Long userId) {
        List<Long> roleIds = roleIdsByUserId(userId);
        String applicationId = currentApplicationId();
        Set<Long> menuIds = applicationId == null
            ? allRoleMenuIds(roleIds)
            : effectiveRoleMenuIds(roleIds, applicationId);
        return menuPermissions(menuIds);
    }

    /**
     * 合并全局和全部应用菜单权限。
     *
     * @param userId 用户ID
     * @return 全部角色的菜单权限
     */
    public Set<String> allMenuPermissions(Long userId) {
        return menuPermissions(allRoleMenuIds(roleIdsByUserId(userId)));
    }

    /**
     * 按角色批量查询全部菜单权限。
     *
     * @param roleIds 角色ID列表
     * @return 角色权限映射
     */
    public Map<Long, Set<String>> allRoleMenuPermissions(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Set<Long>> menuIdsByRole = new LinkedHashMap<>();
        for (Long roleId : roleIds) {
            menuIdsByRole.put(roleId, new LinkedHashSet<>());
        }
        for (SysRoleMenu item : roleMenuMapper.lambda()
            .in(SysRoleMenu::getRoleId, roleIds)
            .list()) {
            menuIdsByRole.computeIfAbsent(item.getRoleId(), key -> new LinkedHashSet<>())
                .add(item.getMenuId());
        }
        for (SysRoleMenuApplication item : applicationMenuMapper.lambda()
            .in(SysRoleMenuApplication::getRoleId, roleIds)
            .list()) {
            menuIdsByRole.computeIfAbsent(item.getRoleId(), key -> new LinkedHashSet<>())
                .add(item.getMenuId());
        }
        Map<Long, Set<String>> result = new LinkedHashMap<>();
        menuIdsByRole.forEach((roleId, menuIds) ->
            result.put(roleId, menuPermissions(menuIds)));
        return result;
    }

    /**
     * 按当前应用查询用户菜单树。
     *
     * @param userId 用户ID
     * @return 正常状态的目录和菜单
     */
    public List<SysMenu> effectiveMenus(Long userId) {
        List<Long> roleIds = roleIdsByUserId(userId);
        String applicationId = currentApplicationId();
        Set<Long> menuIds = applicationId == null
            ? legacyRoleMenuIds(roleIds)
            : effectiveRoleMenuIds(roleIds, applicationId);
        if (menuIds.isEmpty()) {
            return List.of();
        }
        return menuMapper.lambda()
            .in(SysMenu::getMenuId, menuIds)
            .in(SysMenu::getMenuType, SystemConstants.TYPE_DIR, SystemConstants.TYPE_MENU)
            .eq(SysMenu::getStatus, SystemConstants.NORMAL)
            .orderByAsc(SysMenu::getParentId)
            .orderByAsc(SysMenu::getOrderNum)
            .list()
            .stream()
            .filter(menu -> isMenuVisibleForApplication(menu, applicationId))
            .toList();
    }

    /**
     * 过滤菜单定义到指定应用。
     *
     * @param menus         菜单
     * @param applicationId 应用标识；为空时不限制
     * @return 当前应用和共享菜单
     */
    public List<SysMenu> filterMenusForApplication(List<SysMenu> menus, String applicationId) {
        if (StringUtils.isBlank(applicationId)) {
            return menus;
        }
        return menus.stream()
            .filter(menu -> isMenuVisibleForApplication(menu, applicationId))
            .toList();
    }

    /**
     * 查询角色在指定应用中的菜单。
     *
     * @param roleId        角色ID
     * @param applicationId 应用标识
     * @return 显式应用授权；未配置时返回全局菜单
     */
    public List<Long> roleMenuIds(Long roleId, String applicationId) {
        if (StringUtils.isBlank(applicationId) || !isRoleScopeConfigured(roleId, applicationId)) {
            return roleMenuMapper.lambda()
                .eq(SysRoleMenu::getRoleId, roleId)
                .list()
                .stream()
                .map(SysRoleMenu::getMenuId)
                .distinct()
                .toList();
        }
        return applicationMenuMapper.lambda()
            .eq(SysRoleMenuApplication::getRoleId, roleId)
            .eq(SysRoleMenuApplication::getApplicationId, applicationId)
            .list()
            .stream()
            .map(SysRoleMenuApplication::getMenuId)
            .distinct()
            .toList();
    }

    /**
     * 判断角色是否已显式配置应用独立菜单。
     *
     * @param roleId        角色ID
     * @param applicationId 应用标识
     * @return true 已配置 false 未配置
     */
    public boolean isRoleScopeConfigured(Long roleId, String applicationId) {
        return StringUtils.isNotBlank(applicationId)
            && applicationScopeMapper.lambda()
            .eq(SysRoleMenuScope::getRoleId, roleId)
            .eq(SysRoleMenuScope::getApplicationId, applicationId)
            .exists();
    }

    /**
     * 查询用户的有效角色。
     *
     * @param userId 用户ID
     * @return 角色ID列表
     */
    private List<Long> roleIdsByUserId(Long userId) {
        return roleMapper.selectRolesByUserId(userId).stream()
            .map(SysRoleVo::getRoleId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    /**
     * 合并角色的全局和应用独立菜单。
     *
     * @param roleIds 角色ID列表
     * @return 全部菜单ID
     */
    private Set<Long> allRoleMenuIds(Collection<Long> roleIds) {
        Set<Long> result = new LinkedHashSet<>(legacyRoleMenuIds(roleIds));
        if (roleIds != null && !roleIds.isEmpty()) {
            applicationMenuMapper.lambda()
                .in(SysRoleMenuApplication::getRoleId, roleIds)
                .list()
                .stream()
                .map(SysRoleMenuApplication::getMenuId)
                .forEach(result::add);
        }
        return result;
    }

    /**
     * 查询角色的全局菜单。
     *
     * @param roleIds 角色ID列表
     * @return 全局菜单ID
     */
    private Set<Long> legacyRoleMenuIds(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Set.of();
        }
        return roleMenuMapper.lambda()
            .in(SysRoleMenu::getRoleId, roleIds)
            .list()
            .stream()
            .map(SysRoleMenu::getMenuId)
            .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    /**
     * 逐角色选择显式应用菜单或全局回退菜单。
     *
     * @param roleIds       角色ID列表
     * @param applicationId 应用标识
     * @return 有效菜单ID
     */
    private Set<Long> effectiveRoleMenuIds(Collection<Long> roleIds, String applicationId) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Set.of();
        }
        Map<Long, Set<Long>> legacyMenuIdsByRole = legacyRoleMenuIdsByRole(roleIds);
        Set<Long> scopedRoleIds = applicationScopeMapper.lambda()
            .in(SysRoleMenuScope::getRoleId, roleIds)
            .eq(SysRoleMenuScope::getApplicationId, applicationId)
            .list()
            .stream()
            .map(SysRoleMenuScope::getRoleId)
            .collect(java.util.stream.Collectors.toSet());
        if (scopedRoleIds.isEmpty()) {
            return legacyMenuIdsByRole.values().stream()
                .flatMap(Set::stream)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        }

        Map<Long, Set<Long>> applicationMenuIdsByRole = new HashMap<>();
        for (SysRoleMenuApplication item : applicationMenuMapper.lambda()
            .in(SysRoleMenuApplication::getRoleId, scopedRoleIds)
            .eq(SysRoleMenuApplication::getApplicationId, applicationId)
            .list()) {
            applicationMenuIdsByRole.computeIfAbsent(item.getRoleId(), key -> new LinkedHashSet<>())
                .add(item.getMenuId());
        }
        return RoleMenuScopeRules.effective(
            roleIds,
            legacyMenuIdsByRole,
            scopedRoleIds,
            applicationMenuIdsByRole
        );
    }

    /**
     * 按角色索引全局菜单。
     *
     * @param roleIds 角色ID列表
     * @return 角色菜单映射
     */
    private Map<Long, Set<Long>> legacyRoleMenuIdsByRole(Collection<Long> roleIds) {
        Map<Long, Set<Long>> result = new LinkedHashMap<>();
        for (SysRoleMenu item : roleMenuMapper.lambda()
            .in(SysRoleMenu::getRoleId, roleIds)
            .list()) {
            result.computeIfAbsent(item.getRoleId(), key -> new LinkedHashSet<>())
                .add(item.getMenuId());
        }
        return result;
    }

    /**
     * 将菜单ID转换为权限字符串。
     *
     * @param menuIds 菜单ID
     * @return 权限字符串
     */
    private Set<String> menuPermissions(Collection<Long> menuIds) {
        if (menuIds == null || menuIds.isEmpty()) {
            return Set.of();
        }
        return menuMapper.lambda()
            .in(SysMenu::getMenuId, menuIds)
            .list()
            .stream()
            .filter(menu -> isMenuVisibleForApplication(menu, currentApplicationId()))
            .map(SysMenu::getPerms)
            .filter(StringUtils::isNotBlank)
            .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * 判断菜单定义是否属于当前应用或为共享菜单。
     *
     * @param menu          菜单
     * @param applicationId 当前应用；为空表示认证中心管理端
     * @return 是否可见
     */
    private boolean isMenuVisibleForApplication(SysMenu menu, String applicationId) {
        return MenuApplicationScopeRules.isVisible(menu.getApplicationId(), applicationId);
    }
}
