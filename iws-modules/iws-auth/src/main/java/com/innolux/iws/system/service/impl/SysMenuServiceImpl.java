/*
 * @Date         : 2026-09-19
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.tree.Tree;
import cn.hutool.core.util.ObjectUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.innolux.iws.common.core.constant.Constants;
import com.baomidou.dynamic.datasource.annotation.DS;
import com.innolux.iws.common.core.constant.SystemConstants;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.core.utils.MapstructUtils;
import com.innolux.iws.common.core.utils.StringUtils;
import com.innolux.iws.common.core.utils.TreeBuildUtils;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.system.domain.SysMenu;
import com.innolux.iws.system.domain.SysRole;
import com.innolux.iws.system.domain.SysRoleMenu;
import com.innolux.iws.system.domain.SysRoleMenuApplication;
import com.innolux.iws.system.domain.bo.SysMenuBo;
import com.innolux.iws.system.domain.vo.MetaVo;
import com.innolux.iws.system.domain.vo.RouterVo;
import com.innolux.iws.system.domain.vo.SysMenuVo;
import com.innolux.iws.system.mapper.SysMenuMapper;
import com.innolux.iws.system.mapper.SysRoleMapper;
import com.innolux.iws.system.mapper.SysRoleMenuApplicationMapper;
import com.innolux.iws.system.mapper.SysRoleMenuMapper;
import com.innolux.iws.system.service.ISysMenuService;
import com.innolux.iws.system.service.menu.MenuApplicationScopeRules;
import com.innolux.iws.system.service.menu.MenuApplicationScopeResolver;
import com.innolux.iws.system.service.menu.MenuDeploymentFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 菜单 业务层处理
 *
 * @author YONGJIE.PENG
 */
@Slf4j
@RequiredArgsConstructor
@Service
@DS("identity")
public class SysMenuServiceImpl implements ISysMenuService {

    private final SysMenuMapper menuMapper;
    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysRoleMenuApplicationMapper roleMenuApplicationMapper;
    private final MenuDeploymentFilter menuDeploymentFilter;
    private final MenuApplicationScopeResolver menuApplicationScopeResolver;

    /**
     * 根据用户查询系统菜单列表
     *
     * @param userId 用户ID
     * @return 菜单列表
     */
    @Override
    public List<SysMenuVo> selectMenuList(Long userId) {
        return selectMenuList(new SysMenuBo(), userId);
    }

    /**
     * 查询系统菜单列表
     *
     * @param menu   菜单筛选条件
     * @param userId 当前查询的用户主键
     * @return 菜单列表
     */
    @Override
    public List<SysMenuVo> selectMenuList(SysMenuBo menu, Long userId) {
        String applicationId = resolveQueryApplicationId(menu.getApplicationId());
        // 管理员显示所有菜单信息 不是管理员 按用户id过滤菜单
        List<SysMenuVo> menus;
        if (LoginHelper.isSuperAdmin(userId)) {
            menus = menuMapper.lambda()
                .likeIfText(SysMenu::getMenuName, menu.getMenuName())
                .eqIfText(SysMenu::getVisible, menu.getVisible())
                .eqIfText(SysMenu::getStatus, menu.getStatus())
                .eqIfText(SysMenu::getMenuType, menu.getMenuType())
                .eqIfPresent(SysMenu::getParentId, menu.getParentId())
                .orderByAsc(SysMenu::getParentId)
                .orderByAsc(SysMenu::getOrderNum)
                .voList();
        } else {
            menus = menuMapper.selectMenuListByUserId(menu, userId);
        }
        return filterMenuListByApplication(menus, applicationId);
    }

    /**
     * 根据用户ID查询权限
     *
     * @param userId 用户ID
     * @return 权限列表
     */
    @Override
    public Set<String> selectMenuPermsByUserId(Long userId) {
        return menuApplicationScopeResolver.effectiveMenuPermissions(userId);
    }

