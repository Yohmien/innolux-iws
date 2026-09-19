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

package com.innolux.iws.common.mybatis.core.domain;

import cn.hutool.core.collection.CollUtil;

import java.io.Serial;
import java.io.Serializable;
import java.util.Set;

/**
 * 当前请求的数据权限访问上下文
 *
 * @param perms    当前请求接口权限标识集合
 * @param roleKeys 当前请求角色标识集合
 * @author YONGJIE.PENG
 */
public record DataPermissionAccess(Set<String> perms, Set<String> roleKeys) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 空访问上下文，表示不按接口权限或角色约束数据权限角色。
     */
    public static final DataPermissionAccess EMPTY = new DataPermissionAccess(Set.of(), Set.of());

    /**
     * 是否存在数据权限约束。
     *
     * @return true 存在权限约束 false 不存在权限约束
     */
    public boolean constrained() {
        return CollUtil.isNotEmpty(perms) || CollUtil.isNotEmpty(roleKeys);
    }
}
