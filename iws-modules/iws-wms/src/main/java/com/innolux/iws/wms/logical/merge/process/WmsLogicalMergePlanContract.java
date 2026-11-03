/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 定义合并父计划、固定批次计划和监督计划合同；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.logical.merge.process;

/** 逻辑合并计划合同。 */
public final class WmsLogicalMergePlanContract {

    public static final String BUSINESS_TYPE = "WMS_LOGICAL_MERGE";

    public static final String SCHEDULE_CODE = "LOGICAL_MERGE";

    public static final String BATCH_PLAN_TYPE = "LOGICAL_MERGE";

    public static final String CLOSE_PLAN_TYPE = "LOGICAL_MERGE_CLOSE";

    public static final String BATCH_PLAN_CODE = "WMS_LOGICAL_MERGE_BATCH";

    public static final String BATCH_SUPERVISOR_PLAN_CODE =
        "WMS_LOGICAL_MERGE_BATCH_SUPERVISOR";

    public static final String CLOSE_PLAN_CODE = "WMS_LOGICAL_MERGE_CLOSE";

    public static final String CLOSE_SUPERVISOR_PLAN_CODE =
        "WMS_LOGICAL_MERGE_CLOSE_SUPERVISOR";

    public static final String BATCH_STATE_VALIDATOR_KEY =
        "WMS_LOGICAL_MERGE_BATCH_STATE";

    public static final String CLOSE_STATE_VALIDATOR_KEY =
        "WMS_LOGICAL_MERGE_CLOSE_STATE";

    public static final String VALIDATOR_VERSION = "1";

    public static final String CAPTURE_BATCH = "CAPTURE_MERGE_BATCH";

    public static final String CHECK_BATCH = "CHECK_MERGE_COMPATIBILITY";

    public static final String APPLY_BATCH = "APPLY_LOGICAL_MERGE";

    public static final String CLOSE_BATCH = "CLOSE_MERGE_BATCH";

    public static final String CAPTURE_PARENT = "CAPTURE_MERGE_SCOPE";

    public static final String CLOSE_PARENT = "CLOSE_MERGE";

    public static final String CAPTURE_BATCH_EFFECT = "WMS:CAPTURE_MERGE_BATCH";

    public static final String CHECK_BATCH_EFFECT = "WMS:CHECK_MERGE_COMPATIBILITY";

    public static final String APPLY_BATCH_EFFECT = "WMS:APPLY_LOGICAL_MERGE";

    public static final String CLOSE_BATCH_EFFECT = "WMS:CLOSE_MERGE_BATCH";

    public static final String CAPTURE_PARENT_EFFECT = "WMS:CAPTURE_MERGE_SCOPE";

    public static final String CLOSE_PARENT_EFFECT = "WMS:CLOSE_MERGE";

    public static final String BARRIER_KEY = "LOGICAL_MERGE_BATCHES";

    private WmsLogicalMergePlanContract() {
    }
}