    /**
     * 根据角色ID查询权限
     *
     * @param roleId 角色ID
     * @return 权限列表
     */
    @Override
    public Set<String> selectMenuPermsByRoleId(Long roleId) {
        return menuApplicationScopeResolver
            .allRoleMenuPermissions(List.of(roleId))
            .getOrDefault(roleId, Set.of());
    }

    /**
     * 根据角色ID列表批量查询权限
     *
     * @param roleIds 角色ID列表
     * @return 角色权限映射
     */
    @Override
    public Map<Long, Set<String>> selectMenuPermsByRoleIds(Collection<Long> roleIds) {
        return menuApplicationScopeResolver.allRoleMenuPermissions(roleIds);
    }

    /**
     * 根据用户ID查询菜单树信息
     *
     * @param userId 用户ID
     * @return 按树结构组织的菜单列表
     */
    @Override
    public List<SysMenu> selectMenuTreeByUserId(Long userId) {
        List<SysMenu> menus;
        if (LoginHelper.isSuperAdmin(userId)) {
            menus = menuApplicationScopeResolver.filterMenusForApplication(
                menuMapper.selectMenuTreeAll(),
                menuApplicationScopeResolver.currentApplicationId()
            );
        } else {
            menus = menuApplicationScopeResolver.effectiveMenus(userId);
        }
        menus = menuDeploymentFilter.filter(menus);
        if (CollUtil.isEmpty(menus)) {
            return CollUtil.newArrayList();
        }

        List<SysMenu> menuTree = TreeBuildUtils.build(menus, Constants.TOP_PARENT_ID, SysMenu::getParentId, (menu, nodeTreeMaps) -> {
            // 将当前节点的菜单ID用作父节点ID
            Long menuParentId = menu.getMenuId();
            // 从动态规划表中取出子节点列表
            // 如果不存在子节点，则返回一个空的列表，确保数据在进行JSON序列化时该字段的类型和结构是正确的
            List<SysMenu> childMenus = nodeTreeMaps.getOrDefault(menuParentId, Collections.emptyList());
            // 设置子节点
            // 如果存在根节点指向尾节点的情况，则会出现环形依赖。但在菜单表中基本不会出现这种情况...
            menu.setChildren(childMenus);
        });
        return CollUtil.isEmpty(menuTree) ? CollUtil.newArrayList() : menuTree;
    }

    /**
     * 根据角色ID查询菜单树信息
     *
     * @param roleId 角色ID
     * @return 选中菜单列表
     */
    @Override
    public List<Long> selectMenuListByRoleId(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        return menuMapper.selectMenuListByRoleId(roleId, role.getMenuCheckStrictly());
    }

    /**
     * 根据角色ID和应用标识查询有效菜单。
     *
     * @param roleId        角色ID
     * @param applicationId 应用标识
     * @return 选中菜单列表
     */
    @Override
    public List<Long> selectMenuListByRoleId(Long roleId, String applicationId) {
        SysRole role = roleMapper.selectById(roleId);
        String effectiveApplicationId = resolveQueryApplicationId(applicationId);
        List<Long> menuIds = filterMenuIdsByApplication(
            menuApplicationScopeResolver.roleMenuIds(roleId, effectiveApplicationId),
            effectiveApplicationId
        );
        if (role == null || menuIds.isEmpty()) {
            return menuIds;
        }
        if (!Boolean.TRUE.equals(role.getMenuCheckStrictly())) {
            return menuIds;
        }
        Map<Long, Long> parentByMenuId = menuMapper.lambda()
            .select(SysMenu::getMenuId, SysMenu::getParentId)
            .in(SysMenu::getMenuId, menuIds)
            .list()
            .stream()
            .filter(menu -> menu.getMenuId() != null && menu.getParentId() != null)
            .collect(java.util.stream.Collectors.toMap(
                SysMenu::getMenuId,
                SysMenu::getParentId,
                (left, right) -> left
            ));
        Set<Long> parentIds = new HashSet<>(parentByMenuId.values());
        return menuIds.stream()
            .filter(menuId -> !parentIds.contains(menuId))
            .toList();
    }

