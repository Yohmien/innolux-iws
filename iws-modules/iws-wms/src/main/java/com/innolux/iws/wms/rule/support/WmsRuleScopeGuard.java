/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则发布及生效读取的目标对象与管理范围校验；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.support;

import cn.hutool.http.HttpStatus;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.rule.WmsRuleContract;
import com.innolux.iws.wms.rule.domain.WmsRuleScopeRow;
import com.innolux.iws.wms.rule.domain.WmsRuleScopeTargetRow;
import com.innolux.iws.wms.rule.domain.WmsRuleScopeZoneRow;
import com.innolux.iws.wms.rule.mapper.WmsRuleScopeMapper;
import com.innolux.iws.wms.space.domain.WmsSpaceHierarchy;
import com.innolux.iws.wms.support.company.WmsCompanyAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 规则发布目标范围 Guard。
 *
 * <p>调用方先按 A 锁序取得 pointer 和 definition 锁。本 Guard 先无锁读取候选 ID，再在功能权限
 * S 之后依次共享锁定 company、warehouse、zone、storage，并在锁定后按当前行完整复核。空间
 * 实体分别按数字 ID 升序加锁；不得在取得储位锁后追加父链对象。</p>
 *
 * <p>锁后的类型、生命周期及归属只使用各实体 FOR SHARE 返回的当前行验证。候选 SELECT
 * 只收集锁定 ID，不用普通 SELECT 重读冒充 current read；当前父链超出已锁集合直接拒绝。</p>
 *
 * <p>发布只接受 ACTIVE 空间对象和后代储位；历史读取接受 ACTIVE/RETIRING/RETIRED，只要求
 * 目标、储位及其当前父链存在、完整且同公司。历史快照不要求仍在原 warehouse/zone。
 * COMPANY.enabled 不参与两种模式的有效性判断。</p>
 *
 * <p>固定锁序：实时功能权限 S → pointer X/S → definition S → company scope/grant S →
 * company S → warehouse S → zone S → storage S，全部保持至事务提交。任何后续空间应用都不得
 * 在此事务中取得 parent 锁后反向调用本发布方法，也不得依赖另一套反向顺序。</p>
 *
 * @author YONGJIE.PENG
 */
@Component
@RequiredArgsConstructor
public class WmsRuleScopeGuard {

    private static final String ACTIVE = "ACTIVE";

    private static final String NOT_DELETED = "0";

    private static final String RETIRING = "RETIRING";

    private static final String RETIRED = "RETIRED";

    private static final String MATERIAL = "MATERIAL";

    private static final String DEVICE = "DEVICE";

    private static final Set<String> HISTORICAL_LIFECYCLES = Set.of(ACTIVE, RETIRING, RETIRED);

    private static final Set<String> REQUIRED_COLUMNS = Set.of(
        "wms_company.company_id",
        "wms_company.enabled",
        "wms_company.lifecycle_status",
        "wms_warehouse.warehouse_id",
        "wms_warehouse.company_id",
        "wms_warehouse.lifecycle_status",
        "wms_warehouse_zone.zone_id",
        "wms_warehouse_zone.warehouse_id",
        "wms_warehouse_zone.parent_zone_id",
        "wms_warehouse_zone.zone_kind",
        "wms_warehouse_zone.lifecycle_status",
        "wms_warehouse_zone.del_flag",
        "wms_storage.storage_id",
        "wms_storage.warehouse_id",
        "wms_storage.zone_id",
        "wms_storage.lifecycle_status",
        "wms_storage.del_flag"
    );

    private final WmsRuleScopeMapper scopeMapper;

    private final WmsCompanyAccessPolicy accessPolicy;

    /**
     * 锁定并复核当前发布目标及后代储位快照。
     *
     * @param scopeType 发布作用域
     * @param scopeId 目标 ID
     * @param storageIds 命令显式提供的快照储位，允许空集合
     * @return 已复核范围
     */
    public ResolvedScope lockForPublish(String scopeType, Long scopeId, List<Long> storageIds) {
        return resolve(scopeType, scopeId, storageIds, true, null);
    }

