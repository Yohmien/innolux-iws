/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则发布计划
 * @Version      : V1.0
 * @Description  : 定义规则包校验与激活的持久计划合同；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.process;

/** 规则发布计划合同。 */
public final class WmsRulePlanContract {

    public static final String PLAN_CODE = "WMS_RULESET_PUBLISH";

    public static final String SUPERVISOR_PLAN_CODE = "WMS_RULESET_PUBLISH_SUPERVISOR";

    public static final String PLAN_NAME = "规则包发布";

    public static final String SUPERVISOR_PLAN_NAME = "规则包发布监督";

    public static final String PLAN_TYPE = "RULESET_PUBLISH";

    public static final String BUSINESS_TYPE = "WMS_RULESET_PUBLISH";

    public static final String BUSINESS_CODE = "INVENTORY_CONFIG";

    public static final String STATE_VALIDATOR_KEY = "WMS_RULE_PUBLISH_STATE";

    public static final String STATE_VALIDATOR_VERSION = "1";

    public static final String SUPERVISOR_STATE_VALIDATOR_KEY =
        "WMS_ALLOCATION_SUPERVISOR_STATE";

    public static final String SUPERVISOR_STATE_VALIDATOR_VERSION = "1";

    public static final String STATE_START = "START";

    public static final String STATE_VALIDATED = "VALIDATED";

    public static final String STATE_PUBLISHED = "PUBLISHED";

    public static final String EVENT_VALIDATE = "VALIDATE_RULESET";

    public static final String EVENT_ACTIVATE = "ACTIVATE_RULESET";

    public static final String VALIDATE_ACTION_KEY = "VALIDATE_RULESET";

    public static final String VALIDATE_LOCAL_EFFECT_KEY = "WMS:VALIDATE_RULESET";

    public static final String ACTIVATE_ACTION_KEY = "ACTIVATE_RULESET";

    public static final String ACTIVATE_LOCAL_EFFECT_KEY = "WMS:ACTIVATE_RULESET";

    private WmsRulePlanContract() {
    }
}
