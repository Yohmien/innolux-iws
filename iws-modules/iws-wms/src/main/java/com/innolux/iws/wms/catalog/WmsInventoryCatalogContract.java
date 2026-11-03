/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存业务目录
 * @Version      : V1.0
 * @Description  : 库存业务类型与计划挂载的只读合同常量；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.catalog;

/**
 * 库存业务目录只读合同。
 *
 * @author YONGJIE.PENG
 */
public final class WmsInventoryCatalogContract {

    public static final String CONTRACT_VERSION = "1";

    public static final String PERMISSION_QUERY = "inventory:business:query";

    public static final String STATUS_ACTIVE = "ACTIVE";

    public static final String STATUS_DISABLED = "DISABLED";

    public static final String ERROR_PARENT_TYPE_NOT_REGISTERED = "PARENT_TYPE_NOT_REGISTERED";

    public static final String ERROR_MOUNT_NOT_ALLOWED = "MOUNT_NOT_ALLOWED";

    private WmsInventoryCatalogContract() {
    }
}