    /**
     * 以冻结发起人身份锁定并复核当前发布目标。
     *
     * @param scopeType 发布作用域
     * @param scopeId 目标 ID
     * @param storageIds 命令显式提供的快照储位，允许空集合
     * @param actorId 冻结发起人用户 ID
     * @return 已复核范围
     */
    public ResolvedScope lockForPublish(
        String scopeType,
        Long scopeId,
        List<Long> storageIds,
        Long actorId
    ) {
        return resolve(scopeType, scopeId, storageIds, true, actorId);
    }

    /**
     * 锁定并复核当前历史读取目标和持久化储位快照。
     *
     * @param scopeType 生效指针作用域
     * @param scopeId 目标 ID
     * @param storageIds 已持久化快照储位，允许空集合
     * @return 已复核范围
     */
    public ResolvedScope lockForRead(String scopeType, Long scopeId, List<Long> storageIds) {
        return resolve(scopeType, scopeId, storageIds, false, null);
    }

    /**
     * 以冻结发起人身份锁定并复核历史读取目标。
     *
     * @param scopeType 发布作用域
     * @param scopeId 目标 ID
     * @param storageIds 已持久化快照储位
     * @param actorId 冻结发起人用户 ID
     * @return 已复核范围
     */
    public ResolvedScope lockForRead(
        String scopeType,
        Long scopeId,
        List<Long> storageIds,
        Long actorId
    ) {
        return resolve(scopeType, scopeId, storageIds, false, actorId);
    }

    private ResolvedScope resolve(
        String scopeType,
        Long scopeId,
        List<Long> storageIds,
        boolean publishing,
        Long actorId
    ) {
        String type = requireScopeType(scopeType);
        assertSchema();
        List<Long> orderedStorageIds = orderedIds(storageIds);
        Candidate candidate = candidate(type, scopeId, orderedStorageIds);

        try {
            if (actorId == null) {
                accessPolicy.lockAndCheckCompanyForShare(candidate.companyId());
            } else {
                accessPolicy.lockAndCheckCompanyForShare(candidate.companyId(), actorId);
            }
        } catch (ServiceException error) {
            if (error.getMessage() != null
                && error.getMessage().startsWith("COMPANY_SCOPE_DENIED")) {
                throw fail(RuleScopeError.COMPANY_DENIED);
            }
            throw error;
        }
        Map<Long, WmsRuleScopeRow> companies = index(
            scopeMapper.lockCompaniesForShare(Set.of(candidate.companyId())),
            WmsRuleScopeRow::getCompanyId
        );
        requireIds(companies, Set.of(candidate.companyId()), RuleScopeError.TARGET_NOT_FOUND);
        validateCompany(companies.get(candidate.companyId()), publishing);

        if (!candidate.warehouseIds().isEmpty()) {
            Map<Long, WmsRuleScopeRow> warehouses = index(
                scopeMapper.lockWarehousesForShare(candidate.warehouseIds()),
                WmsRuleScopeRow::getWarehouseId
            );
            requireIds(warehouses, candidate.warehouseIds(), RuleScopeError.PARENT_CHAIN_INVALID);
            for (Long warehouseId : candidate.warehouseIds()) {
                validateWarehouse(warehouses.get(warehouseId), candidate.companyId(), publishing);
            }
        }

        Map<Long, WmsRuleScopeZoneRow> zones = Map.of();
        if (!candidate.zoneIds().isEmpty()) {
            zones = index(
                scopeMapper.lockZonesForShare(candidate.zoneIds()),
                WmsRuleScopeZoneRow::getZoneId
            );
            requireIds(zones, candidate.zoneIds(), RuleScopeError.PARENT_CHAIN_INVALID);
            for (Long zoneId : candidate.zoneIds()) {
                validateZone(zones.get(zoneId), candidate.warehouseIds(), zones, publishing);
            }
        }

        if (WmsRuleContract.SCOPE_MATERIAL_ZONE.equals(type)
            || WmsRuleContract.SCOPE_DEVICE_ZONE.equals(type)) {
            WmsRuleScopeZoneRow target = zones.get(scopeId);
            String expectedKind = WmsRuleContract.SCOPE_MATERIAL_ZONE.equals(type)
                ? MATERIAL : DEVICE;
            if (target == null || !expectedKind.equals(target.getZoneKind())) {
                throw fail(RuleScopeError.PARENT_CHAIN_INVALID);
            }
        }

        if (publishing) {
            Map<Long, WmsRuleScopeRow> storages = candidate.storageLockIds().isEmpty()
                ? Map.of() : index(
                    scopeMapper.lockStoragesForShare(candidate.storageLockIds()),
                    WmsRuleScopeRow::getSpaceId
                );
            requireIds(storages, candidate.storageLockIds(), RuleScopeError.STORAGE_NOT_FOUND);
            if (WmsRuleContract.SCOPE_STORAGE.equals(type)) {
                validatePublishStorage(storages.get(scopeId), type, scopeId,
                    candidate.warehouseIds(), zones);
            }
            for (Long storageId : candidate.storageIds()) {
                validatePublishStorage(storages.get(storageId), type, scopeId,
                    candidate.warehouseIds(), zones);
            }
        } else {
            Map<Long, WmsRuleScopeRow> storages = candidate.storageLockIds().isEmpty()
                ? Map.of() : index(
                    scopeMapper.lockStoragesForShare(candidate.storageLockIds()),
                    WmsRuleScopeRow::getSpaceId
                );
            requireIds(storages, candidate.storageLockIds(), RuleScopeError.STORAGE_NOT_FOUND);
            if (WmsRuleContract.SCOPE_STORAGE.equals(type)) {
                validateHistoricalStorage(storages.get(scopeId), zones);
            }
            for (Long storageId : candidate.storageIds()) {
                if (!Objects.equals(storageId, scopeId)
                    || !WmsRuleContract.SCOPE_STORAGE.equals(type)) {
                    validateHistoricalStorage(
                        storages.get(storageId), zones
                    );
                }
            }
        }
        return new ResolvedScope(type, scopeId, candidate.companyId(), orderedStorageIds);
    }

