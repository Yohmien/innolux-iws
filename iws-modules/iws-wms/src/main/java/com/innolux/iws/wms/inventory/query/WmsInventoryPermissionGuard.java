/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P7 只读查询
 * @Version      : V1.0
 * @Description  : 在库管理只读权限复核接缝；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.inventory.query;

/**
 * 在库管理权限复核接缝。
 *
 * <p>Controller 注解之外由服务层再次校验，避免内部直接调用绕过入口权限。</p>
 */
public interface WmsInventoryPermissionGuard {

    boolean hasPermission(String permission);

    void requirePermission(String permission);
}
