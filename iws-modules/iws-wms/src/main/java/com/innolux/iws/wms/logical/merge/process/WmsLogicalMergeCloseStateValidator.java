/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 校验父范围确认、完成屏障与最终收敛证据；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.logical.merge.process;

import com.innolux.iws.process.runtime.StateContext;
import com.innolux.iws.process.runtime.StateValidator;
import com.innolux.iws.process.runtime.StateValidatorDescriptor;
import com.innolux.iws.process.runtime.ValidationResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Objects;

/** 逻辑合并父计划状态校验器。 */
public class WmsLogicalMergeCloseStateValidator implements StateValidator {

    private static final Map<String, String> TRANSITIONS = Map.of(
        "START", "CAPTURED",
        "CAPTURED", "END"
    );

    private final JdbcTemplate jdbcTemplate;

    public WmsLogicalMergeCloseStateValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public StateValidatorDescriptor describe() {
        return new StateValidatorDescriptor(
            WmsLogicalMergePlanContract.CLOSE_STATE_VALIDATOR_KEY,
            WmsLogicalMergePlanContract.VALIDATOR_VERSION,
            "逻辑合并父状态"
        );
    }

    @Override
    public ValidationResult validateStructural(StateContext context) {
        if (context == null || context.instanceId() <= 0 || context.eventId() <= 0) {
            return ValidationResult.fail(
                "LOGICAL_MERGE_CLOSE_CONTEXT", "逻辑合并父状态上下文不完整");
        }
        if (!Objects.equals(
            TRANSITIONS.get(context.sourceStateCode()), context.targetStateCode())) {
            return ValidationResult.fail(
                "LOGICAL_MERGE_CLOSE_TRANSITION", "逻辑合并父状态迁移非法");
        }
        return ValidationResult.pass();
    }

    @Override
    public ValidationResult validateBusiness(StateContext context) {
        ValidationResult structural = validateStructural(context);
        if (!structural.passed()) {
            return structural;
        }
        String statuses = context.targetStateCode().equals("END")
            ? "'COMPLETED'" : "'RUNNING','VERIFYING','COMPLETED'";
        String checkpointStatus = context.targetStateCode().equals("END")
            ? "COMPLETED" : "CAPTURED";
        Integer count = jdbcTemplate.queryForObject(
            """
            select count(*)
              from iws_process_attempt attempt
              join iws_process_instance instance on instance.id = attempt.instance_id
              join wms_logical_merge_order merge_order
                on merge_order.merge_order_id = cast(instance.business_id as unsigned)
             where attempt.event_id = ?
               and attempt.effect_result = 'SUCCEEDED'
               and attempt.checkpoint_json is not null
               and json_unquote(json_extract(attempt.checkpoint_json, '$.status')) = ?
               and cast(json_unquote(json_extract(
                     attempt.checkpoint_json, '$.mergeOrderId')) as unsigned) =
                   merge_order.merge_order_id
               and merge_order.status in (%s)
               and (
                   ? <> 'COMPLETED'
                   or merge_order.conflict_count = 0
               )
            """.formatted(statuses),
            Integer.class,
            context.eventId(),
            checkpointStatus,
            checkpointStatus
        );
        if (count == null || count != 1) {
            return ValidationResult.fail(
                "LOGICAL_MERGE_CLOSE_EVIDENCE", "逻辑合并父状态证据不一致");
        }
        return ValidationResult.pass();
    }
}
