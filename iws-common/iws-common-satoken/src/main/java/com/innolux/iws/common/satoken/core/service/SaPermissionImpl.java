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

package com.innolux.iws.common.satoken.core.service;

import cn.dev33.satoken.stp.StpInterface;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.core.service.PermissionService;
import com.innolux.iws.common.core.utils.SpringUtils;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.system.api.model.LoginUser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;

/**
 * sa-token 权限管理实现类
 *
 * @author YONGJIE.PENG
 */
public class SaPermissionImpl implements StpInterface {

    /**
     * 获取指定登录对象的菜单权限列表。
     *
     * @param loginId   登录ID
     * @param loginType 登录类型
     * @return 菜单权限列表
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return resolvePermissionList(loginId, LoginUser::getMenuPermission, PermissionService::getMenuPermission);
    }

    /**
     * 获取指定登录对象的角色权限列表。
     *
     * @param loginId   登录ID
     * @param loginType 登录类型
     * @return 角色权限列表
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return resolvePermissionList(loginId, LoginUser::getRolePermission, PermissionService::getRolePermission);
    }

    /**
     * 解析当前登录对象的权限列表。
     *
     * @param loginId                   登录ID
     * @param localPermissionExtractor  当前登录用户权限提取器
     * @param remotePermissionExtractor 远程权限提取器
     * @return 权限列表
     */
    private List<String> resolvePermissionList(Object loginId,
                                               java.util.function.Function<LoginUser, Collection<String>> localPermissionExtractor,
                                               BiFunction<PermissionService, Long, Collection<String>> remotePermissionExtractor) {
        LoginUser loginUser = LoginHelper.getLoginUser();
        if (ObjectUtil.isNull(loginUser) || !loginUser.getLoginId().equals(loginId)) {
            PermissionService permissionService = getPermissionService();
            if (ObjectUtil.isNotNull(permissionService)) {
                return new ArrayList<>(remotePermissionExtractor.apply(permissionService, resolveUserId(loginId)));
            }
            throw new ServiceException("PermissionService 实现类不存在");
        }
        Collection<String> permissionList = localPermissionExtractor.apply(loginUser);
        if (CollUtil.isNotEmpty(permissionList)) {
            return new ArrayList<>(permissionList);
        }
        return new ArrayList<>();
    }

    /**
     * 获取权限服务实现。
     *
     * @return 权限服务
     */
    private PermissionService getPermissionService() {
        try {
            return SpringUtils.getBean(PermissionService.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从登录ID中提取用户ID。
     *
     * @param loginId 登录ID
     * @return 用户ID
     */
    private Long resolveUserId(Object loginId) {
        String loginIdStr = loginId.toString();
        int separatorIndex = loginIdStr.indexOf(':');
        if (separatorIndex < 0 || separatorIndex == loginIdStr.length() - 1) {
            throw new ServiceException("登录ID格式错误");
        }
        return Long.parseLong(loginIdStr.substring(separatorIndex + 1));
    }

}
