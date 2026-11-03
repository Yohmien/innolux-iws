/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P7 只读查询集成测试
 * @Version      : V1.0
 * @Description  : 为库存只读测试建立真实 Sa-Token 线程会话与数据权限角色；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.inventory.query;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.common.core.constant.SystemConstants;
import com.innolux.iws.common.satoken.core.service.SaPermissionImpl;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.system.api.domain.RoleDTO;
import com.innolux.iws.system.api.model.LoginUser;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 库存只读测试线程上的 Sa-Token 登录上下文。
 */
final class WmsInventoryReadTestAuth {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private WmsInventoryReadTestAuth() {
    }

    static void install() {
        if (INSTALLED.compareAndSet(false, true)) {
            SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
            SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
            SaManager.setStpInterface(new SaPermissionImpl());
            SaManager.getConfig().setJwtSecretKey("abcdefghijklmnopqrstuvwxyz");
            StpLogic jwtLogic = new StpLogicJwtForSimple();
            SaManager.putStpLogic(jwtLogic);
            StpUtil.setStpLogic(jwtLogic);
        }
    }

    static LoginUser loginAsAdmin() {
        return login(
            SystemConstants.SUPER_ADMIN_USER_ID,
            1000L,
            Set.of("*"),
            List.of()
        );
    }

    static LoginUser login(long userId,
                           long deptId,
                           Set<String> permissions,
                           List<RoleDTO> roles) {
        install();
        SaTokenContextMockUtil.setMockContext();
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setUserType("pc");
        user.setUsername("user-" + userId);
        user.setNickname("user-" + userId);
        user.setDeptId(deptId);
        user.setMenuPermission(new java.util.HashSet<>(permissions));
        user.setRolePermission(new java.util.HashSet<>());
        user.setRoles(roles);
        LoginHelper.login(user, null);
        return user;
    }

    static void clear() {
        SaTokenContextMockUtil.clearContext();
    }
}