    private Candidate candidate(String scopeType, Long scopeId, List<Long> storageIds) {
        List<WmsRuleScopeTargetRow> targetRows =
            scopeMapper.selectTargetCandidate(scopeType, scopeId);
        if (targetRows.isEmpty()) {
            throw fail(RuleScopeError.TARGET_NOT_FOUND);
        }
        WmsRuleScopeTargetRow target = targetRows.get(0);
        validateTargetKind(scopeType, target);
        Long companyId = target.getCompanyId();
        if (companyId == null) {
            throw fail(RuleScopeError.PARENT_CHAIN_INVALID);
        }

        Set<Long> warehouseIds = new LinkedHashSet<>();
        Set<Long> zoneIds = new LinkedHashSet<>();
        addIfNotNull(warehouseIds, target.getWarehouseId());
        addIfNotNull(zoneIds, target.getZoneKind() == null ? null : target.getScopeId());
        addIfNotNull(zoneIds, target.getParentZoneId());

        if (!WmsRuleContract.SCOPE_COMPANY.equals(scopeType) && warehouseIds.isEmpty()) {
            throw fail(RuleScopeError.PARENT_CHAIN_INVALID);
        }

        if (!storageIds.isEmpty()) {
            Map<Long, WmsRuleScopeRow> storageRows = index(
                scopeMapper.selectStorageParents(storageIds), WmsRuleScopeRow::getSpaceId
            );
            requireIds(storageRows, new LinkedHashSet<>(storageIds),
                RuleScopeError.STORAGE_NOT_FOUND);
            for (WmsRuleScopeRow storage : storageRows.values()) {
                addIfNotNull(warehouseIds, storage.getSpaceWarehouseId());
                addNotNull(zoneIds, storage.getZoneId(), RuleScopeError.PARENT_CHAIN_INVALID);
                addNotNull(zoneIds, storage.getDeviceZoneParentZoneId(),
                    RuleScopeError.PARENT_CHAIN_INVALID);
            }
        }
        Set<Long> storageLockIds = new LinkedHashSet<>(storageIds);
        if (WmsRuleContract.SCOPE_STORAGE.equals(scopeType)) {
            storageLockIds.add(scopeId);
            Map<Long, WmsRuleScopeRow> targets = index(
                scopeMapper.selectStorageParents(Set.of(scopeId)), WmsRuleScopeRow::getSpaceId
            );
            requireIds(targets, Set.of(scopeId), RuleScopeError.TARGET_NOT_FOUND);
            WmsRuleScopeRow storageTarget = targets.get(scopeId);
            addNotNull(warehouseIds, storageTarget.getSpaceWarehouseId(),
                RuleScopeError.PARENT_CHAIN_INVALID);
            addNotNull(zoneIds, storageTarget.getZoneId(), RuleScopeError.PARENT_CHAIN_INVALID);
            addNotNull(zoneIds, storageTarget.getDeviceZoneParentZoneId(),
                RuleScopeError.PARENT_CHAIN_INVALID);
        }
        if (!WmsRuleContract.SCOPE_COMPANY.equals(scopeType) && warehouseIds.isEmpty()) {
            throw fail(RuleScopeError.PARENT_CHAIN_INVALID);
        }
        return new Candidate(companyId, orderedIdSet(warehouseIds), orderedIdSet(zoneIds),
            orderedIdSet(storageIds), orderedIdSet(storageLockIds));
    }

