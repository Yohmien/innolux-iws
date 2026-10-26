/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 发布计划
 * @Version      : V1.0
 * @Description  : APK 发布计划、事件、本地动作和权限的稳定标识；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.process;

import java.util.List;

/**
 * APK 发布计划接入 process 平台所需的稳定标识。
 *
 * <p>这些值同时用于业务调度、计划定义、能力注册、对象范围授权和测试，
 * 不在各处复制字面量。</p>
 */
public final class ApkProcessContract {

    /** 业务调度编码。 */
    public static final String BUSINESS_CODE = "APK_RELEASE";

    /** 计划类型编码。 */
    public static final String PLAN_TYPE = "APK_PUBLISH";

    /** 计划定义编码。 */
    public static final String PLAN_CODE = "APK_RELEASE_PUBLISH";

    /** 即时监督计划定义编码。 */
    public static final String SUPERVISOR_PLAN_CODE = "APK_RELEASE_PUBLISH_SUPERVISOR";

    /** 计划中文名。 */
    public static final String PLAN_NAME = "APK 发布";

    /** 即时监督计划中文名。 */
    public static final String SUPERVISOR_PLAN_NAME = "APK 发布即时监督";

    /** 实例业务类型。 */
    public static final String BUSINESS_TYPE = "APK_RELEASE";

    /** 本系统编码，必须与 endpoint 目录中的启用系统一致。 */
    public static final String SELF_SYSTEM_CODE = "IWS";

    /** 唯一业务事件编码。 */
    public static final String EVENT_CODE = "APK_RELEASE_PUBLISH";

    /** 事件类型键。 */
    public static final String EVENT_TYPE_KEY = "APK_RELEASE_PUBLICATION";

    /** 监督事件类型键。 */
    public static final String SUPERVISOR_EVENT_TYPE_KEY = "APK_RELEASE_SUPERVISOR";

    /** 事件类型、本地动作和校验器的稳定版本。 */
    public static final String VERSION = "1";

    /** 唯一本地动作键。 */
    public static final String LOCAL_ACTION_KEY = "APK_RELEASE_PUBLISH";

    /** 本地效果键。 */
    public static final String LOCAL_EFFECT_KEY = "APK_RELEASE_PUBLICATION";

    /** 状态校验器键。 */
    public static final String STATE_VALIDATOR_KEY = "APK_RELEASE_PUBLICATION";

    /** 监督状态校验器键。 */
    public static final String SUPERVISOR_STATE_VALIDATOR_KEY = "APK_RELEASE_SUPERVISOR";

    /** 起点状态。 */
    public static final String STATE_START = "APK_START";

    /** 发布完成状态。 */
    public static final String STATE_PUBLISHED = "APK_PUBLISHED";

    /** 监督起点状态。 */
    public static final String SUPERVISOR_STATE_START = "APK_SUPERVISOR_START";

    /** 监督终点状态。 */
    public static final String SUPERVISOR_STATE_END = "APK_SUPERVISOR_END";

    /** 监督决策事件。 */
    public static final String SUPERVISOR_EVENT_CODE = "APK_IMMEDIATE_DECISION";

    /** 监督决策本地效果键。 */
    public static final String SUPERVISOR_LOCAL_EFFECT_KEY = "APK_RELEASE_SUPERVISOR_DECISION";

    /** 计划定义管理员操作权限。 */
    public static final String PLAN_ADMIN_PERMISSION = "system:apk:publish";

    /** APK 业务对象可见性权限，满足任一即可进入精确 APK 范围。 */
    public static final List<String> ACCESS_PERMISSIONS = List.of(
        "system:apk:list", "system:apk:upload", "system:apk:publish");

    /** 即时执行定义期限。 */
    public static final long MAX_EXECUTION_MS = 30_000L;

    /** 本地动作租约。 */
    public static final long LEASE_MS = 30_000L;

    /** 监督单轮预算。 */
    public static final long SUPERVISOR_ROUND_MAX_DURATION_MS = 30_000L;

    /** 监督单轮候选上限。 */
    public static final int SUPERVISOR_ROUND_BATCH_LIMIT = 10;

    /** 监督 worker 单轮上限。 */
    public static final int SUPERVISOR_WORKER_BATCH_LIMIT = 10;

    /** 监督 worker 单轮时长。 */
    public static final long SUPERVISOR_WORKER_MAX_DURATION_MS = 30_000L;

    /** 平台命令键最大长度。 */
    public static final int COMMAND_KEY_MAX_LENGTH = 128;

    /** 输入摘要最大长度。 */
    public static final int INPUT_HASH_MAX_LENGTH = 96;

    /** 证据引用前缀。 */
    public static final String EVIDENCE_PREFIX = "apk-release:";

    private ApkProcessContract() {
    }
}
