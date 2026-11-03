/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P7 只读查询
 * @Version      : V1.0
 * @Description  : 只读查询使用的可信当前用户与库存 visibility 边界；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.inventory.query;

/**
 * 服务端解析后的库存只读范围。
 *
 * @param superAdmin        是否超级管理员
 * @param enforceVisibility 是否强制对象 visibility
 * @param userId            当前用户ID
 * @param deptId            当前用户部门ID
 */
public record WmsInventoryReadScope(
    boolean superAdmin,
    boolean enforceVisibility,
    Long userId,
    Long deptId
) {
}
