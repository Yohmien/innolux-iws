/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理计划对象授权
 * @Version      : V1.0
 * @Description  : 从空间变更单或稳定对象身份解析当前公司范围；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.process;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;

/** 空间变更挂载和执行授权共用的公司解析器。 */
final class WmsSpaceOrderCompanyResolver {

    private WmsSpaceOrderCompanyResolver() {
    }

    /**
     * 优先使用变更单已回填公司；创建或退役受理早期按对象类型解析当前公司。
     *
     * @param jdbcTemplate JDBC 入口
     * @param orderId      空间变更单 ID
     * @return 公司 ID，无法解析时为空
     */
    static Long resolve(JdbcTemplate jdbcTemplate, Long orderId) {
        if (orderId == null || orderId <= 0) {
            return null;
        }
        Scope scope = jdbcTemplate.query("""
            select company_id, object_type, object_id
              from wms_space_change_order where change_order_id = ?
            """, (rs, rowNum) -> new Scope(
            nullableLong(rs, "company_id"),
            rs.getString("object_type"),
            nullableLong(rs, "object_id")
        ), orderId).stream().findFirst().orElse(null);
        if (scope == null) {
            return null;
        }
        return scope.companyId() != null
            ? scope.companyId()
            : resolveByObject(jdbcTemplate, scope.objectType(), scope.objectId());
    }

    private static Long resolveByObject(
        JdbcTemplate jdbcTemplate,
        String objectType,
        Long objectId
    ) {
        if (objectType == null || objectId == null || objectId <= 0) {
            return null;
        }
        String sql = switch (objectType) {
            case "WAREHOUSE" -> """
                select company_id from wms_warehouse where warehouse_id = ?
                """;
            case "ZONE" -> """
                select warehouse.company_id
                  from wms_warehouse_zone zone
                  join wms_warehouse warehouse
                    on warehouse.warehouse_id = zone.warehouse_id
                 where zone.zone_id = ?
                """;
            case "STORAGE" -> """
                select warehouse.company_id
                  from wms_storage storage
                  join wms_warehouse warehouse
                    on warehouse.warehouse_id = storage.warehouse_id
                 where storage.storage_id = ?
                """;
            default -> null;
        };
        if (sql == null) {
            return null;
        }
        return jdbcTemplate.query(sql, (rs, rowNum) ->
            nullableLong(rs, "company_id"), objectId)
            .stream().findFirst().orElse(null);
    }

    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private record Scope(Long companyId, String objectType, Long objectId) {
    }
}
