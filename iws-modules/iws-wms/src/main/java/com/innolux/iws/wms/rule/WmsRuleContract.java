/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则属性目录、规则草稿、规则包发布与生效读取的合同常量；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule;

import java.util.Set;

/**
 * 在库管理规则服务合同常量。
 *
 * <p>本类集中权限码、配置状态、命令动作、审计对象类型和稳定错误码。权限码与后续菜单/授权种子必须
 * 保持一致；本切片不写菜单和权限种子 SQL，也不声明已授权。</p>
 *
 * @author YONGJIE.PENG
 */
public final class WmsRuleContract {

    /**
     * 配置发布状态：草稿。
     */
    public static final String STATUS_DRAFT = "DRAFT";

    /**
     * 配置发布状态：已发布且可被引用。
     */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /**
     * 配置发布状态：已退役，只读保留。
     */
    public static final String STATUS_RETIRED = "RETIRED";

    /**
     * 规则定义状态：启用。
     */
    public static final String DEFINITION_STATUS_ACTIVE = "ACTIVE";

    /**
     * 规则定义状态：停用，停止新引用。
     */
    public static final String DEFINITION_STATUS_DISABLED = "DISABLED";

    /**
     * 逻辑归属生命周期：活跃。
     */
    public static final String LIFECYCLE_ACTIVE = "ACTIVE";

    public static final String PERMISSION_QUERY = "inventory:rule:query";

    public static final String PERMISSION_DRAFT = "inventory:rule:draft";

    public static final String PERMISSION_PUBLISH = "inventory:rule:publish";

    public static final String SCOPE_COMPANY = "COMPANY";

    public static final String SCOPE_WAREHOUSE = "WAREHOUSE";

    public static final String SCOPE_MATERIAL_ZONE = "MATERIAL_ZONE";

    public static final String SCOPE_DEVICE_ZONE = "DEVICE_ZONE";

    public static final String SCOPE_STORAGE = "STORAGE";

    private static final Set<String> SUPPORTED_SCOPE_TYPES = Set.of(
        SCOPE_COMPANY,
        SCOPE_WAREHOUSE,
        SCOPE_MATERIAL_ZONE,
        SCOPE_DEVICE_ZONE,
        SCOPE_STORAGE
    );

    public static final String ACTION_DEFINITION_CREATE = "RULE_DEFINITION_CREATE";

    public static final String ACTION_DRAFT_EDIT = "RULE_DRAFT_EDIT";

    public static final String ACTION_DRAFT_COPY = "RULE_DRAFT_COPY";

    public static final String ACTION_VERSION_ACTIVATE = "RULE_VERSION_ACTIVATE";

    public static final String ACTION_DEFINITION_RETIRE = "RULE_DEFINITION_RETIRE";

    public static final String ACTION_PACKAGE_CREATE = "RULE_PACKAGE_CREATE";

    public static final String ACTION_PACKAGE_PUBLISH = "RULE_PACKAGE_PUBLISH";

    public static final String OBJECT_TYPE_DEFINITION = "WMS_RULE_DEFINITION";

    public static final String OBJECT_TYPE_VERSION = "WMS_RULE_VERSION";

    public static final String OBJECT_TYPE_PACKAGE = "WMS_RULE_PACKAGE";

    public static final String FACT_TYPE_DEFINITION = "WMS_RULE_DEFINITION";

    public static final String FACT_TYPE_VERSION = "WMS_RULE_VERSION";

    public static final String FACT_TYPE_PACKAGE = "WMS_RULE_PACKAGE";

    public static final String FACT_TYPE_EFFECTIVE = "WMS_RULE_EFFECTIVE";

    public static final String RESULT_SUCCESS = "SUCCESS";

    public static final String ATTEMPT_APPLIED = "APPLIED";

    public static final String ATTEMPT_FAILED = "FAILED";

    public static final String ATTEMPT_REJECTED = "REJECTED";

    public static final String INITIATOR_USER = "USER";

    public static final String ERROR_INVALID_COMMAND = "RULE_COMMAND_INVALID";

    public static final String ERROR_RULE_KEY_EXISTS = "RULE_KEY_EXISTS";

    public static final String ERROR_DEFINITION_NOT_FOUND = "RULE_DEFINITION_NOT_FOUND";

    public static final String ERROR_DEFINITION_NOT_ACTIVE = "RULE_DEFINITION_NOT_ACTIVE";

    public static final String ERROR_DEFINITION_RETIRED = "RULE_DEFINITION_RETIRED";

