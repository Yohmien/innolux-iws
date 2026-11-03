/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 锁位短事务
 * @Version      : V1.0
 * @Description  : 基于 JDBC 当前读的通道成员快照实现；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation.space;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** 通道成员 JDBC 读取器。 */
@Repository
public class WmsAllocationSpaceReaderImpl implements WmsAllocationSpaceReader {

    private static final String MEMBERSHIP_SQL = """
        SELECT m.member_id, m.storage_id, m.channel_id, m.access_order,
               m.revision AS member_revision, c.status AS channel_status,
               c.revision AS channel_revision
        FROM wms_storage_access_member m
        JOIN wms_access_channel c ON c.channel_id = m.channel_id
        WHERE m.storage_id IN (:storageIds)
        ORDER BY m.storage_id
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    public WmsAllocationSpaceReaderImpl(JdbcTemplate jdbcTemplate) {
        this(new NamedParameterJdbcTemplate(jdbcTemplate));
    }

    private WmsAllocationSpaceReaderImpl(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<Long, Membership> readInitial(Collection<Long> storageIds) {
        return query(storageIds, false);
    }

    @Override
    public Map<Long, Membership> readCurrentForUpdate(Collection<Long> storageIds) {
        return query(storageIds, true);
    }

    private Map<Long, Membership> query(Collection<Long> storageIds, boolean forUpdate) {
        Map<Long, Membership> result = new LinkedHashMap<>();
        if (storageIds.isEmpty()) {
            return result;
        }
        String sql = MEMBERSHIP_SQL + (forUpdate ? " FOR UPDATE" : "");
        jdbcTemplate.query(sql, Map.of("storageIds", storageIds), row -> {
            Membership membership = new Membership(
                row.getLong("storage_id"),
                row.getLong("member_id"),
                row.getLong("channel_id"),
                row.getInt("access_order"),
                row.getInt("member_revision"),
                row.getString("channel_status"),
                row.getInt("channel_revision")
            );
            result.put(membership.storageId(), membership);
        });
        return result;
    }
}