    /**
     * 判断角色是否显式配置了应用独立菜单。
     *
     * @param roleId        角色ID
     * @param applicationId 应用标识
     * @return true 已配置 false 未配置
     */
    @Override
    public boolean isRoleMenuScopeConfigured(Long roleId, String applicationId) {
        return menuApplicationScopeResolver.isRoleScopeConfigured(roleId, applicationId);
    }

    /**
     * 构建前端路由所需要的菜单
     * 路由name命名规则 path首字母转大写 + id
     *
     * @param menus 菜单列表
     * @return 路由列表
     */
    @Override
    public List<RouterVo> buildMenus(List<SysMenu> menus) {
        if (CollUtil.isEmpty(menus)) {
            return CollUtil.newArrayList();
        }
        List<RouterVo> routers = new LinkedList<>();
        for (SysMenu menu : menus) {
            String name = menu.getRouteName() + menu.getMenuId();
            RouterVo router = new RouterVo();
            router.setHidden("1".equals(menu.getVisible()));
            router.setName(name);
            router.setPath(menu.getRouterPath());
            router.setComponent(menu.getComponentInfo());
            router.setQuery(menu.getQueryParam());
            router.setExt(menu.getExt());
            router.setMeta(
                new MetaVo(
                    menu.getMenuName(),
                    menu.getIcon(),
                    StringUtils.equals(SystemConstants.NO, menu.getIsCache()),
                    menu.getPath(),
                    menu.getActiveMenu()
                ).withMenuId(menu.getMenuId())
            );
            List<SysMenu> cMenus = menu.getChildren();
            if (CollUtil.isNotEmpty(cMenus) && SystemConstants.TYPE_DIR.equals(menu.getMenuType())) {
                router.setAlwaysShow(true);
                router.setRedirect("noRedirect");
                router.setChildren(buildMenus(cMenus));
            } else if (menu.isMenuFrame()) {
                String frameName = StringUtils.capitalize(menu.getPath()) + menu.getMenuId();
                router.setMeta(null);
                List<RouterVo> childrenList = new ArrayList<>();
                RouterVo children = new RouterVo();
                children.setPath(menu.getPath());
                children.setComponent(menu.getComponent());
                children.setName(frameName);
                children.setMeta(
                    new MetaVo(
                        menu.getMenuName(),
                        menu.getIcon(),
                        StringUtils.equals(SystemConstants.NO, menu.getIsCache()),
                        menu.getPath(),
                        menu.getActiveMenu()
                    ).withMenuId(menu.getMenuId())
                );
                children.setQuery(menu.getQueryParam());
                children.setExt(menu.getExt());
                childrenList.add(children);
                router.setChildren(childrenList);
            } else if (menu.getParentId().equals(Constants.TOP_PARENT_ID) && menu.isInnerLink()) {
                router.setMeta(new MetaVo(menu.getMenuName(), menu.getIcon()).withMenuId(menu.getMenuId()));
                router.setPath("/");
                List<RouterVo> childrenList = new ArrayList<>();
                RouterVo children = new RouterVo();
                String routerPath = SysMenu.innerLinkReplaceEach(menu.getPath());
                String innerLinkName = StringUtils.capitalize(routerPath) + menu.getMenuId();
                children.setPath(routerPath);
                children.setComponent(SystemConstants.INNER_LINK);
                children.setName(innerLinkName);
                children.setMeta(
                    new MetaVo(menu.getMenuName(), menu.getIcon(), menu.getPath())
                        .withMenuId(menu.getMenuId())
                );
                children.setExt(menu.getExt());
                childrenList.add(children);
                router.setChildren(childrenList);
            }
            routers.add(router);
        }
        return routers;
    }

