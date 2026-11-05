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

package com.innolux.iws.system.listener;

import cn.hutool.core.collection.CollUtil;
import lombok.RequiredArgsConstructor;
import com.innolux.iws.system.event.OnlineUserCleanEvent;
import com.innolux.iws.system.service.ISysRoleService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 在线用户清理监听器。
 *
 * @author YONGJIE.PENG
 */
@Component
@RequiredArgsConstructor
public class OnlineUserCleanListener {

    private final ISysRoleService roleService;

    /**
     * 权限或用户角色关系变化后清理受影响的在线用户。
     *
     * @param event 在线用户清理事件
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void cleanOnlineUser(OnlineUserCleanEvent event) {
        if (event.roleId() != null) {
            roleService.cleanOnlineUserByRole(event.roleId());
        }
        if (CollUtil.isNotEmpty(event.userIds())) {
            roleService.cleanOnlineUser(event.userIds());
        }
    }

}