    private void validateCompany(WmsRuleScopeRow company, boolean publishing) {
        if (!validLifecycle(company.getCompanyLifecycleStatus(), publishing)) {
            throw fail(publishing ? RuleScopeError.TARGET_RETIRED
                : RuleScopeError.PARENT_CHAIN_INVALID);
        }
    }

    private void validateTargetKind(String scopeType, WmsRuleScopeTargetRow target) {
        if (!WmsRuleContract.SCOPE_COMPANY.equals(scopeType)
            && !WmsRuleContract.SCOPE_WAREHOUSE.equals(scopeType)
            && !NOT_DELETED.equals(target.getDelFlag())) {
            throw fail(RuleScopeError.TARGET_NOT_FOUND);
        }
        if (WmsRuleContract.SCOPE_MATERIAL_ZONE.equals(scopeType)
            && (!MATERIAL.equals(target.getZoneKind()) || target.getParentZoneId() != null)) {
            throw fail(RuleScopeError.PARENT_CHAIN_INVALID);
        }
        if (WmsRuleContract.SCOPE_DEVICE_ZONE.equals(scopeType)
            && (!DEVICE.equals(target.getZoneKind()) || target.getParentZoneId() == null)) {
            throw fail(RuleScopeError.PARENT_CHAIN_INVALID);
        }
    }

    private void validateWarehouse(WmsRuleScopeRow warehouse, Long expectedCompany,
                                   boolean publishing) {
        if (!Objects.equals(warehouse.getWarehouseCompanyId(), expectedCompany)) {
            throw fail(RuleScopeError.PARENT_CHAIN_INVALID);
        }
        if (!validLifecycle(warehouse.getWarehouseLifecycleStatus(), publishing)) {
            throw fail(publishing ? RuleScopeError.TARGET_RETIRED
                : RuleScopeError.PARENT_CHAIN_INVALID);
        }
    }

    private void validateZone(WmsRuleScopeZoneRow zone, Set<Long> warehouseIds,
                              Map<Long, WmsRuleScopeZoneRow> zones, boolean publishing) {
        if (!NOT_DELETED.equals(zone.getDelFlag())
            || !warehouseIds.contains(zone.getWarehouseId())) {
            throw fail(RuleScopeError.PARENT_CHAIN_INVALID);
        }
        if (!validLifecycle(zone.getLifecycleStatus(), publishing)) {
            throw fail(publishing ? RuleScopeError.TARGET_RETIRED
                : RuleScopeError.PARENT_CHAIN_INVALID);
        }
        WmsRuleScopeZoneRow parent = zone.getParentZoneId() == null
            ? null : zones.get(zone.getParentZoneId());
        if (!WmsSpaceHierarchy.validZoneParent(
            zone.getZoneKind(),
            zone.getParentZoneId(),
            parent == null ? null : parent.getZoneKind(),
            parent == null ? null : parent.getWarehouseId(),
            zone.getWarehouseId()
        ) || parent != null && parent.getParentZoneId() != null) {
            throw fail(RuleScopeError.PARENT_CHAIN_INVALID);
        }
    }