    /**
     * 构建前端所需要下拉树结构
     *
     * @param menus 菜单列表
     * @return 下拉树结构列表
     */
    @Override
    public List<Tree<Long>> buildMenuTreeSelect(List<SysMenuVo> menus) {
        if (CollUtil.isEmpty(menus)) {
            return CollUtil.newArrayList();
        }
        return TreeBuildUtils.build(menus, (menu, tree) -> {
            Tree<Long> menuTree = tree.setId(menu.getMenuId())
                .setParentId(menu.getParentId())
                .setName(menu.getMenuName())
                .setWeight(menu.getOrderNum());
            menuTree.put("menuType", menu.getMenuType());
            menuTree.put("icon", menu.getIcon());
            menuTree.put("visible", menu.getVisible());
            menuTree.put("status", menu.getStatus());
        });
    }

    /**
     * 根据菜单ID查询信息
     *
     * @param menuId 菜单ID
     * @return 菜单信息
     */
    @Override
    public SysMenuVo selectMenuById(Long menuId) {
        return menuMapper.selectVoById(menuId);
    }

    /**
     * 是否存在菜单子节点
     *
     * @param menuId 菜单ID
     * @return 结果
     */
    @Override
    public boolean hasChildByMenuId(Long menuId) {
        return menuMapper.lambda().eq(SysMenu::getParentId, menuId).exists();
    }

    /**
     * 是否存在菜单子节点
     *
     * @param menuIds 菜单ID列表
     * @return 结果
     */
    @Override
    public boolean hasChildByMenuId(Collection<Long> menuIds) {
        return menuMapper.lambda()
            .in(SysMenu::getParentId, menuIds)
            .notIn(SysMenu::getMenuId, menuIds)
            .exists();
    }

    /**
     * 查询菜单使用数量
     *
     * @param menuId 菜单ID
     * @return 结果
     */
    @Override
    public boolean checkMenuExistRole(Long menuId) {
        return roleMenuMapper.lambda().eq(SysRoleMenu::getMenuId, menuId).exists()
            || roleMenuApplicationMapper.lambda()
            .eq(SysRoleMenuApplication::getMenuId, menuId)
            .exists();
    }

    /**
     * 新增保存菜单信息
     *
     * @param bo 菜单信息
     * @return 结果
     */
    @Override
    public int insertMenu(SysMenuBo bo) {
        prepareMenuApplication(bo);
        validateParentApplication(bo);
        SysMenu menu = MapstructUtils.convert(bo, SysMenu.class);
        return menuMapper.insert(menu);
    }

    /**
     * 修改保存菜单信息
     *
     * @param bo 菜单信息
     * @return 结果
     */
    @Override
    public int updateMenu(SysMenuBo bo) {
        prepareMenuApplication(bo);
        validateParentApplication(bo);
        validateChildrenApplication(bo);
        SysMenu menu = MapstructUtils.convert(bo, SysMenu.class);
        return menuMapper.updateById(menu);
    }

    /**
     * 删除菜单管理信息
     *
     * @param menuId 菜单ID
     * @return 结果
     */
    @Override
    public int deleteMenuById(Long menuId) {
        return menuMapper.deleteById(menuId);
    }

    /**
     * 批量删除菜单管理信息
     *
     * @param menuIds 菜单ID串
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMenuById(Collection<Long> menuIds) {
        menuMapper.deleteByIds(menuIds);
        roleMenuMapper.deleteByMenuIds(menuIds);
        roleMenuApplicationMapper.deleteByMenuIds(menuIds);
    }

    /**
     * 校验菜单名称是否唯一
     *
     * @param menu 菜单信息
     * @return 结果
     */
    @Override
    public boolean checkMenuNameUnique(SysMenuBo menu) {
        String applicationId = MenuApplicationScopeRules.normalize(menu.getApplicationId());
        boolean exist = menuMapper.lambda()
            .eq(SysMenu::getMenuName, menu.getMenuName())
            .eq(SysMenu::getParentId, menu.getParentId())
            .in(SysMenu::getApplicationId, readableApplicationIds(applicationId))
            .neIfPresent(SysMenu::getMenuId, menu.getMenuId())
            .exists();
        return !exist;
    }

