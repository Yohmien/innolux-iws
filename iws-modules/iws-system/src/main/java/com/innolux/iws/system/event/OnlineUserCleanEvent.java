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

package com.innolux.iws.system.event;

import cn.hutool.core.collection.CollUtil;

import java.util.Collection;
import java.util.List;

/**
 * 在线用户清理事件。
 *
 * @param roleId  角色 ID，存在时清理拥有该角色的在线用户
 * @param userIds 用户 ID 集合，存在时清理指定在线用户
 */
public record OnlineUserCleanEvent(Long roleId, Collection<Long> userIds) {

    public OnlineUserCleanEvent {
        if (CollUtil.isNotEmpty(userIds)) {
            userIds = List.copyOf(userIds);
        }
    }

    /**
     * 创建按角色清理在线用户事件。
     *
     * @param roleId 角色 ID
     * @return 在线用户清理事件
     */
    public static OnlineUserCleanEvent byRole(Long roleId) {
        return new OnlineUserCleanEvent(roleId, null);
    }

    /**
     * 创建按用户清理在线用户事件。
     *
     * @param userIds 用户 ID 集合
     * @return 在线用户清理事件
     */
    public static OnlineUserCleanEvent byUsers(Collection<Long> userIds) {
        return new OnlineUserCleanEvent(null, userIds);
    }

}
