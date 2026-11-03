/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P7 只读查询
 * @Version      : V1.0
 * @Description  : 从登录上下文解析库存对象可见范围，不接受前端公司参数；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.inventory.query;

import com.innolux.iws.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Component;

/**
 * 从受信登录上下文解析库存只读范围。
 */
@Component
public class WmsInventoryReadScopeResolver {

    /**
     * 解析当前请求范围。
     *
     * <p>超级管理员保留现有数据权限旁路；普通用户必须同时通过库存对象
     * visibility 与既有多角色 DataPermission。</p>
     *
     * @return 当前可信范围
     */
    public WmsInventoryReadScope current() {
        boolean superAdmin = LoginHelper.isSuperAdmin();
        return new WmsInventoryReadScope(
            superAdmin,
            !superAdmin,
            LoginHelper.getUserId(),
            LoginHelper.getDeptId()
        );
    }
}
