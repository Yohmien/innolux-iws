/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配释放计划
 * @Version      : V1.0
 * @Description  : 校验释放计划状态迁移及 checkpoint、释放流水和锁终态证据；
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

/** 分配释放计划状态校验器。 */
public class WmsAllocationReleaseStateValidator implements StateValidator {

    public static final String VALIDATOR_KEY = "WMS_ALLOCATION_RELEASE_STATE";

    public static final String VALIDATOR_VERSION = "1";

    public static final String STATE_START = "START";

    public static final String STATE_EVIDENCE_CHECKED = "EVIDENCE_CHECKED";

    public static final String STATE_RELEASED = "RELEASED";

    public static final String STATE_END = "END";

    private static final Map<String, String> TRANSITIONS = Map.of(
        STATE_START, STATE_EVIDENCE_CHECKED,
        STATE_EVIDENCE_CHECKED, STATE_RELEASED,
        STATE_RELEASED, STATE_END
    );

    private static final String EVIDENCE_SQL = """
        SELECT COUNT(*)
        FROM iws_process_attempt a
        JOIN iws_process_event e ON e.id = a.event_id
        WHERE a.event_id = ?
          AND a.effect_result = 'SUCCEEDED'
          AND a.checkpoint_json IS NOT NULL
          AND e.instance_id = ?
          AND JSON_UNQUOTE(JSON_EXTRACT(a.checkpoint_json, '$.status')) = ?
          AND JSON_LENGTH(a.checkpoint_json, '$.allocationLockIds') > 0
        """;

    private static final String RELEASED_SQL = EVIDENCE_SQL + """
          AND JSON_LENGTH(a.checkpoint_json, '$.allocationLockIds') = (
              SELECT COUNT(*)
              FROM wms_allocation_release r
              WHERE r.plan_instance_id = e.instance_id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM wms_allocation_release r
              JOIN wms_allocation_lock l ON l.allocation_lock_id = r.allocation_lock_id
              WHERE r.plan_instance_id = e.instance_id
                AND (l.status <> 'RELEASED' OR l.effect_result <> 'NO_EFFECT')
          )
        """;

    private final JdbcTemplate jdbcTemplate;

    public WmsAllocationReleaseStateValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public StateValidatorDescriptor describe() {
        return new StateValidatorDescriptor(
            VALIDATOR_KEY, VALIDATOR_VERSION, "分配释放计划状态");
    }

    @Override
    public ValidationResult validateStructural(StateContext context) {
        if (context == null || context.instanceId() <= 0 || context.eventId() <= 0) {
            return ValidationResult.fail("ALLOCATION_RELEASE_STATE_CONTEXT", "释放计划状态上下文不完整");
        }
        if (!Objects.equals(TRANSITIONS.get(context.sourceStateCode()), context.targetStateCode())) {
            return ValidationResult.fail(
                "ALLOCATION_RELEASE_STATE_TRANSITION",
                "释放计划不允许状态迁移: " + context.sourceStateCode() + " -> " + context.targetStateCode()
            );
        }
        if (context.inputHash() == null || context.inputHash().isBlank()) {
            return ValidationResult.fail("ALLOCATION_RELEASE_STATE_INPUT", "释放计划状态缺少输入摘要");
        }
        return ValidationResult.pass();
    }

    @Override
    public ValidationResult validateBusiness(StateContext context) {
        ValidationResult structural = validateStructural(context);
        if (!structural.passed()) {
            return structural;
        }
        String sql = STATE_EVIDENCE_CHECKED.equals(context.targetStateCode())
            ? EVIDENCE_SQL : RELEASED_SQL;
        String checkpointStatus = STATE_END.equals(context.targetStateCode())
            ? STATE_RELEASED : context.targetStateCode();
        Integer count = jdbcTemplate.queryForObject(
            sql,
            Integer.class,
            context.eventId(),
            context.instanceId(),
            checkpointStatus
        );
        if (count == null || count != 1) {
            return ValidationResult.fail(
                "ALLOCATION_RELEASE_STATE_EVIDENCE",
                "释放计划状态缺少匹配的 checkpoint、流水或锁终态证据"
            );
        }
        return ValidationResult.pass();
    }
}
