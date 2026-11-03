/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P2 空间生命周期
 * @Version      : V1.0
 * @Description  : 定义空间配置与退役计划的事件和状态合同；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.space.change.process;

/** 空间生命周期计划合同。 */
public final class WmsSpacePlanContract {

    public static final String CHANGE_PLAN_CODE = "WMS_SPACE_CHANGE";

    public static final String CHANGE_SUPERVISOR_PLAN_CODE =
        "WMS_SPACE_CHANGE_SUPERVISOR";

    public static final String RETIRE_PLAN_CODE = "WMS_SPACE_RETIRE";

    public static final String RETIRE_SUPERVISOR_PLAN_CODE =
        "WMS_SPACE_RETIRE_SUPERVISOR";

    public static final String CHANGE_PLAN_NAME = "空间配置变更";

    public static final String RETIRE_PLAN_NAME = "空间退役";

    public static final String STATE_VALIDATOR_KEY = "WMS_SPACE_CHANGE_STATE";

    public static final String STATE_VALIDATOR_VERSION = "1";

    public static final String VALIDATE_EVENT = "VALIDATE_CHANGE";

    public static final String APPLY_EVENT = "APPLY_CONFIGURATION";

    public static final String VERIFY_EVENT = "VERIFY_CHANGE";

    public static final String CHECK_RETIRE_EVENT = "CHECK_RETIREMENT";

    public static final String RETIRE_EVENT = "RETIRE_LOCATION";

    public static final String VALIDATE_EFFECT = "WMS:VALIDATE_CHANGE";

    public static final String APPLY_EFFECT = "WMS:APPLY_CONFIGURATION";

    public static final String VERIFY_EFFECT = "WMS:VERIFY_CHANGE";

    public static final String CHECK_RETIRE_EFFECT = "WMS:CHECK_RETIREMENT";

    public static final String RETIRE_EFFECT = "WMS:RETIRE_LOCATION";

    private WmsSpacePlanContract() {
    }
}
