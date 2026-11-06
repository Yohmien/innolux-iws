/*
 * @Date         : 2026-11-06
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 角色菜单应用作用域合并规则；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.service.menu;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 角色菜单应用作用域合并规则。
 */
public final class RoleMenuScopeRules {

    private RoleMenuScopeRules() {
    }

    /**
     * 逐角色选择应用独立菜单或全局回退菜单。
     *
     * @param roleIds          用户角色
     * @param globalByRole     全局菜单
     * @param scopedRoleIds    已配置应用独立菜单的角色
     * @param applicationByRole 应用菜单
     * @return 合并后的菜单
     */
    public static Set<Long> effective(
        Collection<Long> roleIds,
        Map<Long, Set<Long>> globalByRole,
        Set<Long> scopedRoleIds,
        Map<Long, Set<Long>> applicationByRole
    ) {
        Set<Long> result = new LinkedHashSet<>();
        for (Long roleId : roleIds) {
            if (scopedRoleIds.contains(roleId)) {
                result.addAll(applicationByRole.getOrDefault(roleId, Set.of()));
            } else {
                result.addAll(globalByRole.getOrDefault(roleId, Set.of()));
            }
        }
        return result;
    }
}
