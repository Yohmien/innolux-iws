/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 装拆计划动作
 * @Version      : V1.0
 * @Description  : 校验装拆计划状态迁移和预检、提交 checkpoint 证据；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.change.process;

import com.innolux.iws.process.runtime.StateContext;
import com.innolux.iws.process.runtime.StateValidator;
import com.innolux.iws.process.runtime.StateValidatorDescriptor;
import com.innolux.iws.process.runtime.ValidationResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Objects;

/** 装拆计划状态校验器。 */
public class WmsLoadChangeStateValidator implements StateValidator {

    public static final String VALIDATOR_KEY = "WMS_LOAD_CHANGE_STATE";

    public static final String VALIDATOR_VERSION = "1";

    public static final String STATE_START = "START";

    public static final String STATE_CHECKED = "CHECKED";

    public static final String STATE_END = "END";

    private static final Map<String, String> TRANSITIONS = Map.of(
        STATE_START, STATE_CHECKED,
        STATE_CHECKED, STATE_END
    );

    private static final String CHECK_EVIDENCE_SQL = """
        SELECT COUNT(*)
        FROM iws_process_attempt a
        JOIN iws_process_event e ON e.id = a.event_id
        WHERE a.event_id = ?
          AND e.instance_id = ?
          AND a.effect_result = 'SUCCEEDED'
          AND a.input_hash = ?
          AND a.checkpoint_json IS NOT NULL
          AND JSON_UNQUOTE(JSON_EXTRACT(a.checkpoint_json, '$.status')) = 'CHECKED'
          AND JSON_UNQUOTE(JSON_EXTRACT(a.checkpoint_json, '$.inputHash')) = ?
          AND JSON_TYPE(JSON_EXTRACT(a.checkpoint_json, '$.changes')) = 'ARRAY'
          AND JSON_LENGTH(a.checkpoint_json, '$.changes') >= 1
        """;

    private static final String APPLY_EVIDENCE_SQL = """
        SELECT COUNT(*)
        FROM iws_process_attempt a
        JOIN iws_process_event e ON e.id = a.event_id
        JOIN wms_occupancy_effect effect
          ON effect.effect_key =
             JSON_UNQUOTE(JSON_EXTRACT(a.checkpoint_json, '$.effectKey'))
        WHERE a.event_id = ?
          AND e.instance_id = ?
          AND a.effect_result = 'SUCCEEDED'
          AND a.input_hash = ?
          AND a.checkpoint_json IS NOT NULL
          AND JSON_UNQUOTE(JSON_EXTRACT(a.checkpoint_json, '$.status')) = 'APPLIED'
          AND effect.plan_instance_id = e.instance_id
        """;

    private final JdbcTemplate jdbcTemplate;

    public WmsLoadChangeStateValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public StateValidatorDescriptor describe() {
        return new StateValidatorDescriptor(
            VALIDATOR_KEY, VALIDATOR_VERSION, "装拆计划状态");
    }

    @Override
    public ValidationResult validateStructural(StateContext context) {
        if (context == null || context.instanceId() <= 0 || context.eventId() <= 0) {
            return ValidationResult.fail("LOAD_CHANGE_STATE_CONTEXT", "装拆计划状态上下文不完整");
        }
        if (!Objects.equals(TRANSITIONS.get(context.sourceStateCode()), context.targetStateCode())) {
            return ValidationResult.fail(
                "LOAD_CHANGE_STATE_TRANSITION",
                "装拆计划不允许状态迁移: " + context.sourceStateCode()
                    + " -> " + context.targetStateCode()
            );
        }
        if (context.inputHash() == null || context.inputHash().isBlank()) {
            return ValidationResult.fail("LOAD_CHANGE_STATE_INPUT", "装拆计划状态缺少输入摘要");
        }
        return ValidationResult.pass();
    }

    @Override
    public ValidationResult validateBusiness(StateContext context) {
        ValidationResult structural = validateStructural(context);
        if (!structural.passed()) {
            return structural;
        }
        String sql = STATE_CHECKED.equals(context.targetStateCode())
            ? CHECK_EVIDENCE_SQL : APPLY_EVIDENCE_SQL;
        int parameters = STATE_CHECKED.equals(context.targetStateCode()) ? 4 : 3;
        Integer count = parameters == 4
            ? jdbcTemplate.queryForObject(
                sql, Integer.class,
                context.eventId(), context.instanceId(), context.inputHash(), context.inputHash())
            : jdbcTemplate.queryForObject(
                sql, Integer.class,
                context.eventId(), context.instanceId(), context.inputHash());
        if (count == null || count != 1) {
            return ValidationResult.fail(
                "LOAD_CHANGE_STATE_EVIDENCE",
                "装拆计划状态缺少匹配的预检或提交 checkpoint 证据"
            );
        }
        return ValidationResult.pass();
    }
}