    private void validatePublishStorage(WmsRuleScopeRow storage, String scopeType, Long scopeId,
                                        Set<Long> warehouseIds,
                                        Map<Long, WmsRuleScopeZoneRow> zones) {
        if (!NOT_DELETED.equals(storage.getSpaceDelFlag())) {
            throw fail(RuleScopeError.STORAGE_NOT_FOUND);
        }
        if (!warehouseIds.contains(storage.getSpaceWarehouseId())) {
            throw fail(RuleScopeError.STORAGE_OUT_OF_SCOPE);
        }
        if (!validLifecycle(storage.getSpaceLifecycleStatus(), true)) {
            throw fail(RuleScopeError.STORAGE_RETIRED);
        }
        Placement placement = placement(storage, zones);
        if (!belongsToScope(scopeType, scopeId, storage, placement)) {
            throw fail(RuleScopeError.STORAGE_OUT_OF_SCOPE);
        }
    }

    private void validateHistoricalStorage(WmsRuleScopeRow storage,
                                           Map<Long, WmsRuleScopeZoneRow> zones) {
        if (!NOT_DELETED.equals(storage.getSpaceDelFlag())) {
            throw fail(RuleScopeError.STORAGE_NOT_FOUND);
        }
        if (!validLifecycle(storage.getSpaceLifecycleStatus(), false)) {
            throw fail(RuleScopeError.PARENT_CHAIN_INVALID);
        }
        placement(storage, zones);
    }

    private Placement placement(WmsRuleScopeRow storage, Map<Long, WmsRuleScopeZoneRow> zones) {
        WmsRuleScopeZoneRow device = zones.get(storage.getZoneId());
        if (device == null || !WmsSpaceHierarchy.validStorageParent(
            storage.getSpaceWarehouseId(), device.getWarehouseId(), device.getZoneKind())
            || device.getParentZoneId() == null) {
            throw fail(RuleScopeError.STORAGE_OUT_OF_SCOPE);
        }
        WmsRuleScopeZoneRow material = zones.get(device.getParentZoneId());
        if (material == null || !WmsSpaceHierarchy.validZoneParent(
            device.getZoneKind(), device.getParentZoneId(), material.getZoneKind(),
            material.getWarehouseId(), storage.getSpaceWarehouseId())) {
            throw fail(RuleScopeError.STORAGE_OUT_OF_SCOPE);
        }
        return new Placement(material, device);
    }

    private boolean belongsToScope(String scopeType, Long scopeId, WmsRuleScopeRow storage,
                                   Placement placement) {
        return switch (scopeType) {
            case WmsRuleContract.SCOPE_COMPANY ->
                true;
            case WmsRuleContract.SCOPE_WAREHOUSE ->
                Objects.equals(storage.getSpaceWarehouseId(), scopeId);
            case WmsRuleContract.SCOPE_MATERIAL_ZONE ->
                Objects.equals(placement.materialZone().getZoneId(), scopeId);
            case WmsRuleContract.SCOPE_DEVICE_ZONE ->
                Objects.equals(placement.deviceZone().getZoneId(), scopeId);
            case WmsRuleContract.SCOPE_STORAGE ->
                Objects.equals(storage.getSpaceId(), scopeId);
            default -> false;
        };
    }

    /**
     * 校验并返回五类精确作用域之一。
     *
     * @param scopeType 已通过基础文本规范化的作用域
     * @return 原样作用域
     */
    public String requireScopeType(String scopeType) {
        if (!WmsRuleContract.supportsScopeType(scopeType)) {
            throw fail(RuleScopeError.TYPE_UNSUPPORTED);
        }
        return scopeType;
    }

    private void assertSchema() {
        Set<String> actual = new HashSet<>(scopeMapper.selectRequiredColumns());
        if (!actual.containsAll(REQUIRED_COLUMNS)) {
            throw fail(RuleScopeError.SCHEMA_UNSUPPORTED);
        }
    }

