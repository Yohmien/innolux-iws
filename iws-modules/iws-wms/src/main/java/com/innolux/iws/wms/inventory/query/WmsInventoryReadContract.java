/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P7 只读查询
 * @Version      : V1.0
 * @Description  : 规则、物料、业务任务、追踪与导入预览的权限及边界常量；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.inventory.query;

import java.util.Set;

/**
 * 在库管理 P7 只读查询合同。
 *
 * <p>写能力并未因此开放。物料正式写入仍由持久父业务和 MATERIAL_CHANGE 计划承接。</p>
 */
public final class WmsInventoryReadContract {

    public static final String PERMISSION_MATERIAL_QUERY = "inventory:material:query";

    public static final String PERMISSION_TRACE_QUERY = "inventory:trace:query";

    public static final String PERMISSION_TRACE_EXPORT = "inventory:trace:export";

    public static final String PERMISSION_BUSINESS_QUERY = "inventory:business:query";

    public static final String PERMISSION_TASK_QUERY = "inventory:task:query";

    public static final String PERMISSION_ALLOCATION_INVESTIGATE = "inventory:allocation:investigate";

    public static final String PERMISSION_RULE_QUERY = "inventory:rule:query";

    public static final String PERMISSION_RULE_DRAFT = "inventory:rule:draft";

    public static final String ERROR_INVALID_QUERY = "INVENTORY_QUERY_INVALID";

    public static final String ERROR_IMPORT_INVALID = "INVENTORY_IMPORT_INVALID";

    public static final int DEFAULT_PAGE_SIZE = 20;

    public static final int MAX_PAGE_SIZE = 200;

    public static final int TRACE_EXPORT_MAX_ROWS = 5000;

    public static final Set<String> ACTION_KEYS = Set.of(
        "materialQuery",
        "traceQuery",
        "traceExport",
        "businessQuery",
        "taskQuery",
        "allocationInvestigate",
        "ruleQuery",
        "ruleDraft",
        "rulePublish",
        "materialWrite",
        "moveWrite",
        "pdaLocate"
    );

    private WmsInventoryReadContract() {
    }
}
