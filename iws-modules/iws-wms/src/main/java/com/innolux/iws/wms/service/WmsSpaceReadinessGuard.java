/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存空间只读模型
 * @Version      : V1.0
 * @Description  : 空间只读查询的数据库结构与父链门禁；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.service;

import cn.hutool.http.HttpStatus;
import com.innolux.iws.common.core.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 空间只读查询的数据库结构与父链门禁。
 *
 * <p>空库直接使用最终空间结构；本门禁不会把空值推断为任何公司，也不会在
 * 缺少权威字段时回退旧列名。</p>
 *
 * @author YONGJIE.PENG
 */
@Component
@RequiredArgsConstructor
public class WmsSpaceReadinessGuard {

    private static final Map<String, List<String>> EXPECTED_COLUMNS = expectedColumns();

    private final JdbcTemplate jdbcTemplate;

    /**
     * 空间只读数据门禁的请求边界。
     *
     * @param companyIds 当前用户可管理的公司ID
     * @param warehouseId 显式目标仓库ID
     * @param zoneId      显式目标库区ID
     * @param storageId   显式目标储位ID
     */
    public record Scope(Set<Long> companyIds, Long warehouseId, Long zoneId, Long storageId) {

        public Scope {
            companyIds = companyIds == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(companyIds));
        }
    }

    /**
     * 校验仓库只读查询所需结构。
     */
    public void assertWarehouseReadable() {
        assertSchema(false, false);
    }

    /**
     * 校验库区只读查询所需结构和当前可证明范围内的父链。
     *
     * <p>无法证明归属的库区不进入普通用户门禁；显式目标对象不在可证明范围内时
     * 直接交给后续带公司范围的查询返回空结果，避免通过错误差异枚举对象。</p>
     *
     * @param scope 当前授权与显式目标边界
     */
    public void assertZoneReadable(Scope scope) {
        assertSchema(true, false);
        if (scope == null || scope.companyIds().isEmpty()) {
            return;
        }
        if (scope.zoneId() != null) {
            Map<Long, ZoneNode> zones = loadZoneAncestors(scope.companyIds(), scope.zoneId());
            if (zones.isEmpty()) {
                return;
            }
            if (scope.warehouseId() != null
                && !chainMatchesWarehouse(zones, scope.zoneId(), scope.warehouseId())) {
                return;
            }
            validateZoneGraph(zones);
            return;
        }
        if (scope.warehouseId() != null) {
            if (!warehouseInScope(scope.companyIds(), scope.warehouseId())) {
                return;
            }
            validateZoneGraph(loadZoneGraph(scope.companyIds(), scope.warehouseId()));
            return;
        }
        validateZoneGraph(loadZoneGraph(scope.companyIds()));
    }

    /**
     * 校验储位只读查询所需结构和完整父链。
     *
     * @param scope 当前授权与显式目标边界
     */
    public void assertStorageReadable(Scope scope) {
        assertSchema(true, true);
        if (scope == null || scope.companyIds().isEmpty()) {
            return;
        }
        if (scope.storageId() != null) {
            StorageNode storage = loadStorage(scope.storageId());
            if (storage == null) {
                return;
            }
            if (storage.warehouseId() != null
                && !warehouseInScope(scope.companyIds(), storage.warehouseId())) {
                return;
            }
            Map<Long, ZoneNode> zones = loadZoneAncestors(scope.companyIds(), storage.zoneId());
            if (storage.warehouseId() == null && zones.isEmpty()) {
                return;
            }
            if (!storageMatchesWarehouse(storage, zones, scope.warehouseId())) {
                return;
            }
            validateZoneGraph(zones);
            validateStorages(zones, Map.of(storage.storageId(), storage));
            return;
        }
        if (scope.zoneId() != null) {
            Map<Long, ZoneNode> zones = loadZoneAncestors(scope.companyIds(), scope.zoneId());
            if (zones.isEmpty()
                || scope.warehouseId() != null
                && !chainMatchesWarehouse(zones, scope.zoneId(), scope.warehouseId())) {
                return;
            }
            validateZoneGraph(zones);
            validateStorages(zones, loadStoragesForZone(scope.zoneId()));
            return;
        }
        if (scope.warehouseId() != null) {
            if (!warehouseInScope(scope.companyIds(), scope.warehouseId())) {
                return;
            }
            Map<Long, ZoneNode> zones = loadZoneGraph(scope.companyIds(), scope.warehouseId());
            validateZoneGraph(zones);
            validateStorages(zones, loadStoragesForWarehouse(scope.warehouseId(), zones.keySet()));
            return;
        }
        Map<Long, ZoneNode> zones = loadZoneGraph(scope.companyIds());
        validateZoneGraph(zones);
        validateStorages(zones, loadStorages(scope.companyIds(), zones.keySet()));
    }

    private Map<Long, ZoneNode> loadZoneGraph(Set<Long> companyIds) {
        return loadZoneGraph(companyIds, null);
    }

    private Map<Long, ZoneNode> loadZoneGraph(Set<Long> companyIds, Long warehouseId) {
        Map<Long, ZoneNode> zones = new LinkedHashMap<>();
        List<ZoneNode> owned;
        if (warehouseId == null) {
            owned = jdbcTemplate.query(
                "select z.zone_id, z.warehouse_id, z.parent_zone_id "
                    + "from wms_warehouse_zone z "
                    + "join wms_warehouse w on w.warehouse_id = z.warehouse_id "
                    + "where z.del_flag = '0' and w.company_id in ("
                    + placeholders(companyIds.size()) + ")",
                zoneRowMapper(),
                companyIds.toArray()
            );
        } else {
            Object[] args = new Object[companyIds.size() + 1];
            args[0] = warehouseId;
            int index = 1;
            for (Long companyId : companyIds) {
                args[index++] = companyId;
            }
            owned = jdbcTemplate.query(
                "select z.zone_id, z.warehouse_id, z.parent_zone_id "
                    + "from wms_warehouse_zone z "
                    + "join wms_warehouse w on w.warehouse_id = z.warehouse_id "
                    + "where z.del_flag = '0' and z.warehouse_id = ? "
                    + "and w.company_id in (" + placeholders(companyIds.size()) + ")",
                zoneRowMapper(),
                args
            );
        }
        owned.forEach(zone -> zones.put(zone.zoneId(), zone));

        Set<Long> frontier = new LinkedHashSet<>(zones.keySet());
        while (!frontier.isEmpty()) {
            List<ZoneNode> children = jdbcTemplate.query(
                "select zone_id, warehouse_id, parent_zone_id "
                    + "from wms_warehouse_zone "
                    + "where del_flag = '0' and warehouse_id is null "
                    + "and parent_zone_id in (" + placeholders(frontier.size()) + ")",
                zoneRowMapper(),
                frontier.toArray()
            );
            Set<Long> next = new LinkedHashSet<>();
            for (ZoneNode child : children) {
                if (!zones.containsKey(child.zoneId())) {
                    zones.put(child.zoneId(), child);
                    next.add(child.zoneId());
                }
            }
            frontier = next;
        }
        return zones;
    }

    private Map<Long, ZoneNode> loadZoneAncestors(Set<Long> companyIds, Long zoneId) {
        ZoneNode target = loadZone(zoneId);
        if (target == null) {
            return Map.of();
        }
        if (target.warehouseId() != null
            && !warehouseInScope(companyIds, target.warehouseId())) {
            return Map.of();
        }
        Map<Long, ZoneNode> zones = new LinkedHashMap<>();
        Set<Long> visited = new HashSet<>();
        Long current = zoneId;
        Long firstWarehouseId = target.warehouseId();
        while (current != null && visited.add(current)) {
            ZoneNode zone = loadZone(current);
            if (zone == null) {
                break;
            }
            zones.put(zone.zoneId(), zone);
            if (firstWarehouseId == null && zone.warehouseId() != null) {
                if (!warehouseInScope(companyIds, zone.warehouseId())) {
                    return Map.of();
                }
                firstWarehouseId = zone.warehouseId();
            }
            current = zone.parentZoneId();
        }
        return firstWarehouseId == null ? Map.of() : zones;
    }

    private ZoneNode loadZone(Long zoneId) {
        List<ZoneNode> rows = jdbcTemplate.query(
            "select zone_id, warehouse_id, parent_zone_id from wms_warehouse_zone "
                + "where zone_id = ? and del_flag = '0'",
            zoneRowMapper(),
            zoneId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean chainMatchesWarehouse(Map<Long, ZoneNode> zones, Long zoneId, Long warehouseId) {
        Set<Long> visited = new HashSet<>();
        Long current = zoneId;
        while (current != null && visited.add(current)) {
            ZoneNode zone = zones.get(current);
            if (zone == null) {
                return false;
            }
            if (Objects.equals(zone.warehouseId(), warehouseId)) {
                return true;
            }
            current = zone.parentZoneId();
        }
        return false;
    }

    private Map<Long, StorageNode> loadStorages(Set<Long> companyIds, Set<Long> zoneIds) {
        Map<Long, StorageNode> storages = new LinkedHashMap<>();
        List<StorageNode> owned = jdbcTemplate.query(
            "select s.storage_id, s.warehouse_id, s.zone_id "
                + "from wms_storage s "
                + "join wms_warehouse w on w.warehouse_id = s.warehouse_id "
                + "where s.del_flag = '0' and w.company_id in ("
                + placeholders(companyIds.size()) + ")",
            storageRowMapper(),
            companyIds.toArray()
        );
        owned.forEach(storage -> storages.put(storage.storageId(), storage));
        if (zoneIds.isEmpty()) {
            return storages;
        }
        List<StorageNode> unresolved = jdbcTemplate.query(
            "select storage_id, warehouse_id, zone_id from wms_storage "
                + "where del_flag = '0' and warehouse_id is null "
                + "and zone_id in (" + placeholders(zoneIds.size()) + ")",
            storageRowMapper(),
            zoneIds.toArray()
        );
        unresolved.forEach(storage -> storages.putIfAbsent(storage.storageId(), storage));
        return storages;
    }

    private StorageNode loadStorage(Long storageId) {
        List<StorageNode> rows = jdbcTemplate.query(
            "select storage_id, warehouse_id, zone_id from wms_storage "
                + "where storage_id = ? and del_flag = '0'",
            storageRowMapper(),
            storageId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<Long, StorageNode> loadStoragesForZone(Long zoneId) {
        Map<Long, StorageNode> storages = new LinkedHashMap<>();
        List<StorageNode> rows = jdbcTemplate.query(
            "select storage_id, warehouse_id, zone_id from wms_storage "
                + "where del_flag = '0' and zone_id = ?",
            storageRowMapper(),
            zoneId
        );
        rows.forEach(storage -> storages.put(storage.storageId(), storage));
        return storages;
    }

    private Map<Long, StorageNode> loadStoragesForWarehouse(Long warehouseId, Set<Long> zoneIds) {
        Map<Long, StorageNode> storages = new LinkedHashMap<>();
        List<StorageNode> owned = jdbcTemplate.query(
            "select storage_id, warehouse_id, zone_id from wms_storage "
                + "where del_flag = '0' and warehouse_id = ?",
            storageRowMapper(),
            warehouseId
        );
        owned.forEach(storage -> storages.put(storage.storageId(), storage));
        if (zoneIds.isEmpty()) {
            return storages;
        }
        List<StorageNode> unresolved = jdbcTemplate.query(
            "select storage_id, warehouse_id, zone_id from wms_storage "
                + "where del_flag = '0' and warehouse_id is null "
                + "and zone_id in (" + placeholders(zoneIds.size()) + ")",
            storageRowMapper(),
            zoneIds.toArray()
        );
        unresolved.forEach(storage -> storages.putIfAbsent(storage.storageId(), storage));
        return storages;
    }

    private boolean storageMatchesWarehouse(StorageNode storage,
                                            Map<Long, ZoneNode> zones,
                                            Long warehouseId) {
        if (warehouseId == null) {
            return true;
        }
        return storage.warehouseId() != null
            ? Objects.equals(storage.warehouseId(), warehouseId)
            : chainMatchesWarehouse(zones, storage.zoneId(), warehouseId);
    }

    private void validateZoneGraph(Map<Long, ZoneNode> zones) {
        if (zones.values().stream().anyMatch(zone -> zone.warehouseId() == null)) {
            throw blocked(
                WmsSpaceContract.ERROR_SPACE_WAREHOUSE_UNRESOLVED,
                "当前可管理范围内存在未回填仓库归属的有效库区；拒绝推断公司归属"
            );
        }
        for (ZoneNode zone : zones.values()) {
            Long parentId = zone.parentZoneId();
            if (parentId == null) {
                continue;
            }
            if (Objects.equals(parentId, zone.zoneId())) {
                throw parentChainInconsistent();
            }
            ZoneNode parent = zones.get(parentId);
            if (parent == null || !Objects.equals(parent.warehouseId(), zone.warehouseId())) {
                throw parentChainInconsistent();
            }
        }
        detectZoneCycles(zones);
    }

    private void detectZoneCycles(Map<Long, ZoneNode> zones) {
        Map<Long, Integer> states = new HashMap<>();
        for (Long start : zones.keySet()) {
            if (states.getOrDefault(start, 0) == 2) {
                continue;
            }
            List<Long> path = new ArrayList<>();
            Long current = start;
            while (current != null) {
                int state = states.getOrDefault(current, 0);
                if (state == 2) {
                    break;
                }
                if (state == 1) {
                    throw parentChainInconsistent();
                }
                states.put(current, 1);
                path.add(current);
                current = zones.get(current).parentZoneId();
            }
            for (Long zoneId : path) {
                states.put(zoneId, 2);
            }
        }
    }

    private void validateStorages(Map<Long, ZoneNode> zones, Map<Long, StorageNode> storages) {
        for (StorageNode storage : storages.values()) {
            if (storage.warehouseId() == null) {
                throw blocked(
                    WmsSpaceContract.ERROR_SPACE_WAREHOUSE_UNRESOLVED,
                    "当前可管理范围内存在未回填仓库归属的有效储位；拒绝推断公司归属"
                );
            }
            ZoneNode zone = zones.get(storage.zoneId());
            if (zone == null || !Objects.equals(zone.warehouseId(), storage.warehouseId())) {
                throw parentChainInconsistent();
            }
        }
    }

    private boolean warehouseInScope(Set<Long> companyIds, Long warehouseId) {
        if (companyIds.isEmpty()) {
            return false;
        }
        Object[] args = new Object[companyIds.size() + 1];
        args[0] = warehouseId;
        int index = 1;
        for (Long companyId : companyIds) {
            args[index++] = companyId;
        }
        return !jdbcTemplate.queryForList(
            "select 1 from wms_warehouse where warehouse_id = ? and company_id in ("
                + placeholders(companyIds.size()) + ")",
            Long.class,
            args
        ).isEmpty();
    }

    private RowMapper<ZoneNode> zoneRowMapper() {
        return (rs, rowNum) -> new ZoneNode(
            rs.getLong("zone_id"),
            nullableLong(rs.getLong("warehouse_id"), rs.wasNull()),
            nullableLong(rs.getLong("parent_zone_id"), rs.wasNull())
        );
    }

    private RowMapper<StorageNode> storageRowMapper() {
        return (rs, rowNum) -> new StorageNode(
            rs.getLong("storage_id"),
            nullableLong(rs.getLong("warehouse_id"), rs.wasNull()),
            rs.getLong("zone_id")
        );
    }

    private Long nullableLong(long value, boolean wasNull) {
        return wasNull ? null : value;
    }

    private String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private ServiceException parentChainInconsistent() {
        return blocked(
            WmsSpaceContract.ERROR_SPACE_PARENT_CHAIN_INCONSISTENT,
            "当前可管理范围内存在库区父链缺失、跨仓或成环；拒绝返回空间父链"
        );
    }

    private record ZoneNode(Long zoneId, Long warehouseId, Long parentZoneId) {
    }

    private record StorageNode(Long storageId, Long warehouseId, Long zoneId) {
    }

    private void assertSchema(boolean requireZone, boolean requireStorage) {
        Set<String> tables = new HashSet<>(jdbcTemplate.queryForList(
            """
            select table_name
            from information_schema.tables
            where table_schema = database()
              and table_name in (?, ?, ?)
            """,
            String.class,
            WmsSpaceContract.TABLE_WAREHOUSE,
            WmsSpaceContract.TABLE_ZONE,
            WmsSpaceContract.TABLE_STORAGE
        ));
        Set<String> columns = new HashSet<>(jdbcTemplate.queryForList(
            """
            select concat(table_name, '.', column_name)
            from information_schema.columns
            where table_schema = database()
              and table_name in (?, ?, ?)
            """,
            String.class,
            WmsSpaceContract.TABLE_WAREHOUSE,
            WmsSpaceContract.TABLE_ZONE,
            WmsSpaceContract.TABLE_STORAGE
        ));

        List<String> requiredTables = new ArrayList<>();
        requiredTables.add(WmsSpaceContract.TABLE_WAREHOUSE);
        if (requireZone) {
            requiredTables.add(WmsSpaceContract.TABLE_ZONE);
        }
        if (requireStorage) {
            requiredTables.add(WmsSpaceContract.TABLE_STORAGE);
        }

        List<String> missing = new ArrayList<>();
        for (String table : requiredTables) {
            if (!tables.contains(table)) {
                missing.add("TABLE " + table);
                continue;
            }
            for (String column : EXPECTED_COLUMNS.get(table)) {
                if (!columns.contains(table + "." + column)) {
                    missing.add("COLUMN " + table + "." + column);
                }
            }
        }
        if (!missing.isEmpty()) {
            throw blocked(
                WmsSpaceContract.ERROR_SCHEMA_STATE_UNSUPPORTED,
                "空间只读查询缺少 " + String.join(", ", missing)
                    + "；需要 S1 空间结构和 type_config_version_id 修复，禁止使用旧列名回退"
            );
        }
    }

    private ServiceException blocked(String code, String message) {
        return new ServiceException(code + ": " + message, HttpStatus.HTTP_CONFLICT);
    }

    private static Map<String, List<String>> expectedColumns() {
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put(WmsSpaceContract.TABLE_WAREHOUSE, List.of(
            "warehouse_id", "company_id", "warehouse_code", "warehouse_name",
            "warehouse_type_code", "type_config_version_id", "lifecycle_status",
            "enabled", "revision", "remark",
            "create_dept", "create_by", "create_time", "update_by", "update_time"
        ));
        expected.put(WmsSpaceContract.TABLE_ZONE, List.of(
            "zone_id", "zone_code", "zone_name", "zone_type", "zone_kind",
            "lifecycle_status", "plant_code",
            "warehouse_code", "parent_zone_id", "warehouse_id", "allow_inbound",
            "allow_outbound", "status", "revision", "sort_no", "del_flag", "remark",
            "create_dept", "create_by", "create_time", "update_by", "update_time"
        ));
        expected.put(WmsSpaceContract.TABLE_STORAGE, List.of(
            "storage_id", "storage_code", "zone_id", "storage_type", "height_type",
            "height_desc", "row_no", "col_no", "level_no", "x_coordinate",
            "y_coordinate", "port_code", "wcs_point_code", "status", "lock_status",
            "disable_status", "lifecycle_status", "version", "del_flag", "remark", "warehouse_id",
            "aisle_text", "row_text", "level_text", "column_text", "depth_text",
            "aisle_text_is_null", "row_text_is_null", "level_text_is_null",
            "column_text_is_null", "depth_text_is_null",
            "aisle_text_cmp", "row_text_cmp", "level_text_cmp",
            "column_text_cmp", "depth_text_cmp",
            "create_dept", "create_by", "create_time", "update_by", "update_time"
        ));
        return expected;
    }
}
