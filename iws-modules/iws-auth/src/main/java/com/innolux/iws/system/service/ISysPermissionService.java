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

package com.innolux.iws.system.service;

import com.innolux.iws.system.api.domain.RoleDTO;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户权限处理
 *
 * @author YONGJIE.PENG
 */
public interface ISysPermissionService {

    /**
     * 获取角色数据权限
     *
     * @param userId 用户id
     * @return 角色权限信息
     */
    Set<String> getRolePermission(Long userId);

    /**
     * 获取菜单数据权限
     *
     * @param userId 用户id
     * @return 菜单权限信息
     */
    Set<String> getMenuPermission(Long userId);

    /**
     * 根据角色列表构建数据权限角色映射
     *
     * @param roles 角色列表
     * @return key 为权限码 value 为命中的角色ID列表
     */
    Map<String, List<Long>> getDataScopeRoleMap(List<RoleDTO> roles);

}
