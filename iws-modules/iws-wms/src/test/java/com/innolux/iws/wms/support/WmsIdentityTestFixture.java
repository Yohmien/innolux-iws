/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 权威身份隔离测试
 * @Version      : V1.0
 * @Description  : 在清理原身份表后恢复真实超级管理员角色关系，供既有权限回归；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.support;

import com.innolux.iws.common.core.constant.SystemConstants;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * WMS 测试身份数据夹具。
 */
public final class WmsIdentityTestFixture {

    private WmsIdentityTestFixture() {
    }

    /**
     * 恢复原超级管理员账号到原超级管理员角色的关联。
     *
     * @param jdbc 隔离身份库
     */
    public static void restoreSuperAdmin(JdbcTemplate jdbc) {
        Long roleId = SystemConstants.SUPER_ADMIN_USER_ID;
        jdbc.update("""
            insert into sys_role (role_id, role_name, role_key, status, del_flag)
            values (?, 'superadmin', 'superadmin', '0', '0')
            on duplicate key update
                role_key = 'superadmin',
                status = '0',
                del_flag = '0'
            """, roleId);
        jdbc.update("""
            insert ignore into sys_user_role (user_id, role_id)
            values (?, ?)
            """, roleId, roleId);
    }
}
