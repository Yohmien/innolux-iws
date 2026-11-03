/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P2 空间生命周期
 * @Version      : V1.0
 * @Description  : 校验空间计划状态迁移及持久单、checkpoint和版本证据；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.space.change.process;

import com.innolux.iws.process.runtime.StateContext;
import com.innolux.iws.process.runtime.StateValidator;
import com.innolux.iws.process.runtime.StateValidatorDescriptor;
import com.innolux.iws.process.runtime.ValidationResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;
import java.util.Set;

/** 空间生命周期状态校验器。 */
public class WmsSpaceChangeStateValidator implements StateValidator {

    private static final Set<String> CHANGE_TRANSITIONS = Set.of(
        "START->VALIDATED",
        "VALIDATED->APPLIED",
        "APPLIED->END"
    );

    private static final Set<String> RETIRE_TRANSITIONS = Set.of(
        "START->CHECKED",
        "CHECKED->END"
    );

    private final JdbcTemplate jdbcTemplate;

    public WmsSpaceChangeStateValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public StateValidatorDescriptor describe() {
        return new StateValidatorDescriptor(
            WmsSpacePlanContract.STATE_VALIDATOR_KEY,
            WmsSpacePlanContract.STATE_VALIDATOR_VERSION,
            "空间生命周期状态"
        );
    }

    @Override
    public ValidationResult validateStructural(StateContext context) {
        if (context == null || context.instanceId() <= 0 || context.eventId() <= 0) {
            return ValidationResult.fail(
                "SPACE_STATE_CONTEXT",
                "空间计划状态上下文不完整"
            );
        }
        String planType = jdbcTemplate.query(
            "select plan_type from iws_process_instance where id = ?",
            (rs, rowNum) -> rs.getString("plan_type"),
            context.instanceId()
        ).stream().findFirst().orElse(null);
        Set<String> allowed = planType == null ? Set.of() : switch (planType) {
            case "SPACE_CHANGE" -> CHANGE_TRANSITIONS;
            case "SPACE_RETIRE" -> RETIRE_TRANSITIONS;
            default -> Set.of();
        };
        String transition = context.sourceStateCode() + "->" + context.targetStateCode();
        if (!allowed.contains(transition)) {
            return ValidationResult.fail(
                "SPACE_STATE_TRANSITION",
                "空间计划不允许状态迁移: " + context.sourceStateCode()
                    + " -> " + context.targetStateCode() + "（" + planType + "）"
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
        String checkpointStatus = switch (context.targetStateCode()) {
            case "VALIDATED" -> "VALIDATED";
            case "APPLIED" -> "APPLIED";
            case "END" -> Objects.equals(context.sourceStateCode(), "CHECKED")
                ? "RETIRED" : "COMPLETED";
            case "CHECKED" -> "CHECKED";
            default -> null;
        };
        if (checkpointStatus == null) {
            return ValidationResult.fail(
                "SPACE_STATE_TARGET",
                "空间计划目标状态未定义"
            );
        }
        String allowedOrderStatuses = switch (checkpointStatus) {
            case "VALIDATED" -> "'VALIDATED','APPLIED','COMPLETED'";
            case "APPLIED" -> "'APPLIED','COMPLETED'";
            case "COMPLETED" -> "'COMPLETED'";
            case "CHECKED" -> "'CHECKED','RETIRED','COMPLETED'";
            case "RETIRED" -> "'RETIRED','COMPLETED'";
            default -> "''";
        };
        Integer count = jdbcTemplate.queryForObject(
            """
            select count(*)
              from iws_process_attempt attempt
              join iws_process_event event on event.id = attempt.event_id
              join iws_process_instance instance on instance.id = event.instance_id
              join wms_space_change_order change_order
                on change_order.change_order_id = cast(instance.business_id as unsigned)
             where attempt.event_id = ?
               and attempt.effect_result = 'SUCCEEDED'
               and attempt.checkpoint_json is not null
               and json_unquote(json_extract(attempt.checkpoint_json, '$.status')) = ?
               and cast(json_unquote(json_extract(
                     attempt.checkpoint_json, '$.changeOrderId')) as unsigned) =
                   change_order.change_order_id
               and change_order.status in (%s)
               and change_order.canonical_digest is not null
               and json_unquote(json_extract(
                     attempt.checkpoint_json, '$.configDigest')) =
                   change_order.canonical_digest
               and (
                   ? <> 'APPLIED'
                   or (
                       cast(json_unquote(json_extract(
                           attempt.checkpoint_json, '$.locationVersionId')) as unsigned) =
                           change_order.location_version_id
                       and cast(json_unquote(json_extract(
                           attempt.checkpoint_json, '$.locationEffectiveRevision')) as unsigned) =
                           change_order.location_effective_revision
                       and cast(json_unquote(json_extract(
                           attempt.checkpoint_json, '$.objectRevision')) as unsigned) =
                           change_order.object_revision
                   )
               )
            """.formatted(allowedOrderStatuses),
            Integer.class,
            context.eventId(),
            checkpointStatus,
            checkpointStatus
        );
        if (count == null || count != 1) {
            return ValidationResult.fail(
                "SPACE_STATE_EVIDENCE",
                "空间计划状态缺少匹配的持久单、对象版本或 checkpoint 证据"
            );
        }
        return ValidationResult.pass();
    }
}
