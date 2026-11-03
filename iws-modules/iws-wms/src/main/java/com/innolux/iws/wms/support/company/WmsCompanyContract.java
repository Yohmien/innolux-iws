/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公司最小闭环
 * @Version      : V1.0
 * @Description  : 公司最小闭环的表码、权限、动作和稳定状态常量；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.support.company;

/**
 * 公司最小闭环合同常量。
 *
 * @author YONGJIE.PENG
 */
public final class WmsCompanyContract {

    public static final String TABLE_CODE = "wms_company";

    public static final String PERMISSION_CREATE = "inventory:company:create";

    public static final String PERMISSION_QUERY = "inventory:company:query";

    public static final String PERMISSION_UPDATE = "inventory:company:update";

    public static final String PERMISSION_ENABLE = "inventory:company:enable";

    public static final String PERMISSION_DISABLE = "inventory:company:disable";

    public static final String PERMISSION_SCOPE_ALL = "inventory:company:scope:all";

    public static final String PERMISSION_SCOPE_MANAGE = "inventory:company:scope:manage";

    public static final String LIFECYCLE_ACTIVE = "ACTIVE";

    public static final String ACTION_CREATE = "CREATE";

    public static final String ACTION_UPDATE = "UPDATE";

    public static final String ACTION_ENABLE = "ENABLE";

    public static final String ACTION_DISABLE = "DISABLE";

    public static final String ACTION_REPLACE_MANAGEMENT_SCOPE = "MANAGEMENT_SCOPE_REPLACE";

    public static final String OBJECT_TYPE = "WMS_COMPANY";

    public static final String RESULT_SUCCESS = "SUCCESS";

    public static final String ATTEMPT_APPLIED = "APPLIED";

    public static final String ATTEMPT_FAILED = "FAILED";

    public static final String ATTEMPT_REJECTED = "REJECTED";

    public static final String INITIATOR_USER = "USER";

    private WmsCompanyContract() {
    }
}
