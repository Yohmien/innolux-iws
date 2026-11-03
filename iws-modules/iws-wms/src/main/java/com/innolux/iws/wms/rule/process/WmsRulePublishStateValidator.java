/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则发布计划
 * @Version      : V1.0
 * @Description  : 校验规则发布状态迁移与已持久动作 checkpoint；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.process;

import com.innolux.iws.process.runtime.StateContext;
import com.innolux.iws.process.runtime.StateValidator;
import com.innolux.iws.process.runtime.StateValidatorDescriptor;
import com.innolux.iws.process.runtime.ValidationResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Objects;

/** 规则发布计划状态校验器。 */
public class WmsRulePublishStateValidator implements StateValidator {

    private static final Map<String, String> TRANSITIONS = Map.of(
        WmsRulePlanContract.STATE_START, WmsRulePlanContract.STATE_VALIDATED,
        WmsRulePlanContract.STATE_VALIDATED, WmsRulePlanContract.STATE_PUBLISHED
    );

    private static final String VALIDATED_EVIDENCE_SQL = """
        SELECT COUNT(*)
        FROM iws_process_attempt a
        JOIN iws_process_event event ON event.id = a.event_id
        JOIN iws_process_instance i ON i.id = event.instance_id
        JOIN wms_rule_package_version p
          ON p.package_version_id = CAST(i.business_id AS UNSIGNED)
        WHERE a.event_id = ?
          AND a.effect_result = 'SUCCEEDED'
          AND a.checkpoint_json IS NOT NULL
          AND JSON_UNQUOTE(JSON_EXTRACT(a.checkpoint_json, '$.status')) = ?
          AND CAST(JSON_UNQUOTE(JSON_EXTRACT(
                a.checkpoint_json, '$.packageVersionId')) AS UNSIGNED) =
              p.package_version_id
          AND JSON_UNQUOTE(JSON_EXTRACT(
                a.checkpoint_json, '$.canonicalDigest')) = p.canonical_digest
          AND i.business_type = ?
          AND p.status = 'DRAFT'
        """;

    private static final String PUBLISHED_EVIDENCE_SQL = """
        SELECT COUNT(*)
        FROM iws_process_attempt a
        JOIN iws_process_event event ON event.id = a.event_id
        JOIN iws_process_instance i ON i.id = event.instance_id
        JOIN wms_rule_package_version p
          ON p.package_version_id = CAST(i.business_id AS UNSIGNED)
        JOIN wms_rule_effective_pointer pointer
          ON pointer.scope_type = JSON_UNQUOTE(JSON_EXTRACT(
                a.checkpoint_json, '$.scopeType'))
         AND pointer.scope_id = CAST(JSON_UNQUOTE(JSON_EXTRACT(
                a.checkpoint_json, '$.scopeId')) AS UNSIGNED)
        JOIN wms_logical_scope_version scope_version
          ON scope_version.scope_version_id = CAST(JSON_UNQUOTE(JSON_EXTRACT(
                a.checkpoint_json, '$.scopeVersionId')) AS UNSIGNED)
        JOIN wms_logical_scope scope
          ON scope.logical_scope_id = scope_version.logical_scope_id
        WHERE a.event_id = ?
          AND a.effect_result = 'SUCCEEDED'
          AND a.checkpoint_json IS NOT NULL
          AND JSON_UNQUOTE(JSON_EXTRACT(a.checkpoint_json, '$.status')) = ?
          AND CAST(JSON_UNQUOTE(JSON_EXTRACT(
                a.checkpoint_json, '$.packageVersionId')) AS UNSIGNED) =
              p.package_version_id
          AND pointer.package_version_id = p.package_version_id
          AND pointer.revision = CAST(JSON_UNQUOTE(JSON_EXTRACT(
                a.checkpoint_json, '$.pointerRevision')) AS UNSIGNED)
          AND JSON_UNQUOTE(JSON_EXTRACT(
                a.checkpoint_json, '$.canonicalDigest')) = p.canonical_digest
          AND scope_version.rule_package_version_id = p.package_version_id
          AND scope.scope_code = CONCAT(
                pointer.scope_type, ':', pointer.scope_id)
          AND scope_version.version_no = CAST(JSON_UNQUOTE(JSON_EXTRACT(
                a.checkpoint_json, '$.scopeVersionNo')) AS UNSIGNED)
          AND i.business_type = ?
          AND p.status = 'ACTIVE'
        """;

    private final JdbcTemplate jdbcTemplate;

    public WmsRulePublishStateValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public StateValidatorDescriptor describe() {
        return new StateValidatorDescriptor(
            WmsRulePlanContract.STATE_VALIDATOR_KEY,
            WmsRulePlanContract.STATE_VALIDATOR_VERSION,
            "规则发布计划状态"
        );
    }

    @Override
    public ValidationResult validateStructural(StateContext context) {
        if (context == null || context.instanceId() <= 0 || context.eventId() <= 0) {
            return ValidationResult.fail(
                "RULE_PUBLISH_STATE_CONTEXT",
                "规则发布计划状态上下文不完整"
            );
        }
        if (!Objects.equals(
            TRANSITIONS.get(context.sourceStateCode()),
            context.targetStateCode()
        )) {
            return ValidationResult.fail(
                "RULE_PUBLISH_STATE_TRANSITION",
                "规则发布计划不允许状态迁移: "
                    + context.sourceStateCode() + " -> " + context.targetStateCode()
            );
        }
        if (context.inputHash() == null || context.inputHash().isBlank()) {
            return ValidationResult.fail(
                "RULE_PUBLISH_STATE_INPUT",
                "规则发布计划状态缺少输入摘要"
            );
        }
        return ValidationResult.pass();
    }

    @Override
    public ValidationResult validateBusiness(StateContext context) {
        ValidationResult structural = validateStructural(context);
        if (!structural.passed()) {
            return structural;
        }
        boolean validated = WmsRulePlanContract.STATE_VALIDATED.equals(
            context.targetStateCode()
        );
        Integer count = jdbcTemplate.queryForObject(
            validated ? VALIDATED_EVIDENCE_SQL : PUBLISHED_EVIDENCE_SQL,
            Integer.class,
            context.eventId(),
            context.targetStateCode(),
            WmsRulePlanContract.BUSINESS_TYPE
        );
        if (count == null || count != 1) {
            return ValidationResult.fail(
                "RULE_PUBLISH_STATE_EVIDENCE",
                "规则发布状态缺少匹配的包、指针、逻辑归属或 checkpoint 证据"
            );
        }
        return ValidationResult.pass();
    }
}