    /**
     * 校验路由组合是否唯一
     *
     * @param menuBo 菜单信息
     * @return 结果
     */
    @Override
    public boolean checkRouteConfigUnique(SysMenuBo menuBo) {
        SysMenu menu = MapstructUtils.convert(menuBo, SysMenu.class);
        if (SystemConstants.TYPE_BUTTON.equals(menu.getMenuType())) {
            return true;
        }
        String applicationId = MenuApplicationScopeRules.normalize(menuBo.getApplicationId());
        long menuId = ObjectUtil.isNull(menu.getMenuId()) ? -1L : menu.getMenuId();
        Long parentId = menu.getParentId();
        String path = menu.getPath();
        String routeName = StringUtils.isEmpty(menu.getRouteName()) ? path : menu.getRouteName();
        List<SysMenu> sysMenuList = menuMapper.lambda()
            .in(SysMenu::getMenuType, SystemConstants.TYPE_DIR, SystemConstants.TYPE_MENU)
            .in(SysMenu::getApplicationId, readableApplicationIds(applicationId))
            .and(w ->
                w.eq(SysMenu::getPath, path).or().eq(SysMenu::getPath, routeName)
            ).list();
        for (SysMenu sysMenu : sysMenuList) {
            if (!sysMenu.getMenuId().equals(menuId)) {
                Long dbParentId = sysMenu.getParentId();
                String dbPath = sysMenu.getPath();
                String dbRouteName = StringUtils.isEmpty(sysMenu.getRouteName()) ? dbPath : sysMenu.getRouteName();
                if (StringUtils.equalsAnyIgnoreCase(path, dbPath) && parentId.equals(dbParentId)) {
                    log.warn("[同级路由冲突] 同级下已存在相同路由路径 '{}'，冲突菜单：{}", dbPath, sysMenu.getMenuName());
                    return false;
                } else if (StringUtils.equalsAnyIgnoreCase(path, dbPath)
                    && Constants.TOP_PARENT_ID.equals(parentId)
                    && Constants.TOP_PARENT_ID.equals(dbParentId)) {
                    log.warn("[根目录路由冲突] 根目录下路由 '{}' 必须唯一，已被菜单 '{}' 占用", path, sysMenu.getMenuName());
                    return false;
                } else if (StringUtils.equalsAnyIgnoreCase(routeName, dbRouteName)
                    && sysMenu.getMenuType().equals(menu.getMenuType())) {
                    log.warn("[路由名称冲突] 路由名称 '{}' 需全局唯一，已被菜单 '{}' 使用", routeName, sysMenu.getMenuName());
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 过滤菜单视图到当前应用和共享菜单。
     *
     * @param menus         菜单
     * @param applicationId 应用标识；为空时不限制
     * @return 过滤结果
     */
    private List<SysMenuVo> filterMenuListByApplication(
        List<SysMenuVo> menus,
        String applicationId
    ) {
        if (StringUtils.isBlank(applicationId)) {
            return menus;
        }
        return menus.stream()
            .filter(menu -> MenuApplicationScopeRules.isVisible(
                menu.getApplicationId(),
                applicationId
            ))
            .toList();
    }

    /**
     * 过滤角色菜单 ID 到指定应用的完整菜单定义。
     *
     * @param menuIds       菜单 ID
     * @param applicationId 应用标识
     * @return 应用范围内菜单 ID
     */
    private List<Long> filterMenuIdsByApplication(
        Collection<Long> menuIds,
        String applicationId
    ) {
        if (menuIds == null || menuIds.isEmpty() || StringUtils.isBlank(applicationId)) {
            return menuIds == null ? List.of() : List.copyOf(menuIds);
        }
        Set<Long> visibleMenuIds = menuMapper.lambda()
            .select(SysMenu::getMenuId, SysMenu::getApplicationId)
            .in(SysMenu::getMenuId, menuIds)
            .list()
            .stream()
            .filter(menu -> MenuApplicationScopeRules.isVisible(
                menu.getApplicationId(),
                applicationId
            ))
            .map(SysMenu::getMenuId)
            .collect(java.util.stream.Collectors.toSet());
        return menuIds.stream()
            .filter(visibleMenuIds::contains)
            .toList();
    }

    /**
     * 解析菜单查询使用的应用范围；业务进程始终以自身应用为准。
     *
     * @param requestedApplicationId 请求应用标识
     * @return 生效应用标识
     */
    private String resolveQueryApplicationId(String requestedApplicationId) {
        String currentApplicationId = menuApplicationScopeResolver.currentApplicationId();
        return StringUtils.isBlank(currentApplicationId)
            ? requestedApplicationId
            : currentApplicationId;
    }

    /**
     * 规范化菜单写入范围并阻止业务进程跨应用修改。
     *
     * @param menu 菜单参数
     */
    private void prepareMenuApplication(SysMenuBo menu) {
        String currentApplicationId = menuApplicationScopeResolver.currentApplicationId();
        String requestedApplicationId = MenuApplicationScopeRules.normalize(
            menu.getApplicationId()
        );
        if (StringUtils.isNotBlank(currentApplicationId)
            && !currentApplicationId.equals(requestedApplicationId)) {
            throw new ServiceException("业务进程只能维护当前应用菜单");
        }
        menu.setApplicationId(
            StringUtils.isBlank(currentApplicationId)
                ? requestedApplicationId
                : currentApplicationId
        );
    }

    /**
     * 校验新增或修改菜单的父节点归属。
     *
     * @param menu 菜单参数
     */
    private void validateParentApplication(SysMenuBo menu) {
        if (menu.getParentId() == null || Constants.TOP_PARENT_ID.equals(menu.getParentId())) {
            return;
        }
        SysMenu parent = menuMapper.selectById(menu.getParentId());
        if (parent == null) {
            throw new ServiceException("上级菜单不存在");
        }
        if (!MenuApplicationScopeRules.isParentCompatible(
            parent.getApplicationId(),
            menu.getApplicationId()
        )) {
            throw new ServiceException("菜单应用范围与上级菜单不兼容");
        }
    }

    /**
     * 校验菜单改变应用范围后，直属子菜单仍属于有效范围。
     *
     * @param menu 菜单参数
     */
    private void validateChildrenApplication(SysMenuBo menu) {
        if (menu.getMenuId() == null) {
            return;
        }
        SysMenu existing = menuMapper.selectById(menu.getMenuId());
        if (existing == null
            || MenuApplicationScopeRules.normalize(existing.getApplicationId())
            .equals(menu.getApplicationId())) {
            return;
        }
        boolean incompatible = menuMapper.lambda()
            .eq(SysMenu::getParentId, menu.getMenuId())
            .list()
            .stream()
            .anyMatch(child -> !MenuApplicationScopeRules.isParentCompatible(
                menu.getApplicationId(),
                child.getApplicationId()
            ));
        if (incompatible) {
            throw new ServiceException("菜单应用范围与子菜单不兼容");
        }
    }

    /**
     * 计算应用菜单保存时需要检查冲突的定义范围。
     *
     * @param applicationId 应用标识
     * @return 共享及当前应用范围
     */
    private List<String> readableApplicationIds(String applicationId) {
        return MenuApplicationScopeRules.SHARED_APPLICATION_ID.equals(applicationId)
            ? List.of(MenuApplicationScopeRules.SHARED_APPLICATION_ID)
            : List.of(MenuApplicationScopeRules.SHARED_APPLICATION_ID, applicationId);
    }

}
