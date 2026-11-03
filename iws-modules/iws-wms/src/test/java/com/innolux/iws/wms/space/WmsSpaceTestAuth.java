/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存空间只读模型集成测试
 * @Version      : V1.0
 * @Description  : 为空间只读测试建立真实 Sa-Token 线程会话；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.space;

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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 测试线程上的 Sa-Token 登录上下文。
 *
 * @author YONGJIE.PENG
 */
final class WmsSpaceTestAuth {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private WmsSpaceTestAuth() {
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
        return login(SystemConstants.SUPER_ADMIN_USER_ID, Set.of("*"));
    }

    static LoginUser login(long userId, Set<String> permissions, Long... roleIds) {
        install();
        SaTokenContextMockUtil.setMockContext();
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setUserType("pc");
        user.setUsername("user-" + userId);
        user.setNickname("user-" + userId);
        user.setDeptId(1000L);
        user.setMenuPermission(new HashSet<>(permissions));
        user.setRolePermission(new HashSet<>());
        List<RoleDTO> roles = new ArrayList<>();
        for (Long roleId : roleIds) {
            RoleDTO role = new RoleDTO();
            role.setRoleId(roleId);
            role.setRoleName("role-" + roleId);
            roles.add(role);
        }
        user.setRoles(roles);
        LoginHelper.login(user, null);
        return user;
    }

    static void clear() {
        SaTokenContextMockUtil.clearContext();
    }
}