    private boolean validLifecycle(String value, boolean publishing) {
        return value != null
            && (publishing ? ACTIVE.equals(value) : HISTORICAL_LIFECYCLES.contains(value));
    }

    private List<Long> orderedIds(Collection<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().distinct().sorted().toList();
    }

    private Set<Long> orderedIdSet(Collection<Long> ids) {
        return ids.stream().distinct().sorted()
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void addIfNotNull(Set<Long> target, Long value) {
        if (value != null) {
            target.add(value);
        }
    }

    private void addNotNull(Set<Long> target, Long value, RuleScopeError error) {
        if (value == null) {
            throw fail(error);
        }
        target.add(value);
    }

    private <T> Map<Long, T> index(Collection<T> rows, Function<T, Long> id) {
        return rows.stream().collect(Collectors.toMap(id, Function.identity(), (first, second) -> {
            throw fail(RuleScopeError.PARENT_CHAIN_INVALID);
        }, LinkedHashMap::new));
    }

    private void requireIds(Map<Long, ?> rows, Set<Long> expected, RuleScopeError error) {
        if (!rows.keySet().equals(expected)) {
            throw fail(error);
        }
    }

    private ServiceException fail(RuleScopeError error) {
        return new ServiceException(error.message(), error.status());
    }

    /**
     * 已复核范围，供同一事务后续业务路径使用。
     *
     * @param scopeType 封闭作用域
     * @param scopeId 目标 ID
     * @param companyId 解析后的唯一公司
     * @param storageIds 去重升序储位快照
     */
    public record ResolvedScope(String scopeType, Long scopeId, Long companyId,
                                List<Long> storageIds) {
    }

    private record Candidate(Long companyId, Set<Long> warehouseIds, Set<Long> zoneIds,
                             Set<Long> storageIds, Set<Long> storageLockIds) {
    }

    private record Placement(WmsRuleScopeZoneRow materialZone, WmsRuleScopeZoneRow deviceZone) {
    }

    private enum RuleScopeError {
        TYPE_UNSUPPORTED(WmsRuleContract.ERROR_SCOPE_TYPE_UNSUPPORTED + ": 不支持的作用域类型",
            HttpStatus.HTTP_CONFLICT),
        SCHEMA_UNSUPPORTED(WmsRuleContract.ERROR_SCOPE_SCHEMA_UNSUPPORTED
            + ": 规则范围目标结构不可用", HttpStatus.HTTP_CONFLICT),
        TARGET_NOT_FOUND(WmsRuleContract.ERROR_SCOPE_TARGET_NOT_FOUND
            + ": 规则目标不可用", HttpStatus.HTTP_NOT_FOUND),
        TARGET_RETIRED(WmsRuleContract.ERROR_SCOPE_TARGET_RETIRED
            + ": 规则目标状态不可发布", HttpStatus.HTTP_CONFLICT),
        PARENT_CHAIN_INVALID(WmsRuleContract.ERROR_SCOPE_PARENT_CHAIN_INVALID
            + ": 规则目标父链不一致", HttpStatus.HTTP_CONFLICT),
        STORAGE_NOT_FOUND(WmsRuleContract.ERROR_SCOPE_STORAGE_NOT_FOUND
            + ": 规则目标储位不可用", HttpStatus.HTTP_NOT_FOUND),
        STORAGE_RETIRED(WmsRuleContract.ERROR_SCOPE_STORAGE_RETIRED
            + ": 规则目标储位状态不可发布", HttpStatus.HTTP_CONFLICT),
        STORAGE_OUT_OF_SCOPE(WmsRuleContract.ERROR_SCOPE_STORAGE_OUT_OF_SCOPE
            + ": 规则目标储位归属不一致", HttpStatus.HTTP_CONFLICT),
        COMPANY_DENIED(WmsRuleContract.ERROR_SCOPE_COMPANY_DENIED
            + ": 当前角色没有该目标的有效管理授权", HttpStatus.HTTP_FORBIDDEN);

        private final String message;

        private final int status;

        RuleScopeError(String message, int status) {
            this.message = message;
            this.status = status;
        }

        String message() {
            return message;
        }

        int status() {
            return status;
        }
    }
}
