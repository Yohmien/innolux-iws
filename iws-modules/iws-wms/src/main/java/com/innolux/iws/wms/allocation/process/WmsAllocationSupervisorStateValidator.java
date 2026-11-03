/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配计划
 * @Version      : V1.0
 * @Description  : 校验分配监督计划的候选决策与准入 checkpoint 证据；
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

/** 分配监督计划状态校验器。 */
public class WmsAllocationSupervisorStateValidator implements StateValidator {

    public static final String VALIDATOR_KEY = "WMS_ALLOCATION_SUPERVISOR_STATE";

    public static final String VALIDATOR_VERSION = "1";

    public static final String STATE_START = "START";

    public static final String STATE_DECIDED = "DECIDED";

    public static final String STATE_END = "END";

    private static final Map<String, String> TRANSITIONS = Map.of(
        STATE_START, STATE_DECIDED,
        STATE_DECIDED, STATE_END
    );

    private static final String DECISION_EVIDENCE_SQL = """
        SELECT COUNT(*)
        FROM iws_process_event e
        JOIN iws_process_attempt a ON a.event_id = e.id
        JOIN iws_process_schedule_control c
          ON c.supervisor_instance_id = e.instance_id
         AND c.duty = 'DISPATCH'
        WHERE e.id = ?
          AND e.instance_id = ?
          AND e.event_role = 'DECISION'
          AND e.source_state_code = ?
          AND e.target_state_code = ?
          AND a.effect_result = 'SUCCEEDED'
          AND a.checkpoint_json IS NOT NULL
          AND JSON_UNQUOTE(JSON_EXTRACT(a.checkpoint_json, '$.decisionCode')) = e.event_code
          AND JSON_EXTRACT(a.checkpoint_json, '$.roundNo') = e.round_no
          AND JSON_TYPE(JSON_EXTRACT(a.checkpoint_json, '$.selected')) = 'ARRAY'
          AND c.round_no = e.round_no
          AND c.round_status = 'OPEN'
        """;

    private static final String ADMISSION_EVIDENCE_SQL = """
        SELECT COUNT(*)
        FROM iws_process_event e
        JOIN iws_process_attempt a ON a.event_id = e.id
        LEFT JOIN iws_process_schedule_control slot
          ON slot.supervisor_instance_id = e.instance_id
         AND slot.duty = 'PLAN_SLOT'
         AND slot.child_instance_id = CAST(SUBSTRING_INDEX(e.event_key, ':C', -1) AS UNSIGNED)
         AND slot.slot_state = 'HELD'
        WHERE e.id = ?
          AND e.instance_id = ?
          AND e.event_role = 'LOCAL'
          AND e.source_state_code = ?
          AND e.target_state_code = ?
          AND a.effect_result = 'SUCCEEDED'
          AND a.checkpoint_json IS NOT NULL
          AND JSON_UNQUOTE(JSON_EXTRACT(a.checkpoint_json, '$.code')) IN (
              'ADMITTED', 'ALREADY_ADMITTED', 'SLOT_FULL', 'STALE_CANDIDATE',
              'SUPERVISOR_PAUSED', 'MODE_MISMATCH', 'SLOT_RELEASED'
          )
          AND (
              JSON_UNQUOTE(JSON_EXTRACT(a.checkpoint_json, '$.code'))
                  NOT IN ('ADMITTED', 'ALREADY_ADMITTED')
              OR slot.id IS NOT NULL
          )
        """;

    private final JdbcTemplate jdbcTemplate;

    public WmsAllocationSupervisorStateValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public StateValidatorDescriptor describe() {
        return new StateValidatorDescriptor(
            VALIDATOR_KEY,
            VALIDATOR_VERSION,
            "分配监督计划状态"
        );
    }

    @Override
    public ValidationResult validateStructural(StateContext context) {
        if (context == null || context.instanceId() <= 0 || context.eventId() <= 0) {
            return ValidationResult.fail(
                "ALLOCATION_SUPERVISOR_CONTEXT",
                "分配监督状态上下文不完整"
            );
        }
        if (!Objects.equals(TRANSITIONS.get(context.sourceStateCode()), context.targetStateCode())) {
            return ValidationResult.fail(
                "ALLOCATION_SUPERVISOR_TRANSITION",
                "分配监督计划不允许状态迁移: " + context.sourceStateCode()
                    + " -> " + context.targetStateCode()
            );
        }
        if (context.inputHash() == null || context.inputHash().isBlank()) {
            return ValidationResult.fail(
                "ALLOCATION_SUPERVISOR_INPUT",
                "分配监督计划状态缺少输入摘要"
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
        String sql = STATE_DECIDED.equals(context.targetStateCode())
            ? DECISION_EVIDENCE_SQL : ADMISSION_EVIDENCE_SQL;
        Integer count = jdbcTemplate.queryForObject(
            sql,
            Integer.class,
            context.eventId(),
            context.instanceId(),
            context.sourceStateCode(),
            context.targetStateCode()
        );
        if (count == null || count != 1) {
            return ValidationResult.fail(
                "ALLOCATION_SUPERVISOR_EVIDENCE",
                "分配监督状态缺少匹配的决策或准入 checkpoint 证据"
            );
        }
        return ValidationResult.pass();
    }
}