    public static final String ERROR_VERSION_NOT_FOUND = "RULE_VERSION_NOT_FOUND";

    public static final String ERROR_VERSION_IMMUTABLE = "RULE_VERSION_IMMUTABLE";

    public static final String ERROR_VERSION_RETIRED = "RULE_VERSION_RETIRED";

    public static final String ERROR_VERSION_NOT_ACTIVE = "RULE_VERSION_NOT_ACTIVE";

    public static final String ERROR_CONTENT_CHANGED = "RULE_CONTENT_CHANGED";

    public static final String ERROR_DIGEST_MISMATCH = "RULE_DIGEST_MISMATCH";

    public static final String ERROR_VERSION_NUMBER_CONFLICT = "RULE_VERSION_NUMBER_CONFLICT";

    public static final String ERROR_PACKAGE_NOT_FOUND = "RULE_PACKAGE_NOT_FOUND";

    public static final String ERROR_PACKAGE_NOT_DRAFT = "RULE_PACKAGE_NOT_DRAFT";

    public static final String ERROR_PACKAGE_MEMBER_REQUIRED = "RULE_PACKAGE_MEMBER_REQUIRED";

    public static final String ERROR_PACKAGE_PURPOSE_DUPLICATE = "RULE_PACKAGE_PURPOSE_DUPLICATE";

    public static final String ERROR_PACKAGE_PURPOSE_MISMATCH = "RULE_PACKAGE_PURPOSE_MISMATCH";

    public static final String ERROR_PACKAGE_DIGEST_MISMATCH = "RULE_PACKAGE_DIGEST_MISMATCH";

    public static final String ERROR_PACKAGE_VERSION_CONFLICT = "RULE_PACKAGE_VERSION_CONFLICT";

    public static final String ERROR_EFFECTIVE_NOT_FOUND = "RULE_EFFECTIVE_NOT_FOUND";

    public static final String ERROR_EFFECTIVE_INCONSISTENT = "RULE_EFFECTIVE_INCONSISTENT";

    public static final String ERROR_POINTER_CONFLICT = "RULE_POINTER_CONFLICT";

    public static final String ERROR_VERSION_CONFLICT = "VERSION_CONFLICT";

    public static final String ERROR_SCOPE_TYPE_UNSUPPORTED = "RULE_SCOPE_TYPE_UNSUPPORTED";

    public static final String ERROR_SCOPE_SCHEMA_UNSUPPORTED = "RULE_SCOPE_SCHEMA_UNSUPPORTED";

    public static final String ERROR_SCOPE_TARGET_NOT_FOUND = "RULE_SCOPE_TARGET_NOT_FOUND";

    public static final String ERROR_SCOPE_TARGET_RETIRED = "RULE_SCOPE_TARGET_RETIRED";

    public static final String ERROR_SCOPE_PARENT_CHAIN_INVALID =
        "RULE_SCOPE_PARENT_CHAIN_INVALID";

    public static final String ERROR_SCOPE_COMPANY_DENIED = "RULE_SCOPE_COMPANY_DENIED";

    public static final String ERROR_SCOPE_STORAGE_NOT_FOUND = "RULE_SCOPE_STORAGE_NOT_FOUND";

    public static final String ERROR_SCOPE_STORAGE_RETIRED = "RULE_SCOPE_STORAGE_RETIRED";

    public static final String ERROR_SCOPE_STORAGE_OUT_OF_SCOPE =
        "RULE_SCOPE_STORAGE_OUT_OF_SCOPE";

    private WmsRuleContract() {
    }

    /**
     * 判断作用域是否属于当前冻结的五类物理范围。
     *
     * @param scopeType 作用域类型
     * @return true=可解析的精确类型
     */
    public static boolean supportsScopeType(String scopeType) {
        return scopeType != null && SUPPORTED_SCOPE_TYPES.contains(scopeType);
    }

    /**
     * 生成规则作用域对应的逻辑归属稳定编码。
     *
     * <p>同一规则作用域重复发布复用同一逻辑归属，并通过新增 {@code wms_logical_scope_version}
     * 递增版本；编码使用 {@code scopeType:scopeId} 保证跨发布可解析且满足
     * {@code uk_wms_logical_scope_code} 唯一约束。</p>
     *
     * @param scopeType 作用域类型
     * @param scopeId   作用域内部 ID
     * @return 逻辑归属编码
     */
    public static String scopeCode(String scopeType, Long scopeId) {
        return scopeType + ":" + scopeId;
    }
}
