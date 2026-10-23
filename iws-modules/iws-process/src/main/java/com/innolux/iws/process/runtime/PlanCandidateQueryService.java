/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : worker 与查询共享的 QUEUE 候选排序；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 统一只读候选规则：类型优先级→实例优先级→事件优先级→就绪时间→事件 ID。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class PlanCandidateQueryService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造候选查询。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public PlanCandidateQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取 QUEUE 候选，排除 RECOVERY_ONLY 和未持 PLAN_SLOT 实例。
     *
     * @param scheduleId 业务调度 ID
     * @param limit 上限
     * @return 有序候选
     */
    public List<CandidateRow> loadQueueCandidates(long scheduleId, int limit) {
        return jdbcTemplate.query("""
            SELECT e.id AS event_id, e.instance_id AS instance_id,
                   COALESCE(t.priority, 0) AS plan_type_priority,
                   COALESCE(i.instance_priority, 0) AS instance_priority,
                   COALESCE(e.priority, 0) AS event_priority,
                   COALESCE(e.next_retry_time, e.started_at, e.create_time) AS ready_time
            FROM iws_process_event e
            JOIN iws_process_instance i ON i.id = e.instance_id
            LEFT JOIN iws_business_schedule_plan_type t
              ON t.schedule_id = i.schedule_id AND t.plan_type = i.plan_type AND t.enabled = 1
            WHERE i.schedule_id = ?
              AND i.plan_role = 'BUSINESS'
              AND i.plan_status = 'RUNNING'
              AND i.mode_snapshot <> 'IMMEDIATE'
              AND e.event_status IN ('PENDING', 'RETRY_WAIT')
              AND (e.next_retry_time IS NULL OR e.next_retry_time <= NOW(3))
              AND e.execution_purpose <> 'RECOVERY_ONLY'
              AND EXISTS (
                  SELECT 1 FROM iws_process_schedule_control c
                  WHERE c.scope_type = 'PLAN_SLOT'
                    AND c.scope_key = CAST(i.id AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_general_ci
                    AND c.duty = 'PLAN_SLOT' AND c.slot_state = 'HELD'
              )
            ORDER BY plan_type_priority DESC, instance_priority DESC, event_priority DESC,
                     ready_time ASC, e.id ASC
            LIMIT ?
            """, (rs, rowNum) -> new CandidateRow(rs.getLong("event_id"), rs.getLong("instance_id"),
            rs.getInt("plan_type_priority"), rs.getInt("instance_priority"), rs.getInt("event_priority"),
            rs.getTimestamp("ready_time") == null ? null : rs.getTimestamp("ready_time").toLocalDateTime()),
            scheduleId, limit);
    }

    /**
     * 候选行。
     *
     * @param eventId 事件 ID
     * @param instanceId 实例 ID
     * @param planTypePriority 类型优先级
     * @param instancePriority 实例优先级
     * @param eventPriority 事件优先级
     * @param readyTime 就绪时间
     */
    public record CandidateRow(long eventId, long instanceId, int planTypePriority, int instancePriority,
                               int eventPriority, java.time.LocalDateTime readyTime) {
    }
}
