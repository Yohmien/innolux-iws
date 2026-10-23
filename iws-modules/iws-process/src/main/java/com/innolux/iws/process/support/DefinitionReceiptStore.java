/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 定义命令回执当前读与版本序列锁；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

/**
 * 定义层命令的数据库当前读。
 *
 * <p>MyBatis-Plus 的逻辑删除会把已删除版本从普通查询中隐藏，而业务版本号必须包含已删除历史。
 * 版本序列与回执重投使用本组件显式当前读，避免复用已删除的最大版本，也不修改公共 Mapper。</p>
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class DefinitionReceiptStore {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造当前读组件。
     *
     * @param dataSource 数据源
     */
    public DefinitionReceiptStore(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * 当前读命令回执，必要时等待并发命令提交。
     *
     * <p>使用 {@code FOR UPDATE} 而不是普通快照读，保证在 REPEATABLE READ 下
     * 等待锁后能读到赢家已提交的回执。</p>
     *
     * @param receiptKey 回执键
     * @return 回执，未找到返回 null
     */
    public Receipt findCurrent(String receiptKey) {
        List<Receipt> rows = jdbcTemplate.query(
            "SELECT payload_summary, decision_summary, after_status, after_version"
                + " FROM iws_process_action_audit WHERE receipt_key = ? FOR UPDATE",
            (resultSet, index) -> new Receipt(resultSet.getString("payload_summary"),
                resultSet.getString("decision_summary"), resultSet.getString("after_status"),
                resultSet.getObject("after_version", Long.class)),
            receiptKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 业务版本最大值，包含逻辑删除历史。
     *
     * @param planCode 计划编码
     * @return 最大版本号，没有历史时返回 0
     */
    public int maxPlanVersion(String planCode) {
        Integer value = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(plan_version), 0) FROM iws_process_definition WHERE plan_code = ?",
            Integer.class, planCode);
        return value == null ? 0 : value;
    }

    /**
     * 锁定同一计划编码的全部历史版本行，串行化复制与新建。
     *
     * @param planCode 计划编码
     */
    public void lockPlan(String planCode) {
        jdbcTemplate.queryForList("SELECT id FROM iws_process_definition WHERE plan_code = ?"
            + " ORDER BY plan_version FOR UPDATE", Long.class, planCode);
    }

    /**
     * 最小命令回执投影。
     *
     * @param payloadSummary 参数摘要
     * @param decisionSummary 原受理结果
     * @param afterStatus 结果状态
     * @param afterVersion 结果版本
     */
    public record Receipt(String payloadSummary, String decisionSummary, String afterStatus, Long afterVersion) {
    }
}
