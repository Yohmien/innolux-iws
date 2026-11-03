/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 为对象授权测试建立真实 Sa-Token 线程会话与权限快照；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 测试线程上的 Sa-Token 登录与权限上下文。
 */
final class ProcessTestAuth {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private static final long LOGIN_ID = 1L;

    private ProcessTestAuth() {
    }

    static void install() {
        if (INSTALLED.compareAndSet(false, true)) {
            SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
            SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
            StpLogic logic = new StpLogic(StpUtil.TYPE);
            SaManager.putStpLogic(logic);
            StpUtil.setStpLogic(logic);
        }
    }

    static void login(String... permissions) {
        install();
        SaTokenContextMockUtil.setMockContext();
        Set<String> values = Set.of(permissions);
        SaManager.setStpInterface(new StpInterface() {
            @Override
            public List<String> getPermissionList(Object loginId, String loginType) {
                return List.copyOf(values);
            }

            @Override
            public List<String> getRoleList(Object loginId, String loginType) {
                return List.of();
            }
        });
        StpUtil.login(LOGIN_ID);
    }

    static void anonymous() {
        install();
        SaTokenContextMockUtil.setMockContext();
    }

    static void clear() {
        SaTokenContextMockUtil.clearContext();
    }
}
