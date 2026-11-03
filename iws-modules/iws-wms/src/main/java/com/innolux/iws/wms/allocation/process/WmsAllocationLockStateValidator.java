/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配计划
 * @Version      : V1.0
 * @Description  : 校验分配计划状态迁移及已持久决策、锁和 checkpoint 证据；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.process;

import com.innolux.iws.process.runtime.StateContext;
import com.innolux.iws.process.runtime.StateValidator;
import com.innolux.iws.process.runtime.StateValidatorDescriptor;
import com.innolux.iws.process.runtime.ValidationResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Objects;

/** 分配计划状态校验器。 */
public class WmsAllocationLockStateValidator implements StateValidator {

    public static final String VALIDATOR_KEY = "WMS_ALLOCATION_LOCK_STATE";

    public static final String VALIDATOR_VERSION = "1";

    public static final String STATE_START = "START";

    public static final String STATE_EVALUATED = "EVALUATED";

    public static final String STATE_RESERVED = "RESERVED";

    public static final String STATE_END = "END";

    private static final Map<String, String> TRANSITIONS = Map.of(
        STATE_START, STATE_EVALUATED,
        STATE_EVALUATED, STATE_RESERVED,
        STATE_RESERVED, STATE_END
    );

    private static final String DECISION_EVIDENCE_SQL = """
        SELECT COUNT(*)
        FROM iws_process_attempt a
        JOIN wms_allocation_decision d
          ON d.decision_id = CAST(
               JSON_UNQUOTE(JSON_EXTRACT(a.checkpoint_json, '$.decisionId')) AS UNSIGNED)
        WHERE a.event_id = ?
          AND a.effect_result = 'SUCCEEDED'
          AND a.checkpoint_json IS NOT NULL
          AND JSON_UNQUOTE(JSON_EXTRACT(a.checkpoint_json, '$.status')) = ?
          AND d.plan_instance_id = ?
          AND d.phase = 'LOCK'
          AND d.result = ?
          AND (? = 'END' OR d.input_hash = ?)
          AND (? = 'EVALUATED' OR EXISTS (
              SELECT 1
              FROM wms_allocation_lock l
              WHERE l.decision_id = d.decision_id AND l.status = 'LOCKED'
          ))
          AND JSON_LENGTH(a.checkpoint_json, '$.allocationLockIds') = (
              SELECT COUNT(*)
              FROM wms_allocation_lock l
              WHERE l.decision_id = d.decision_id AND l.status = 'LOCKED'
          )
        """;

    private final JdbcTemplate jdbcTemplate;

    public WmsAllocationLockStateValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public StateValidatorDescriptor describe() {
        return new StateValidatorDescriptor(
            VALIDATOR_KEY, VALIDATOR_VERSION, "分配锁位计划状态");
    }

    @Override
    public ValidationResult validateStructural(StateContext context) {
        if (context == null || context.instanceId() <= 0 || context.eventId() <= 0) {
            return ValidationResult.fail("ALLOCATION_STATE_CONTEXT", "分配计划状态上下文不完整");
        }
        if (!Objects.equals(TRANSITIONS.get(context.sourceStateCode()), context.targetStateCode())) {
            return ValidationResult.fail(
                "ALLOCATION_STATE_TRANSITION",
                "分配计划不允许状态迁移: " + context.sourceStateCode() + " -> " + context.targetStateCode()
            );
        }
        if (context.inputHash() == null || context.inputHash().isBlank()) {
            return ValidationResult.fail("ALLOCATION_STATE_INPUT", "分配计划状态缺少输入摘要");
        }
        return ValidationResult.pass();
    }

    @Override
    public ValidationResult validateBusiness(StateContext context) {
        ValidationResult structural = validateStructural(context);
        if (!structural.passed()) {
            return structural;
        }
        String checkpointStatus = STATE_EVALUATED.equals(context.targetStateCode())
            ? STATE_EVALUATED : "LOCKED";
        String decisionResult = STATE_EVALUATED.equals(context.targetStateCode())
            ? STATE_EVALUATED : "LOCKED";
        Integer count = jdbcTemplate.queryForObject(
            DECISION_EVIDENCE_SQL,
            Integer.class,
            context.eventId(),
            checkpointStatus,
            context.instanceId(),
            decisionResult,
            context.targetStateCode(),
            context.inputHash(),
            context.targetStateCode()
        );
        if (count == null || count != 1) {
            return ValidationResult.fail(
                "ALLOCATION_STATE_EVIDENCE",
                "分配计划状态缺少匹配的决策、锁或 checkpoint 证据"
            );
        }
        return ValidationResult.pass();
    }
}
