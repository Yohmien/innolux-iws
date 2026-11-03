/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P7 只读查询
 * @Version      : V1.0
 * @Description  : 基于 Sa-Token 的在库管理权限实现；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.inventory.query;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Component;

/**
 * Sa-Token 权限复核实现。
 */
@Component
public class StpWmsInventoryPermissionGuard implements WmsInventoryPermissionGuard {

    @Override
    public boolean hasPermission(String permission) {
        return StpUtil.isLogin() && StpUtil.hasPermission(permission);
    }

    @Override
    public void requirePermission(String permission) {
        StpUtil.checkPermission(permission);
    }
}
